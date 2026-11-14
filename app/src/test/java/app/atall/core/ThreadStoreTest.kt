package app.atall.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ThreadStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun persistsAndFoldsSnapshots() {
        val f = File(tmp.root, "thread.jsonl")
        val s = ThreadStore(f)
        s.upsert(ThreadMessage("a", 1, ThreadMessage.USER, "hi", status = SendStatus.SENDING))
        s.upsert(ThreadMessage("b", 2, Main.AUTHOR, ""))
        s.update("b") { it.copy(text = "hello") }
        s.update("a") { it.copy(status = SendStatus.SENT) }
        val again = ThreadStore(f)
        assertEquals(listOf("hi", "hello"), again.all().map { it.text })
        assertEquals(SendStatus.SENT, again.get("a")!!.status)
    }

    @Test fun dedupesBySource() {
        val s = ThreadStore(File(tmp.root, "t.jsonl"))
        assertTrue(s.upsert(ThreadMessage("x", 1, "p1", "yo", sourceId = "in|p1|1|abc")))
        assertFalse(s.upsert(ThreadMessage("y", 1, "p1", "yo", sourceId = "in|p1|1|abc")))
        assertEquals(1, s.all().size)
    }

    @Test fun skipsTornLineAndCompacts() {
        val f = File(tmp.root, "t.jsonl")
        val s = ThreadStore(f, maxMessages = 50)
        repeat(120) { i -> s.upsert(ThreadMessage("m$i", i.toLong(), ThreadMessage.USER, "n$i")) }
        f.appendText("{\"id\":\"torn")
        val again = ThreadStore(f, maxMessages = 50)
        assertTrue(again.all().size <= 50)
        assertEquals("n119", again.all().last().text)
        assertTrue(f.readLines().size < 200)
    }

    @Test fun ordersByTime() {
        val s = ThreadStore(File(tmp.root, "t.jsonl"))
        s.upsert(ThreadMessage("late", 20, ThreadMessage.USER, "2"))
        s.upsert(ThreadMessage("early", 10, ThreadMessage.USER, "1"))
        assertEquals(listOf("1", "2"), s.all().map { it.text })
    }
}
