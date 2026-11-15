package app.opendisplay.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReverseConnectionTest {
    @Test
    fun requestCarriesTheReceiverIdentity() {
        val request = ReverseConnection.request(
            installId = "ABC-123",
            deviceName = "Pixel Pad",
            deviceKind = "Android tablet",
        )

        assertEquals("connectRequest", request.getString("type"))
        assertEquals(ReverseConnection.VERSION, request.getInt("pv"))
        assertEquals("ABC-123", request.getString("id"))
        assertEquals("Pixel Pad", request.getString("name"))
        assertEquals("Android tablet", request.getString("device"))
    }

    @Test
    fun parsesAcceptedAndRejectedReplies() {
        assertEquals(
            ReverseConnection.Reply.Accepted,
            ReverseConnection.parseReply("""{"type":"accepted","pv":1}""".toByteArray()),
        )
        assertEquals(
            ReverseConnection.Reply.Rejected("unknownDevice"),
            ReverseConnection.parseReply(
                """{"type":"rejected","pv":1,"reason":"unknownDevice"}""".toByteArray()),
        )
    }

    @Test
    fun unknownOrMalformedRepliesAreIgnored() {
        assertNull(ReverseConnection.parseReply("not json".toByteArray()))
        assertNull(ReverseConnection.parseReply("""{"type":"reply"}""".toByteArray()))
        assertNull(ReverseConnection.parseReply("""{"type":"accepted","pv":2}""".toByteArray()))
    }
}
