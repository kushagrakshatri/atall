package app.atall.coordinator.adapters

// C3: approval tokens end to end (protocol.md §5.4). An agent that wants to spend money, book, or change an account
// calls atall_request_approval; the phone shows it; "Approve" asks for a fingerprint, and only then signs a
// single-use, short-lived token with a Keystore key that can't sign without one. "Deny" needs no fingerprint.

import app.atall.relay.B64
import app.atall.relay.CanonicalJson
import app.atall.relay.RelayClient
import app.atall.relay.RelayEvent
import app.atall.relay.RelayException
import app.atall.relay.sha256
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit

/** One approval request from an agent (an `approval_request` event, countersigned by the relay). */
data class ApprovalRequest(
  val approvalId: String,
  val taskId: String,
  val ref: String,
  val relayAgentId: String,
  /** The agent in the user's roster, when the task was one of ours. */
  val peerId: String?,
  val action: String,
  val reason: String? = null,
  val amount: Double? = null,
  val currency: String? = null,
  val url: String? = null,
  /** The event payload exactly as the relay countersigned it: what [actionHash] is computed over. */
  val payload: JsonObject,
  val at: Long,
) {
  /** base64url(sha256(canonical JSON of the payload)): binds the token to exactly this request. The relay checks it. */
  val actionHash: String get() = actionHashOf(payload)

  /** Money or an account change: always true here, since an agent only asks when it must not act alone. */
  val spendsMoney: Boolean get() = (amount ?: 0.0) > 0.0

  companion object {
    fun actionHashOf(payload: JsonObject): String = B64.url(sha256(CanonicalJson.encode(payload).toByteArray(Charsets.UTF_8)))

    /** From a verified relay event; null when it isn't a usable approval request. */
    fun from(e: RelayEvent, peerId: String?, at: Long): ApprovalRequest? {
      if (e.type != RelayEvent.APPROVAL_REQUEST || !e.verified) return null
      val p = e.payload as? JsonObject ?: return null
      fun s(k: String) = (p[k] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
      return ApprovalRequest(
        approvalId = s("approval_id") ?: return null,
        taskId = e.taskId ?: return null,
        ref = s("ref") ?: "",
        relayAgentId = e.agentId,
        peerId = peerId,
        action = s("action") ?: return null,
        reason = s("reason"),
        amount = (p["amount"] as? JsonPrimitive)?.doubleOrNull,
        currency = s("currency"),
        url = s("url"),
        payload = p,
        at = at,
      )
    }
  }
}

/**
 * Signs approval tokens. On the phone: a P-256 Android Keystore key created with user authentication required for every
 * use (biometric strong), so a signature can only be made inside a fingerprint prompt. Tests use a software key with a
 * switch for "the user confirmed".
 */
interface ApprovalSigner {
  val publicKeySpkiB64: String

  /** Shows the fingerprint prompt for [req] and signs [data] after it succeeds. Null: cancelled, failed, or unavailable. */
  suspend fun signWithFingerprint(req: ApprovalRequest, data: ByteArray): ByteArray?
}

/** Where decisions go: the relay's /phone/approval-key and /phone/approvals. */
interface ApprovalTransport {
  suspend fun registerKey(publicKeySpkiB64: String)
  suspend fun decide(body: JsonObject)

  companion object {
    fun of(client: RelayClient) = object : ApprovalTransport {
      override suspend fun registerKey(publicKeySpkiB64: String) = client.registerApprovalKey(publicKeySpkiB64)
      override suspend fun decide(body: JsonObject) { client.decideApproval(body) }
    }
  }
}

sealed interface ApprovalResult {
  data class Approved(val token: JsonObject) : ApprovalResult
  data object Denied : ApprovalResult
  /** The fingerprint prompt was cancelled or failed: nothing was sent. */
  data object Cancelled : ApprovalResult
  data object Unknown : ApprovalResult
  data object AlreadyDecided : ApprovalResult
  data class Failed(val reason: String) : ApprovalResult
}

/**
 * The phone's approval gate. Every request waits in [pending] until the user decides. [approve] never sends anything
 * without a signature from [signer], which needs a fingerprint; a token is single use, bound to the request's action
 * hash, the task and the agent, capped at the amount asked, and expires in [tokenTtlMs] (≤ 15 min on the relay).
 */
class ApprovalGate(
  private val transport: ApprovalTransport,
  private val signer: ApprovalSigner,
  private val clock: () -> Long = System::currentTimeMillis,
  /** Whether this phone's approval key is already on the relay (kept in prefs on the phone). */
  private val keyRegistered: () -> Boolean = { false },
  private val onKeyRegistered: () -> Unit = {},
  private val tokenTtlMs: Long = 10 * 60_000L,
  private val newJti: () -> String = { "apv_" + B64.url(ByteArray(12).also { SecureRandom().nextBytes(it) }) },
  private val note: (String) -> Unit = {},
) {
  private val _pending = MutableStateFlow<List<ApprovalRequest>>(emptyList())
  val pending: StateFlow<List<ApprovalRequest>> = _pending
  private val decided = HashSet<String>()
  private val mutex = Mutex()
  @Volatile private var registered = false

  /** A new request (duplicates and already-decided ones are ignored). */
  fun onRequest(r: ApprovalRequest) {
    synchronized(decided) {
      if (r.approvalId in decided || _pending.value.any { it.approvalId == r.approvalId }) return
      _pending.value = _pending.value + r
    }
    note("approval.request ${r.approvalId} ${r.relayAgentId} #${r.ref}")
  }

  fun get(approvalId: String): ApprovalRequest? = _pending.value.firstOrNull { it.approvalId == approvalId }

  /** The token the phone signs for [r] (also what the relay re-derives and checks). */
  fun tokenFor(r: ApprovalRequest, now: Long = clock()): JsonObject = buildJsonObject {
    put("v", 1)
    put("jti", newJti())
    put("approval_id", r.approvalId)
    put("task_id", r.taskId)
    put("agent_id", r.relayAgentId)
    put("action_hash", r.actionHash)
    put("decision", "approve")
    if (r.amount != null) put("max_amount", buildJsonObject { put("value", r.amount); r.currency?.let { put("currency", it) } })
    put("nbf", iso(now))
    put("exp", iso(now + tokenTtlMs))
    put("uses", 1)
  }

  /** Fingerprint, then a signed token to the relay. Nothing is sent when the fingerprint doesn't go through. */
  suspend fun approve(approvalId: String): ApprovalResult = mutex.withLock {
    val r = get(approvalId) ?: return if (synchronized(decided) { approvalId in decided }) ApprovalResult.AlreadyDecided else ApprovalResult.Unknown
    val token = tokenFor(r)
    val sig = try { signer.signWithFingerprint(r, CanonicalJson.encode(token).toByteArray(Charsets.UTF_8)) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    if (sig == null) { note("approval.cancelled ${r.approvalId}"); return ApprovalResult.Cancelled }
    try {
      ensureKey()
      transport.decide(buildJsonObject {
        put("approval_id", r.approvalId)
        put("decision", "approve")
        put("token", token)
        put("token_sig", B64.url(sig))
      })
    } catch (e: CancellationException) { throw e } catch (e: Exception) {
      note("approval.error ${r.approvalId} ${describe(e)}")
      if (e is RelayException && e.status == 409) { settle(approvalId); return ApprovalResult.AlreadyDecided }
      return ApprovalResult.Failed(describe(e))
    }
    settle(approvalId)
    note("approval.approved ${r.approvalId}")
    ApprovalResult.Approved(token)
  }

  /** No fingerprint needed to say no. */
  suspend fun deny(approvalId: String): ApprovalResult = mutex.withLock {
    val r = get(approvalId) ?: return if (synchronized(decided) { approvalId in decided }) ApprovalResult.AlreadyDecided else ApprovalResult.Unknown
    try {
      transport.decide(buildJsonObject { put("approval_id", r.approvalId); put("decision", "deny") })
    } catch (e: CancellationException) { throw e } catch (e: Exception) {
      if (e is RelayException && e.status == 409) { settle(approvalId); return ApprovalResult.AlreadyDecided }
      return ApprovalResult.Failed(describe(e))
    }
    settle(approvalId)
    note("approval.denied ${r.approvalId}")
    ApprovalResult.Denied
  }

  private suspend fun ensureKey() {
    if (registered || keyRegistered()) return
    transport.registerKey(signer.publicKeySpkiB64)
    registered = true
    onKeyRegistered()
  }

  private fun settle(id: String) = synchronized(decided) {
    decided += id
    _pending.value = _pending.value.filterNot { it.approvalId == id }
  }

  private fun describe(e: Exception) = when (e) {
    is RelayException -> "relay ${e.status}: ${e.error}"
    is IOException -> "unreachable"
    else -> e.javaClass.simpleName
  }

  private fun iso(ms: Long) = Instant.ofEpochMilli(ms).truncatedTo(ChronoUnit.SECONDS).toString()
}
