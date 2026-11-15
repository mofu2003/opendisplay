package app.opendisplay.android.protocol

import org.json.JSONObject

/** Receiver-initiated bootstrap; video still uses the normal protocol. */
object ReverseConnection {
    const val SERVICE_TYPE = "_od-sender._tcp"
    const val VERSION = 1

    sealed class Reply {
        object Accepted : Reply()
        data class Rejected(val reason: String?) : Reply()
    }

    fun request(
        installId: String,
        deviceName: String,
        deviceKind: String,
    ): JSONObject = JSONObject().apply {
        put("type", "connectRequest")
        put("pv", VERSION)
        put("id", installId)
        put("name", deviceName)
        put("device", deviceKind)
    }

    fun parseReply(payload: ByteArray): Reply? {
        val json = try {
            JSONObject(String(payload, Charsets.UTF_8))
        } catch (_: Exception) {
            return null
        }
        if (json.optInt("pv", VERSION) !in 1..VERSION) return null
        return when (json.optString("type")) {
            "accepted" -> Reply.Accepted
            "rejected" -> Reply.Rejected(
                json.optString("reason").takeIf { it.isNotEmpty() })
            else -> null
        }
    }
}
