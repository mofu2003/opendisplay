package app.opendisplay.android.sender

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.opendisplay.android.R
import app.opendisplay.android.input.InputAccessibilityService
import app.opendisplay.android.ui.DeviceIdentity
import app.opendisplay.android.ui.padToSafeArea
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlin.math.min

/** Android as the source: pick a Mac receiver and cast the screen to it. */
class SenderActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private lateinit var hostField: TextInputEditText
    private lateinit var portField: TextInputEditText
    private lateinit var status: TextView
    private lateinit var statusDot: View
    private lateinit var startButton: MaterialButton
    private lateinit var stopButton: MaterialButton
    private lateinit var inputCard: MaterialCardView
    private lateinit var inputBody: TextView
    private lateinit var inputButton: MaterialButton
    private var discovery: NsdManager.DiscoveryListener? = null
    private var multicast: WifiManager.MulticastLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val installId by lazy { DeviceIdentity.installId(this) }
    private val ownServiceName by lazy { DeviceIdentity.serviceName(this) }

    private val poll = object : Runnable {
        override fun run() {
            status.text = SenderService.status.ifEmpty { getString(R.string.sender_stopped) }
            val casting = SenderService.casting
            if (casting) {
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            statusDot.background = circle(
                color(if (casting) R.color.od_success else R.color.od_warning))
            startButton.isEnabled = !casting
            startButton.alpha = if (casting) 0.5f else 1f
            stopButton.visibility = if (casting) View.VISIBLE else View.GONE

            val inputOn = InputAccessibilityService.isEnabled(this@SenderActivity)
            inputBody.text = getString(
                if (inputOn) R.string.sender_input_enabled else R.string.sender_no_accessibility)
            inputButton.visibility = if (inputOn) View.GONE else View.VISIBLE
            inputCard.strokeColor = color(if (inputOn) R.color.od_success else R.color.od_warning)
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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        root = buildUi()
        setContentView(root)
        root.padToSafeArea()
        val night = resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
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
        multicast = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
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

    private fun buildUi(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(color(R.color.od_hero_top), themeColor(
                    com.google.android.material.R.attr.colorSurface)))
        }
        root.addView(toolbar(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(8), dp(24), dp(28))
        }
        scroll.addView(center, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val width = min(dp(720), resources.displayMetrics.widthPixels - dp(48))
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        center.addView(content, LinearLayout.LayoutParams(
            width, ViewGroup.LayoutParams.WRAP_CONTENT))

        content.addView(statusCard(), matchWidth())
        content.addView(connectCard(), matchWidth(top = 16))

        startButton = filledButton(R.string.sender_start, R.drawable.ic_cast) { startClicked() }
        content.addView(startButton, matchWidth(top = 20).apply { height = dp(56) })

        stopButton = outlinedButton(R.string.sender_stop, R.drawable.ic_stop) {
            startService(Intent(this@SenderActivity, SenderService::class.java)
                .setAction(SenderService.ACTION_STOP))
        }
        stopButton.visibility = View.GONE
        content.addView(stopButton, matchWidth(top = 10).apply { height = dp(52) })

        inputCard = inputControlCard()
        content.addView(inputCard, matchWidth(top = 16))
        content.addView(TextView(this).apply {
            text = getString(R.string.sender_scan_hint)
            setTextColor(themeColor(com.google.android.material.R.attr.colorOutline))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
        }, matchWidth(top = 18))
        return root
    }

    private fun toolbar() = MaterialToolbar(this).apply {
        title = getString(R.string.sender_title)
        subtitle = getString(R.string.sender_subtitle)
        setTitleTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
        setSubtitleTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        navigationIcon = ContextCompat.getDrawable(this@SenderActivity, R.drawable.ic_arrow_back)
        setNavigationIconTint(themeColor(com.google.android.material.R.attr.colorOnSurface))
        setNavigationContentDescription(androidx.appcompat.R.string.abc_action_bar_up_description)
        setNavigationOnClickListener { finish() }
        setPadding(dp(8), dp(8), dp(8), dp(4))
    }

    private fun statusCard(): MaterialCardView = card(backgroundRes = R.color.od_accent_soft) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        statusDot = View(context)
        row.addView(statusDot, LinearLayout.LayoutParams(dp(9), dp(9)).apply { rightMargin = dp(10) })
        status = TextView(context).apply {
            text = getString(R.string.sender_stopped)
            setTextColor(color(R.color.od_accent_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        row.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(row, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun connectCard(): MaterialCardView = card {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(20))
        }
        column.addView(sectionHeader(
            R.drawable.ic_laptop, getString(R.string.sender_connect_title)))
        column.addView(TextView(context).apply {
            text = getString(R.string.sender_hint)
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(dp(2).toFloat(), 1.1f)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8); bottomMargin = dp(18) })

        val prefs = getSharedPreferences("sender", MODE_PRIVATE)
        val (hostLayout, hostEdit) = editText(
            prefs.getString("host", "").orEmpty(),
            R.string.sender_host,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            R.drawable.ic_laptop)
        hostField = hostEdit
        val (portLayout, portEdit) = editText(
            prefs.getInt("port", 9000).toString(),
            R.string.sender_port,
            InputType.TYPE_CLASS_NUMBER,
            null)
        portField = portEdit

        val wide = resources.configuration.screenWidthDp >= 600
        val fields = LinearLayout(context).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        fields.addView(hostLayout, if (wide)
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        else
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        fields.addView(portLayout, if (wide)
            LinearLayout.LayoutParams(dp(120), ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { leftMargin = dp(12) }
        else
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(12) })
        column.addView(fields, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        column.addView(outlinedButton(R.string.sender_search, R.drawable.ic_search) { searchMac() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(14) })
        addView(column, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun editText(
        value: String,
        hintRes: Int,
        inputType: Int,
        startIcon: Int?,
    ): Pair<TextInputLayout, TextInputEditText> {
        val layout = TextInputLayout(ContextThemeWrapper(this,
            com.google.android.material.R.style.Widget_Material3_TextInputLayout_OutlinedBox)).apply {
            hint = getString(hintRes)
            setBoxCornerRadii(dp(16).toFloat(), dp(16).toFloat(),
                dp(16).toFloat(), dp(16).toFloat())
            if (startIcon != null) {
                setStartIconDrawable(startIcon)
                setStartIconTintList(ColorStateList.valueOf(color(R.color.od_accent_text)))
            }
        }
        val field = TextInputEditText(layout.context).apply {
            this.inputType = inputType
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
            setText(value)
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
        }
        layout.addView(field, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return layout to field
    }

    private fun inputControlCard(): MaterialCardView = card {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(16))
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(iconTile(R.drawable.ic_touch), LinearLayout.LayoutParams(dp(42), dp(42))
            .apply { rightMargin = dp(14) })
        val textColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        textColumn.addView(TextView(context).apply {
            text = getString(R.string.sender_input_title)
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        inputBody = TextView(context).apply {
            text = getString(R.string.sender_no_accessibility)
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(dp(2).toFloat(), 1.1f)
        }
        textColumn.addView(inputBody, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(2) })
        row.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        column.addView(row)

        inputButton = MaterialButton(ContextThemeWrapper(context,
            com.google.android.material.R.style.Widget_Material3_Button_TextButton)).apply {
            text = getString(R.string.sender_accessibility)
            setTextColor(color(R.color.od_accent_text))
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            iconTint = ColorStateList.valueOf(color(R.color.od_accent_text))
            setIconResource(R.drawable.ic_touch)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconPadding = dp(8)
            iconSize = dp(18)
            insetTop = 0
            insetBottom = 0
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        column.addView(inputButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { topMargin = dp(6) })
        addView(column, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun sectionHeader(icon: Int, title: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(plainIcon(icon), LinearLayout.LayoutParams(dp(22), dp(22))
            .apply { rightMargin = dp(10) })
        addView(TextView(context).apply {
            text = title
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
    }

    private fun iconTile(icon: Int) = android.widget.ImageView(this).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(color(R.color.od_accent_text))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(color(R.color.od_accent_soft))
        }
        setPadding(dp(10), dp(10), dp(10), dp(10))
    }

    private fun plainIcon(icon: Int) = android.widget.ImageView(this).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(color(R.color.od_accent_text))
    }

    private fun startClicked() {
        if (hostField.text.isNullOrBlank()) {
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

    private fun card(
        backgroundRes: Int = R.color.od_container_low,
        content: MaterialCardView.() -> Unit,
    ) = MaterialCardView(ContextThemeWrapper(this,
        com.google.android.material.R.style.Widget_Material3_CardView_Filled)).apply {
        cardElevation = 0f
        strokeWidth = dp(1)
        strokeColor = color(R.color.od_outline_variant)
        setCardBackgroundColor(color(backgroundRes))
        radius = dp(24).toFloat()
        content()
    }

    private fun filledButton(text: Int, icon: Int, click: () -> Unit) =
        MaterialButton(ContextThemeWrapper(this,
            com.google.android.material.R.style.Widget_Material3_Button)).apply {
            this.text = getString(text)
            setIconResource(icon)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconPadding = dp(10)
            iconSize = dp(20)
            iconTint = ColorStateList.valueOf(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(color(R.color.od_accent))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            cornerRadius = dp(18)
            insetTop = 0
            insetBottom = 0
            isAllCaps = false
            setOnClickListener { click() }
        }

    private fun outlinedButton(text: Int, icon: Int, click: () -> Unit) =
        MaterialButton(ContextThemeWrapper(this,
            com.google.android.material.R.style.Widget_Material3_Button_OutlinedButton)).apply {
            this.text = getString(text)
            setIconResource(icon)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconPadding = dp(10)
            iconSize = dp(20)
            iconTint = ColorStateList.valueOf(
                themeColor(com.google.android.material.R.attr.colorOnSurface))
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            strokeWidth = dp(1)
            strokeColor = ColorStateList.valueOf(color(R.color.od_outline_variant))
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            cornerRadius = dp(18)
            insetTop = 0
            insetBottom = 0
            isAllCaps = false
            setOnClickListener { click() }
        }

    private fun circle(fill: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
    }

    private fun color(res: Int) = ContextCompat.getColor(this, res)
    private fun themeColor(attr: Int) = MaterialColors.getColor(this, attr, Color.GRAY)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun matchWidth(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(top) }
}
