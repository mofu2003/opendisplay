package app.opendisplay.android.sender

import android.content.Context
import android.media.projection.MediaProjection
import app.opendisplay.android.R
import app.opendisplay.android.input.InputAccessibilityService
import app.opendisplay.android.protocol.Framing
import app.opendisplay.android.protocol.Messages
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.min

/**
 * Android as the source: connect to the Mac receiver, read its hello, then
 * stream Annex B H.264 framed exactly like the Mac sender does.
 */
class SenderSession(
    private val context: Context,
    private val projection: MediaProjection,
    private val host: String,
    private val port: Int,
) {
    @Volatile private var running = true
    @Volatile private var stopped = false
    private var socket: Socket? = null
    private var encoder: ScreenEncoder? = null

    fun start() {
        Thread({ run() }, "od-sender").apply { isDaemon = true }.start()
    }

    fun stop() {
        if (stopped) return
        stopped = true
        running = false
        encoder?.stop()
        encoder = null
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
        try {
            projection.stop()
        } catch (_: Exception) {
        }
    }

    private fun run() {
        try {
            val socket = Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(host, port), 5000)
            }
            this.socket = socket
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

            val helloBytes = Framing.readFrame(input) ?: throw IOException("Mac closed the connection")
            val hello = JSONObject(String(helloBytes, Charsets.UTF_8))
            if (hello.optString("type") != "hello") throw IOException("Unexpected greeting")
            if (hello.optString("device") != "Mac") {
                throw IOException(context.getString(R.string.sender_not_mac))
            }

            val metrics = context.resources.displayMetrics
            val scale = min(1.0, 2560.0 / maxOf(metrics.widthPixels, metrics.heightPixels))
            val width = ((metrics.widthPixels * scale).toInt() / 2) * 2
            val height = ((metrics.heightPixels * scale).toInt() / 2) * 2

            val sink = SenderSink(output)
            sink.json(JSONObject().put("type", "welcome").put("pv", 4).put("min", 1))
            sink.json(JSONObject().apply {
                put("type", "streamConfig")
                put("codec", "h264")
                put("width", width)
                put("height", height)
                put("framesPerSecond", 30)
            })
            SenderService.status = context.getString(R.string.sender_active)

            val encoder = ScreenEncoder(context, projection, width, height,
                metrics.densityDpi, sink)
            this.encoder = encoder
            encoder.start()

            while (running) {
                val frame = Framing.readFrame(input) ?: break
                if (frame.isEmpty() || frame[0] != '{'.code.toByte()) continue
                handle(JSONObject(String(frame, Charsets.UTF_8)), sink)
            }
        } catch (e: Exception) {
            if (running) {
                SenderService.status = context.getString(
                    R.string.sender_error, e.message ?: e.javaClass.simpleName)
            }
        } finally {
            stop()
        }
    }

    private fun handle(message: JSONObject, sink: SenderSink) {
        val input = InputAccessibilityService.instance
        when (message.optString("type")) {
            "ping" -> sink.json(JSONObject()
                .put("type", "pong")
                .put("t", message.optDouble("t"))
                .put("mt", System.currentTimeMillis().toDouble()))
            "touch" -> input?.handleTouch(
                message.optString("phase"),
                message.optDouble("x"), message.optDouble("y"))
            "text" -> input?.typeText(message.optString("text"))
            "key" -> input?.pressKey(message.optString("key"))
            "closing", "sleeping" -> running = false
        }
    }
}

/** Serializes control JSON and video frames onto the one TCP connection. */
class SenderSink(private val output: DataOutputStream) {
    private val lock = Any()

    fun json(message: JSONObject) = send(Messages.bytes(message))

    fun video(payload: ByteArray) = send(payload)

    private fun send(payload: ByteArray) {
        val framed = Framing.encodeVideo(payload)
        synchronized(lock) {
            output.write(framed)
            output.flush()
        }
    }
}
