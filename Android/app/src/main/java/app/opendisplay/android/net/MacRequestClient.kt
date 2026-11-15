package app.opendisplay.android.net

import app.opendisplay.android.protocol.ReverseConnection
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

object MacRequestClient {
    fun request(
        target: MacRequestTarget,
        payload: JSONObject,
        result: (Result<ReverseConnection.Reply>) -> Unit,
    ) {
        Thread({
            val reply = runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(target.host, target.port), 5_000)
                    socket.soTimeout = 5_000
                    socket.tcpNoDelay = true
                    DataOutputStream(socket.getOutputStream()).apply {
                        write(encode(payload.toString().toByteArray(Charsets.UTF_8)))
                        flush()
                    }
                    val data = readFrame(DataInputStream(socket.getInputStream()))
                    ReverseConnection.parseReply(data) ?: error("Invalid reply from Mac")
                }
            }
            result(reply)
        }, "od-mac-request").apply { isDaemon = true }.start()
    }

    private fun encode(payload: ByteArray): ByteArray {
        require(payload.isNotEmpty() && payload.size <= MAX_FRAME)
        val out = ByteArray(4 + payload.size)
        val size = payload.size
        out[0] = (size ushr 24).toByte()
        out[1] = (size ushr 16).toByte()
        out[2] = (size ushr 8).toByte()
        out[3] = size.toByte()
        payload.copyInto(out, 4)
        return out
    }

    private fun readFrame(input: DataInputStream): ByteArray {
        val length = input.readInt()
        if (length !in 1..MAX_FRAME) error("Invalid reply length from Mac")
        return ByteArray(length).also { input.readFully(it) }
    }

    private const val MAX_FRAME = 64 * 1024
}
