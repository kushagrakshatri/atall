package app.atall.inbox

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Everything the inbox persists. Text inside is redacted; codes are never here. */
@Serializable
data class InboxSnapshot(
  val items: List<InboxItem> = emptyList(),
  /** Ingested message ids with when, oldest first (re-ingest guard). */
  val seen: Map<String, Long> = emptyMap(),
  /** Small named numbers (last digest time, ...). */
  val meta: Map<String, Long> = emptyMap(),
)

/**
 * Durable state. The inbox keeps everything in memory and writes through, so implementations only need to be
 * correct, not fast. Calls come from the inbox under its lock (never concurrent).
 */
interface InboxStore {
  fun load(): InboxSnapshot
  fun upsert(item: InboxItem)
  fun remove(itemId: String)
  fun markSeen(messageId: String, at: Long)
  fun putMeta(key: String, value: Long)

  companion object {
    const val SEEN_MAX = 5000
  }
}

/** For tests. */
class InMemoryInboxStore : InboxStore {
  private val items = LinkedHashMap<String, InboxItem>()
  private val seen = LinkedHashMap<String, Long>()
  private val meta = LinkedHashMap<String, Long>()
  override fun load() = InboxSnapshot(items.values.toList(), LinkedHashMap(seen), LinkedHashMap(meta))
  override fun upsert(item: InboxItem) { items[item.id] = item }
  override fun remove(itemId: String) { items.remove(itemId) }
  override fun markSeen(messageId: String, at: Long) {
    seen.remove(messageId); seen[messageId] = at
    while (seen.size > InboxStore.SEEN_MAX) seen.remove(seen.keys.first())
  }
  override fun putMeta(key: String, value: Long) { meta[key] = value }
}

/**
 * Plain files under [dir] (app.noBackupFilesDir/inbox in production):
 *  - snapshot.json   full state, rewritten atomically (temp file + rename) on compaction
 *  - journal.jsonl   one [Rec] per line, appended on every change since the snapshot
 *
 * Crash safety:
 *  - a torn last journal line (crash mid-append) is skipped on read and newline-terminated before the next append;
 *  - every record is idempotent (full item upserts, set-style seen/meta), so a crash after the new snapshot is in
 *    place but before the journal is truncated just replays records the snapshot already holds;
 *  - an unreadable line is skipped, never fatal; an unreadable snapshot loads as empty plus the journal, and the
 *    damaged file is kept aside as snapshot.corrupt-<time>.json for inspection.
 */
class FileInboxStore(
  private val dir: File,
  private val compactAfter: Int = 500,
) : InboxStore {
  @Serializable
  data class Rec(
    /** "item" | "del" | "seen" | "meta" */
    val t: String,
    val item: InboxItem? = null,
    val id: String? = null,
    val at: Long? = null,
    val key: String? = null,
    val value: Long? = null,
  )

  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }
  private val snapFile = File(dir, "snapshot.json")
  private val journal = File(dir, "journal.jsonl")

  private val items = LinkedHashMap<String, InboxItem>()
  private val seen = LinkedHashMap<String, Long>()
  private val meta = LinkedHashMap<String, Long>()
  private var loaded = false
  private var journalLines = 0

  /** Lines skipped while loading (torn or corrupt). Exposed for tests and diagnostics. */
  var skippedOnLoad = 0
    private set

  init { dir.mkdirs() }

  @Synchronized override fun load(): InboxSnapshot {
    ensureLoaded()
    return InboxSnapshot(items.values.toList(), LinkedHashMap(seen), LinkedHashMap(meta))
  }

  @Synchronized override fun upsert(item: InboxItem) { ensureLoaded(); apply(Rec("item", item = item)); write(Rec("item", item = item)) }
  @Synchronized override fun remove(itemId: String) { ensureLoaded(); apply(Rec("del", id = itemId)); write(Rec("del", id = itemId)) }
  @Synchronized override fun markSeen(messageId: String, at: Long) {
    ensureLoaded(); val r = Rec("seen", id = messageId, at = at); apply(r); write(r)
  }
  @Synchronized override fun putMeta(key: String, value: Long) {
    ensureLoaded(); val r = Rec("meta", key = key, value = value); apply(r); write(r)
  }

  /** Writes the snapshot and empties the journal. Called automatically every [compactAfter] records. */
  @Synchronized fun compact() {
    ensureLoaded()
    val snap = InboxSnapshot(items.values.toList(), LinkedHashMap(seen), LinkedHashMap(meta))
    writeAtomic(snapFile, json.encodeToString(InboxSnapshot.serializer(), snap))
    writeAtomic(journal, "")
    journalLines = 0
  }

  private fun ensureLoaded() {
    if (loaded) return
    loaded = true
    if (snapFile.exists()) {
      val snap = runCatching { json.decodeFromString(InboxSnapshot.serializer(), snapFile.readText()) }.getOrNull()
      if (snap == null) {
        skippedOnLoad++
        runCatching { Files.move(snapFile.toPath(), File(dir, "snapshot.corrupt-${System.currentTimeMillis()}.json").toPath()) }
      } else {
        snap.items.forEach { items[it.id] = it }
        seen.putAll(snap.seen)
        meta.putAll(snap.meta)
      }
    }
    if (journal.exists()) {
      healTail(journal)
      for (line in journal.readLines(Charsets.UTF_8)) {
        if (line.isBlank()) continue
        journalLines++
        val r = runCatching { json.decodeFromString(Rec.serializer(), line) }.getOrNull()
        if (r == null) { skippedOnLoad++; continue }
        apply(r)
      }
    }
  }

  private fun apply(r: Rec) {
    when (r.t) {
      "item" -> r.item?.let { items[it.id] = it }
      "del" -> r.id?.let { items.remove(it) }
      "seen" -> if (r.id != null) {
        seen.remove(r.id); seen[r.id] = r.at ?: 0
        while (seen.size > InboxStore.SEEN_MAX) seen.remove(seen.keys.first())
      }
      "meta" -> if (r.key != null && r.value != null) meta[r.key] = r.value
    }
  }

  private fun write(r: Rec) {
    journal.appendText(json.encodeToString(Rec.serializer(), r) + "\n", Charsets.UTF_8)
    if (++journalLines >= compactAfter) compact()
  }

  private fun healTail(f: File) {
    if (f.length() == 0L) return
    val last = f.inputStream().use { it.skip(f.length() - 1); it.read() }
    if (last != '\n'.code) f.appendText("\n")
  }

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
