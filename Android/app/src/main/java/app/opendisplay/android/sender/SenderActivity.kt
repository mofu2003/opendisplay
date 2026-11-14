package app.opendisplay.android.sender

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import app.opendisplay.android.R
import app.opendisplay.android.input.InputAccessibilityService
import app.opendisplay.android.ui.DeviceIdentity

/** Android as the source: pick a Mac receiver and cast the screen to it. */
class SenderActivity : AppCompatActivity() {
    private lateinit var hostField: EditText
    private lateinit var portField: EditText
    private lateinit var status: TextView
    private lateinit var accessibilityHint: TextView
    private var discovery: NsdManager.DiscoveryListener? = null
    private var multicast: WifiManager.MulticastLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val installId by lazy { DeviceIdentity.installId(this) }
    private val ownServiceName by lazy { DeviceIdentity.serviceName(this) }

    private val poll = object : Runnable {
        override fun run() {
            if (SenderService.status.isNotEmpty()) status.text = SenderService.status
            accessibilityHint.visibility = if (InputAccessibilityService.isEnabled(this@SenderActivity))
                android.view.View.GONE else android.view.View.VISIBLE
            handler.postDelayed(this, 500)
        }
    }

    private val capture = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) {
            status.text = getString(R.string.sender_stopped)
            return@registerForActivityResult
        }
        startCasting(result.resultCode, data)
    }

    private val notifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()) {
        requestCapture()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        handler.post(poll)
    }

    override fun onPause() {
        handler.removeCallbacks(poll)
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        multicast = (getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createMulticastLock("open-display-discovery").also { it.acquire() }
    }

    override fun onStop() {
        multicast?.release()
        multicast = null
        (getSystemService(Context.NSD_SERVICE) as NsdManager).let { nsd ->
            discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        }
        discovery = null
        super.onStop()
    }

    private fun buildUi(): ScrollView {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(column, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        column.addView(TextView(this).apply {
            text = getString(R.string.sender_title)
            textSize = 24f
        })
        column.addView(TextView(this).apply {
            text = getString(R.string.sender_hint)
            textSize = 14f
            setPadding(0, dp(8), 0, dp(20))
        })
        column.addView(label(R.string.sender_host))
        hostField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "192.168.1.10"
            setText(getSharedPreferences("sender", MODE_PRIVATE)
                .getString("host", "").orEmpty())
        }
        column.addView(hostField, matchWidth())
        column.addView(label(R.string.sender_port))
        portField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(getSharedPreferences("sender", MODE_PRIVATE)
                .getInt("port", 9000).toString())
        }
        column.addView(portField, matchWidth())

        column.addView(Button(this).apply {
            text = getString(R.string.sender_search)
            setOnClickListener { searchMac() }
        }, matchWidth(top = 8))
        column.addView(Button(this).apply {
            text = getString(R.string.sender_accessibility)
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }, matchWidth(top = 8))
        column.addView(Button(this).apply {
            text = getString(R.string.sender_start)
            setOnClickListener { startClicked() }
        }, matchWidth(top = 20))
        column.addView(Button(this).apply {
            text = getString(R.string.sender_stop)
            setOnClickListener {
                startService(Intent(this@SenderActivity, SenderService::class.java)
                    .setAction(SenderService.ACTION_STOP))
            }
        }, matchWidth(top = 8))

        status = TextView(this).apply {
            text = getString(R.string.sender_stopped)
            textSize = 14f
            setPadding(0, dp(20), 0, 0)
        }
        column.addView(status, matchWidth())
        accessibilityHint = TextView(this).apply {
            text = getString(R.string.sender_no_accessibility)
            textSize = 13f
            setPadding(0, dp(8), 0, 0)
            visibility = android.view.View.GONE
        }
        column.addView(accessibilityHint, matchWidth())
        return scroll
    }

    private fun label(res: Int) = TextView(this).apply {
        text = getString(res)
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun matchWidth(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(top)
    }

    private fun startClicked() {
        if (hostField.text.isBlank()) {
            status.text = getString(R.string.sender_not_found)
            return
        }
        getSharedPreferences("sender", MODE_PRIVATE).edit()
            .putString("host", hostField.text.toString().trim())
            .putInt("port", portField.text.toString().toIntOrNull() ?: 9000)
            .apply()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestCapture()
        }
    }

    private fun requestCapture() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        capture.launch(manager.createScreenCaptureIntent())
    }

    private fun startCasting(resultCode: Int, data: Intent) {
        val host = hostField.text.toString().trim()
        val port = portField.text.toString().toIntOrNull() ?: 9000
        val intent = Intent(this, SenderService::class.java).apply {
            action = SenderService.ACTION_START
            putExtra(SenderService.EXTRA_RESULT_CODE, resultCode)
            putExtra(SenderService.EXTRA_RESULT_DATA, data)
            putExtra(SenderService.EXTRA_HOST, host)
            putExtra(SenderService.EXTRA_PORT, port)
        }
        ContextCompat.startForegroundService(this, intent)
        status.text = getString(R.string.sender_starting)
    }

    private fun searchMac() {
        val nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        status.text = getString(R.string.sender_searching)
        discovery = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                runOnUiThread { status.text = getString(R.string.sender_not_found) }
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) = Unit

            override fun onDiscoveryStarted(serviceType: String?) = Unit

            override fun onDiscoveryStopped(serviceType: String?) = Unit

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                val info = serviceInfo ?: return
                if (info.serviceName == ownServiceName) return
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) = Unit

                    override fun onServiceResolved(resolved: NsdServiceInfo?) {
                        val id = resolved?.attributes?.get("id")?.toString(Charsets.UTF_8)
                        if (id == installId) return
                        val address = resolved?.host?.hostAddress ?: return
                        runOnUiThread {
                            hostField.setText(address)
                            portField.setText(resolved.port.toString())
                            status.text = "${resolved.serviceName} · $address:${resolved.port}"
                        }
                    }
                })
            }
        }
        runCatching {
            nsd.discoverServices("_opensidecar._tcp", NsdManager.PROTOCOL_DNS_SD, discovery)
        }.onFailure {
            status.text = getString(R.string.sender_not_found)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
