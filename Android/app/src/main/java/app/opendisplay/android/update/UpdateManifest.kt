package app.opendisplay.android.update

import org.json.JSONObject
import java.io.IOException
import java.util.Locale

/** Parsed update metadata shared by the network client and unit tests. */
data class UpdateRelease(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val notes: String?,
)

object UpdateManifest {
    fun parse(body: String): UpdateRelease {
        val obj = try {
            JSONObject(body)
        } catch (error: Exception) {
            throw IOException("invalid update manifest", error)
        }

        val code = obj.optLong("versionCode", -1L)
        val name = obj.optString("versionName").trim()
        val apkUrl = obj.optString("apkUrl").trim()
        val sha = obj.optString("sha256").trim().lowercase(Locale.US)
        if (code <= 0L || name.isEmpty() || !apkUrl.startsWith("https://") ||
            !sha.matches(Regex("^[0-9a-f]{64}$"))) {
            throw IOException("invalid update manifest")
        }
        return UpdateRelease(
            versionCode = code,
            versionName = name,
            apkUrl = apkUrl,
            sha256 = sha,
            notes = obj.optString("notes").trim().takeIf { it.isNotEmpty() },
        )
    }
}
