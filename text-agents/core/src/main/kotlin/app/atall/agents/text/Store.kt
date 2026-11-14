package app.atall.agents.text

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Durable state. The hub keeps everything in memory and writes through, so implementations only need to be
 * correct, not fast. Calls come from the hub under its lock (no concurrent calls).
 */
interface TextAgentStore {
  fun agents(): List<AgentConfig>
  fun saveAgents(agents: List<AgentConfig>)

  fun append(entry: LogEntry)
  /** Newest [limit] entries, oldest first; only [agent]'s when non-null. */
  fun log(agent: AgentId?, limit: Int): List<LogEntry>

  fun requests(): List<OutstandingRequest>
  fun saveRequests(requests: List<OutstandingRequest>)

  /** Dedupe keys (message ids, used correlation ids) with when they were added, oldest first. */
  fun keys(): LinkedHashMap<String, Long>
  fun addKey(key: String, at: Long)

  /** Small named numbers (per-agent watermarks, last user line times). */
  fun meta(): Map<String, Long>
  fun saveMeta(meta: Map<String, Long>)

  companion object {
    const val LOG_MAX = 2000
    const val LOG_KEEP = 1600
    const val KEYS_MAX = 5000
  }
}

/** For tests and the fake transport. */
class InMemoryStore : TextAgentStore {
  private var agents = listOf<AgentConfig>()
  private val log = ArrayList<LogEntry>()
  private var requests = listOf<OutstandingRequest>()
  private val keys = LinkedHashMap<String, Long>()
  private var meta = mapOf<String, Long>()

  override fun agents() = agents
  override fun saveAgents(agents: List<AgentConfig>) { this.agents = agents.toList() }
  override fun append(entry: LogEntry) {
    log.add(entry)
    if (log.size > TextAgentStore.LOG_MAX) log.subList(0, log.size - TextAgentStore.LOG_KEEP).clear()
  }
  override fun log(agent: AgentId?, limit: Int) = log.filter { agent == null || it.agentId == agent }.takeLast(limit)
  override fun requests() = requests
  override fun saveRequests(requests: List<OutstandingRequest>) { this.requests = requests.toList() }
  override fun keys() = LinkedHashMap(keys)
  override fun addKey(key: String, at: Long) {
    keys[key] = at
    while (keys.size > TextAgentStore.KEYS_MAX) keys.remove(keys.keys.first())
  }
  override fun meta() = meta
  override fun saveMeta(meta: Map<String, Long>) { this.meta = meta.toMap() }
}

/**
 * Plain files under [dir] (app.filesDir/text-agents in production):
 *  - agents.json    registry, rewritten atomically (temp file + rename)
 *  - log.jsonl      one LogEntry per line, appended; trimmed to the newest LOG_KEEP past LOG_MAX
 *  - requests.json  outstanding requests, rewritten atomically on change (a handful of entries)
 *  - keys.txt       "<at>\t<key>" per line, appended; compacted to the newest KEYS_MAX past 2x
 *  - meta.json      small map, rewritten atomically
 * A torn last line (crash mid-append) is skipped on read and newline-terminated before the next append.
 */
class FileTextAgentStore(private val dir: File) : TextAgentStore {
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }
  private val agentsFile = File(dir, "agents.json")
  private val logFile = File(dir, "log.jsonl")
  private val requestsFile = File(dir, "requests.json")
  private val keysFile = File(dir, "keys.txt")
  private val metaFile = File(dir, "meta.json")
  private var logLines = -1
  private var keyLines = -1

  init {
    dir.mkdirs()
    healTail(logFile)
    healTail(keysFile)
  }

  override fun agents(): List<AgentConfig> =
    decode(agentsFile, ListSerializer(AgentConfig.serializer())) ?: emptyList()

  override fun saveAgents(agents: List<AgentConfig>) =
    writeAtomic(agentsFile, json.encodeToString(ListSerializer(AgentConfig.serializer()), agents))

  override fun append(entry: LogEntry) {
    logFile.appendText(json.encodeToString(LogEntry.serializer(), entry) + "\n")
    if (logLines < 0) logLines = readLines(logFile).size else logLines++
    if (logLines > TextAgentStore.LOG_MAX) {
      val keep = readLines(logFile).takeLast(TextAgentStore.LOG_KEEP)
      writeAtomic(logFile, keep.joinToString("\n", postfix = "\n"))
      logLines = keep.size
    }
  }

  override fun log(agent: AgentId?, limit: Int): List<LogEntry> {
    val out = ArrayList<LogEntry>()
    val lines = readLines(logFile)
    for (i in lines.indices.reversed()) {
      if (out.size >= limit) break
      val e = runCatching { json.decodeFromString(LogEntry.serializer(), lines[i]) }.getOrNull() ?: continue
      if (agent == null || e.agentId == agent) out.add(e)
    }
    return out.asReversed()
  }

  override fun requests(): List<OutstandingRequest> =
    decode(requestsFile, ListSerializer(OutstandingRequest.serializer())) ?: emptyList()

  override fun saveRequests(requests: List<OutstandingRequest>) =
    writeAtomic(requestsFile, json.encodeToString(ListSerializer(OutstandingRequest.serializer()), requests))

  override fun keys(): LinkedHashMap<String, Long> {
    val out = LinkedHashMap<String, Long>()
    for (l in readLines(keysFile)) {
      val tab = l.indexOf('\t')
      if (tab <= 0 || tab == l.length - 1) continue
      val at = l.substring(0, tab).toLongOrNull() ?: continue
      out.remove(l.substring(tab + 1))
      out[l.substring(tab + 1)] = at
    }
    while (out.size > TextAgentStore.KEYS_MAX) out.remove(out.keys.first())
    return out
  }

  override fun addKey(key: String, at: Long) {
    require(key.isNotEmpty() && '\n' !in key && '\t' !in key) { "bad key" }
    keysFile.appendText("$at\t$key\n")
    if (keyLines < 0) keyLines = readLines(keysFile).size else keyLines++
    if (keyLines > 2 * TextAgentStore.KEYS_MAX) {
      val kept = keys()
      writeAtomic(keysFile, kept.entries.joinToString("") { "${it.value}\t${it.key}\n" })
      keyLines = kept.size
    }
  }

  override fun meta(): Map<String, Long> =
    decode(metaFile, MapSerializer(String.serializer(), Long.serializer())) ?: emptyMap()

  override fun saveMeta(meta: Map<String, Long>) =
    writeAtomic(metaFile, json.encodeToString(MapSerializer(String.serializer(), Long.serializer()), meta))

  // ------------------------------------------------------------------ files

  private fun <T> decode(f: File, s: kotlinx.serialization.KSerializer<T>): T? =
    if (!f.exists()) null else runCatching { json.decodeFromString(s, f.readText()) }.getOrNull()

  private fun readLines(f: File): List<String> =
    if (!f.exists()) emptyList() else f.readLines(Charsets.UTF_8).filter { it.isNotBlank() }

  private fun healTail(f: File) {
    if (!f.exists() || f.length() == 0L) return
    val last = f.inputStream().use { it.skip(f.length() - 1); it.read() }
    if (last != '\n'.code) f.appendText("\n")
  }

  /** Temp file + atomic rename, so a crash never leaves half a file. */
  private fun writeAtomic(f: File, text: String) {
    val tmp = File(dir, ".${f.name}.tmp")
    tmp.writeText(text, Charsets.UTF_8)
    try {
      Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
      Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
  }
}
