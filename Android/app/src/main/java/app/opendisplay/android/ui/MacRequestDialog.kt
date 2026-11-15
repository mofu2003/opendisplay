package app.opendisplay.android.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import app.opendisplay.android.R
import app.opendisplay.android.net.MacRequestBrowser
import app.opendisplay.android.net.MacRequestClient
import app.opendisplay.android.net.MacRequestTarget
import app.opendisplay.android.protocol.ReverseConnection
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Discover Mac senders and ask the selected one to start streaming here. */
class MacRequestDialog(
    private val activity: AppCompatActivity,
    private val installId: String,
    private val receiverName: String,
    private val deviceKind: String,
    private val onAccepted: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var targetList: LinearLayout
    private var dialog: AlertDialog? = null
    private var targets: List<MacRequestTarget> = emptyList()
    private var busy = false
    private var searchFailed = false

    private val showNoResults = Runnable {
        if (!busy && !searchFailed && targets.isEmpty()) {
            status.text = activity.getString(R.string.request_mac_none)
        }
    }

    private val browser = MacRequestBrowser(activity, object : MacRequestBrowser.Listener {
        override fun onTargetsChanged(found: List<MacRequestTarget>) {
            if (dialog?.isShowing != true) return
            targets = found
            if (found.isEmpty()) {
                if (!busy && !searchFailed) {
                    status.setText(R.string.request_mac_searching)
                    main.removeCallbacks(showNoResults)
                    main.postDelayed(showNoResults, 2_500)
                }
            } else {
                searchFailed = false
                main.removeCallbacks(showNoResults)
                if (!busy) status.setText(R.string.request_mac_choose)
            }
            renderTargets()
        }

        override fun onSearchFailed() {
            searchFailed = true
            main.removeCallbacks(showNoResults)
            if (!busy && dialog?.isShowing == true) {
                status.setText(R.string.request_mac_search_failed)
            }
        }
    })

    fun show() {
        if (dialog?.isShowing == true) return
        targets = emptyList()
        busy = false
        searchFailed = false
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), 0)
        }
        status = TextView(activity).apply {
            setText(R.string.request_mac_searching)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(color(R.color.od_accent_text))
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        targetList = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        container.addView(status, matchWidth())
        container.addView(targetList, matchWidth())
        dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.request_mac_title)
            .setView(container)
            .setNegativeButton(R.string.close, null)
            .setOnDismissListener {
                main.removeCallbacksAndMessages(null)
                browser.stop()
                dialog = null
                busy = false
                searchFailed = false
                targets = emptyList()
            }
            .show()
        renderTargets()
        browser.start()
        main.postDelayed(showNoResults, 2_500)
    }

    fun dismiss() {
        dialog?.dismiss()
    }

    private fun renderTargets() {
        if (!::targetList.isInitialized) return
        targetList.removeAllViews()
        for (target in targets) {
            targetList.addView(MaterialButton(
                ContextThemeWrapper(activity,
                    com.google.android.material.R.style.Widget_Material3_Button_OutlinedButton)
            ).apply {
                text = "${target.name}\n${target.host}:${target.port}"
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setTextColor(color(R.color.od_accent_text))
                strokeColor = ColorStateList.valueOf(color(R.color.od_outline_variant))
                strokeWidth = dp(1)
                cornerRadius = dp(16)
                setPadding(dp(16), dp(8), dp(16), dp(8))
                isEnabled = !busy
                setOnClickListener { request(target) }
            }, matchWidth(top = 6).apply { height = dp(64) })
        }
    }

    private fun request(target: MacRequestTarget, attempt: Int = 0) {
        if (busy && attempt == 0) return
        busy = true
        status.text = activity.getString(R.string.request_mac_requesting, target.name)
        renderTargets()
        MacRequestClient.request(
            target,
            ReverseConnection.request(installId, receiverName, deviceKind),
        ) { result ->
            main.post {
                if (dialog?.isShowing != true) return@post
                when {
                    result.isSuccess && result.getOrNull() == ReverseConnection.Reply.Accepted -> {
                        status.text = activity.getString(R.string.request_mac_waiting, target.name)
                        onAccepted(target.name)
                        main.postDelayed({ dismiss() }, 450)
                    }
                    result.isSuccess -> {
                        val reason = (result.getOrNull() as? ReverseConnection.Reply.Rejected)?.reason
                        if (reason == "receiverNotVisible" && attempt < 2) {
                            status.text = activity.getString(
                                R.string.request_mac_retrying, target.name)
                            main.postDelayed({
                                if (dialog?.isShowing == true) request(target, attempt + 1)
                            }, 1_000)
                        } else {
                            status.text = rejectionText(reason)
                            busy = false
                            renderTargets()
                        }
                    }
                    else -> {
                        status.text = activity.getString(
                            R.string.request_mac_failed,
                            result.exceptionOrNull()?.localizedMessage ?: "")
                        busy = false
                        renderTargets()
                    }
                }
            }
        }
    }

    private fun rejectionText(reason: String?): String = activity.getString(
        when (reason) {
            "unknownDevice" -> R.string.request_mac_unknown
            "disabled" -> R.string.request_mac_disabled
            "receiverNotVisible" -> R.string.request_mac_not_visible
            else -> R.string.request_mac_unavailable
        })

    private fun matchWidth(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(top) }

    private fun color(res: Int) = ContextCompat.getColor(activity, res)

    private fun dp(value: Int) =
        (value * activity.resources.displayMetrics.density).toInt()
}
