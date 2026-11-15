package app.opendisplay.android.ui

/**
 * Delta between two committed text mirror values. The hidden editor also
 * holds the input method's composing text; the caller removes that span
 * before asking for a delta, so pinyin/romaji previews never reach the Mac.
 */
internal object RemoteTextDiff {
    data class Change(val deletions: Int, val inserted: String)

    fun between(before: String, after: String): Change {
        val old = before.codePointArray()
        val new = after.codePointArray()
        var prefix = 0
        while (prefix < old.size && prefix < new.size && old[prefix] == new[prefix]) {
            prefix++
        }
        var suffix = 0
        while (suffix < old.size - prefix &&
               suffix < new.size - prefix &&
               old[old.size - 1 - suffix] == new[new.size - 1 - suffix]) {
            suffix++
        }
        val inserted = buildString {
            for (i in prefix until new.size - suffix) appendCodePoint(new[i])
        }
        return Change(old.size - prefix - suffix, inserted)
    }

    private fun String.codePointArray(): IntArray {
        val points = ArrayList<Int>(length)
        var i = 0
        while (i < length) {
            val point = codePointAt(i)
            points.add(point)
            i += Character.charCount(point)
        }
        return points.toIntArray()
    }
}
