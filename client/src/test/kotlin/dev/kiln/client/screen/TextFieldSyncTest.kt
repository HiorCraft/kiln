package dev.kiln.client.screen

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TextFieldSyncTest {

    @Test
    fun `local edits are throttled and the latest value wins`() {
        val sync = TextFieldSync("")
        sync.onLocalEdit("a")
        assertEquals("a", sync.poll(1000))
        sync.onLocalEdit("ab")
        sync.onLocalEdit("abc")
        assertNull(sync.poll(1100)) // within 150 ms
        assertEquals("abc", sync.poll(1150))
        assertNull(sync.poll(2000)) // nothing pending
    }

    @Test
    fun `force sends immediately, unchanged values are not sent`() {
        val sync = TextFieldSync("x")
        sync.onLocalEdit("xy")
        assertEquals("xy", sync.poll(0))
        sync.onLocalEdit("xyz")
        assertEquals("xyz", sync.poll(1, force = true))
        sync.onLocalEdit("xyz")
        assertNull(sync.poll(10_000, force = true))
    }

    @Test
    fun `unchanged server value keeps local text`() {
        val sync = TextFieldSync("")
        sync.onLocalEdit("hello")
        sync.poll(0)
        assertFalse(sync.onServerValue("", "hello wor"))
    }

    @Test
    fun `echo of the reported value keeps local text`() {
        val sync = TextFieldSync("")
        sync.onLocalEdit("ab")
        sync.poll(0)
        assertFalse(sync.onServerValue("ab", "abcd"))
        assertFalse(sync.onServerValue("ab", "abcde"))
    }

    @Test
    fun `real server change replaces local text and drops pending input`() {
        val sync = TextFieldSync("")
        sync.onLocalEdit("abc")
        sync.poll(0)
        sync.onLocalEdit("abcd") // pending, not yet sent
        assertTrue(sync.onServerValue("reset", "abcd"))
        assertNull(sync.pending)
        assertEquals("reset", sync.reportedValue)
        assertNull(sync.poll(10_000))
    }

    @Test
    fun `server can clear after echoing`() {
        val sync = TextFieldSync("")
        sync.onLocalEdit("abc")
        sync.poll(0)
        assertFalse(sync.onServerValue("abc", "abc"))
        assertTrue(sync.onServerValue("", "abc"))
    }

    @Test
    fun `server value equal to local content needs no apply and nothing to report`() {
        val sync = TextFieldSync("")
        sync.onLocalEdit("same")
        assertFalse(sync.onServerValue("same", "same"))
        assertNull(sync.poll(10_000))
    }
}
