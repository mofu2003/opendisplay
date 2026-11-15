package app.opendisplay.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteTextDiffTest {
    @Test
    fun typingAppendsOnlyTheNewText() {
        assertEquals(
            RemoteTextDiff.Change(0, "好"),
            RemoteTextDiff.between("你", "你好"),
        )
    }

    @Test
    fun autocorrectBackspacesTheReplacedWord() {
        assertEquals(
            RemoteTextDiff.Change(2, "he"),
            RemoteTextDiff.between("teh", "the"),
        )
    }

    @Test
    fun emojiDiffDoesNotSplitSurrogatePairs() {
        assertEquals(
            RemoteTextDiff.Change(1, "😀"),
            RemoteTextDiff.between("a🙂", "a😀"),
        )
    }
}
