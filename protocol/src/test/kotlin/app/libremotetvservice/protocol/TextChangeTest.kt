package app.libremotetvservice.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class TextChangeTest {

    private fun apply(old: String, change: TextChange) =
        old.substring(0, change.start) + change.inserted + old.substring(change.end)

    private fun check(old: String, new: String, expected: TextChange) {
        val change = TextChange.between(old, new)
        assertEquals(expected, change)
        assertEquals(new, apply(old, change))
    }

    @Test
    fun appends() = check("star", "star wars", TextChange(4, 4, " wars"))

    @Test
    fun deletesAtTheEnd() = check("star wars", "star", TextChange(4, 9, ""))

    @Test
    fun replacesInTheMiddle() = check("the cat sat", "the dog sat", TextChange(4, 7, "dog"))

    @Test
    fun insertsInRepeatedText() = check("aaa", "aaaa", TextChange(3, 3, "a"))

    @Test
    fun clears() = check("abc", "", TextChange(0, 3, ""))

    @Test
    fun keepsSurrogatePairsWhole() {
        // U+1F600 and U+1F601 share their high surrogate.
        val change = TextChange.between("a😀b", "a😁b")
        assertEquals(TextChange(1, 3, "😁"), change)
        // And a pair that differs only in its high surrogate keeps the shared low one in the change.
        val other = TextChange.between("a😀", "a🨀")
        assertEquals(TextChange(1, 3, "🨀"), other)
    }
}
