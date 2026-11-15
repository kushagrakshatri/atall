package app.atall.relay

import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.coroutineContext

/**
 * Polls /phone/events from the stored cursor, hands each page to [onEvents], then saves and acks the new cursor.
 * The cursor only moves after [onEvents] returns, so a crash mid-page re-delivers that page (handle events idempotently).
 */
class EventsPoller(
    private val client: RelayClient,
    private val store: RelayStore,
    private val onEvents: suspend (List<RelayEvent>) -> Unit,
) {
    var cursor: JsonPrimitive?
        get() = store.cursorJson?.let { runCatching { Json.parseToJsonElement(it) as? JsonPrimitive }.getOrNull() }
        set(v) { store.cursorJson = v?.toString() }

    /** One poll. Returns the events delivered (possibly none). */
    suspend fun pollOnce(): List<RelayEvent> {
        val page = client.events(cursor)
        val next = page.nextCursor ?: page.events.lastOrNull()?.cursor?.takeUnless { it is kotlinx.serialization.json.JsonNull }
        if (page.events.isNotEmpty()) onEvents(page.events)
        if (next != null && next != cursor) {
            cursor = next
            if (page.events.isNotEmpty()) runCatching { client.ack(next) } // a failed ack only delays the TTL clean-up
        }
        return page.events
    }

    /**
     * Polls until cancelled: every [intervalMs], right away again after a non-empty page, backing off to [maxBackoffMs]
     * on errors. [onError] sees each failure (a 401 means the device was revoked or the clock is off).
     */
    suspend fun run(intervalMs: Long = 4_000, maxBackoffMs: Long = 60_000, onError: (Throwable) -> Unit = {}) {
        var backoff = intervalMs
        while (coroutineContext.isActive) {
            val wait = try {
                val got = pollOnce()
                backoff = intervalMs
                if (got.isNotEmpty()) 0L else intervalMs
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                onError(e)
                backoff = (backoff * 2).coerceAtMost(maxBackoffMs)
                backoff
            }
            if (wait > 0) delay(wait)
        }
    }
}
