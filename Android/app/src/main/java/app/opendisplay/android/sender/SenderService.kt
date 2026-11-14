package app.opendisplay.android.sender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.IntentCompat
import app.opendisplay.android.Log
import app.opendisplay.android.R

class SenderService : Service() {
    companion object {
        const val ACTION_START = "app.opendisplay.android.START_CAST"
        const val ACTION_STOP = "app.opendisplay.android.STOP_CAST"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val NOTIFICATION_ID = 42
        private const val CHANNEL = "open_display_cast"

        @Volatile
        var status = ""

        @Volatile
        var casting = false
    }

    private var session: SenderSession? = null
    private var projection: MediaProjection? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCasting()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) return START_NOT_STICKY

        startForegroundNow()
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
        if (code == 0 || data == null) {
            status = getString(R.string.sender_error, "screen capture permission missing")
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = manager.getMediaProjection(code, data)
        if (projection == null) {
            status = getString(R.string.sender_error, "projection unavailable")
            stopSelf()
            return START_NOT_STICKY
        }
        this.projection = projection
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopCasting()
            }
        }, Handler(Looper.getMainLooper()))

        val host = intent.getStringExtra(EXTRA_HOST).orEmpty()
        val port = intent.getIntExtra(EXTRA_PORT, 9000)
        status = getString(R.string.sender_starting)
        session?.stop()
        lateinit var created: SenderSession
        created = SenderSession(this, projection, host, port) {
            main.post {
                if (session === created) {
                    session = null
                    stopCasting(keepStatus = true)
                }
            }
        }
        session = created
        casting = true
        created.start()
        Log.i("od-sender", "cast started to $host:$port")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        session?.stop()
        session = null
        projection = null
        casting = false
        super.onDestroy()
    }

    private fun stopCasting(keepStatus: Boolean = false) {
        val s = session
        session = null
        s?.stop()
        projection = null
        casting = false
        if (!keepStatus) status = getString(R.string.sender_stopped)
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun startForegroundNow() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL, getString(R.string.sender_title), NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, SenderActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle(getString(R.string.sender_title))
            .setContentText(getString(R.string.sender_active))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

}
