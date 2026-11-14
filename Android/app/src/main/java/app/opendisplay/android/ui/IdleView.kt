package app.opendisplay.android.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import app.opendisplay.android.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import kotlin.math.min

/**
 * The receiver's idle screen. A fixed blue brand palette replaces wallpaper
 * dynamic color, and a wide layout uses a two-column hero + actions split on
 * tablets instead of a narrow phone column.
 */
class IdleView(
    context: Context,
    private val kind: String,
    private val onSettings: () -> Unit,
    private val onSend: () -> Unit,
) : ScrollView(context) {
    private val dot = View(context)
    private val status = TextView(context)
    private val wide = resources.configuration.screenWidthDp >= 720

    private fun colorRes(res: Int) = ContextCompat.getColor(context, res)
    private fun themeColor(attr: Int) = MaterialColors.getColor(context, attr, Color.GRAY)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    init {
        isFillViewport = true
        clipToPadding = false
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(colorRes(R.color.od_hero_top), themeColor(com.google.android.material.R.attr.colorSurface))
        )

        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(28))
            minimumHeight = resources.displayMetrics.heightPixels - dp(40)
        }
        addView(outer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        outer.padToSafeArea()

        val contentWidth = min(dp(960),
            resources.displayMetrics.widthPixels - dp(48))
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        outer.addView(content, LinearLayout.LayoutParams(contentWidth, LayoutParams.WRAP_CONTENT))

        if (wide) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
            }
            content.addView(row, matchWidth())
            row.addView(hero(), LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                .apply { rightMargin = dp(24) })
            val side = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            side.addView(actionsCard(), matchWidth())
            side.addView(stepsCard(), matchWidth(top = 16))
            row.addView(side, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        } else {
            content.addView(hero(), matchWidth())
            content.addView(actionsCard(), matchWidth(top = 28))
            content.addView(stepsCard(), matchWidth(top = 16))
        }
        content.addView(footer(), matchWidth(top = 24))
    }

    private fun hero(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = if (wide) Gravity.START else Gravity.CENTER_HORIZONTAL

        val tile = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(30).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), colorRes(R.color.od_outline_variant))
            }
            elevation = dp(6).toFloat()
        }
        tile.addView(ImageView(context).apply {
            setImageResource(R.drawable.app_logo)
        }, FrameLayout.LayoutParams(dp(82), dp(82), Gravity.CENTER))
        addView(tile, LinearLayout.LayoutParams(dp(108), dp(108)))

        addView(TextView(context).apply {
            text = context.getString(R.string.title)
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            letterSpacing = -0.02f
        }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(20) })

        addView(TextView(context).apply {
            text = context.getString(R.string.ui_tagline)
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = if (wide) Gravity.START else Gravity.CENTER
        }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(6) })

        val chip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(16), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(100).toFloat()
                setColor(colorRes(R.color.od_accent_soft))
            }
        }
        chip.addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(8) })
        chip.addView(status.apply {
            setTextColor(colorRes(R.color.od_accent_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        addView(chip, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(18) })
        setStatus(context.getString(R.string.status_ready), connected = false)
    }

    private fun actionsCard(): MaterialCardView = card {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(22))
        }
        column.addView(TextView(context).apply {
            text = context.getString(R.string.ui_get_started)
            setTextColor(colorRes(R.color.od_accent_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.08f
        })
        column.addView(TextView(context).apply {
            text = context.getString(R.string.ui_sender_note)
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(0f, 1.15f)
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(6); bottomMargin = dp(18) })

        column.addView(filledButton(R.string.sender_open, R.drawable.ic_cast) { onSend() },
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(56)))
        column.addView(outlinedButton(R.string.settings_and_help, R.drawable.ic_settings) { onSettings() },
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(52))
                .apply { topMargin = dp(12) })
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun stepsCard(): MaterialCardView = card {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(20))
        }
        column.addView(TextView(context).apply {
            text = context.getString(R.string.ui_steps_title)
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        column.addView(TextView(context).apply {
            text = context.getString(R.string.ui_steps_subtitle)
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(2); bottomMargin = dp(16) })

        column.addView(step(1, context.getString(R.string.hint_open_mac)))
        column.addView(step(2, context.getString(R.string.hint_pick_wifi, kind)),
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(14) })
        column.addView(step(3, context.getString(R.string.hint_keep_open)),
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(14) })
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun footer() = TextView(context).apply {
        text = context.getString(R.string.shake_tip, kind)
        setTextColor(themeColor(com.google.android.material.R.attr.colorOutline))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        gravity = Gravity.CENTER
    }

    fun setStatus(text: String, connected: Boolean) {
        status.text = text
        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(colorRes(if (connected) R.color.od_success else R.color.od_warning))
        }
    }

    private fun card(content: MaterialCardView.() -> Unit) =
        MaterialCardView(ContextThemeWrapper(context,
            com.google.android.material.R.style.Widget_Material3_CardView_Filled)).apply {
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = colorRes(R.color.od_outline_variant)
            setCardBackgroundColor(colorRes(R.color.od_container_low))
            radius = dp(28).toFloat()
            content()
        }

    private fun step(number: Int, text: String) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.TOP
        addView(TextView(context).apply {
            this.text = number.toString()
            gravity = Gravity.CENTER
            setTextColor(colorRes(R.color.od_accent_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colorRes(R.color.od_accent_soft))
            }
        }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { rightMargin = dp(12) })
        addView(TextView(context).apply {
            this.text = text
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(dp(2).toFloat(), 1.1f)
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun filledButton(text: Int, icon: Int, click: () -> Unit) =
        MaterialButton(ContextThemeWrapper(context,
            com.google.android.material.R.style.Widget_Material3_Button)).apply {
            this.text = context.getString(text)
            setIconResource(icon)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconPadding = dp(10)
            iconSize = dp(20)
            iconTint = android.content.res.ColorStateList.valueOf(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(colorRes(R.color.od_accent))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            cornerRadius = dp(18)
            insetTop = 0
            insetBottom = 0
            isAllCaps = false
            setOnClickListener { click() }
        }

    private fun outlinedButton(text: Int, icon: Int, click: () -> Unit) =
        MaterialButton(ContextThemeWrapper(context,
            com.google.android.material.R.style.Widget_Material3_Button_OutlinedButton)).apply {
            this.text = context.getString(text)
            setIconResource(icon)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconPadding = dp(10)
            iconSize = dp(20)
            iconTint = android.content.res.ColorStateList.valueOf(
                themeColor(com.google.android.material.R.attr.colorOnSurface))
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
            strokeWidth = dp(1)
            strokeColor = android.content.res.ColorStateList.valueOf(colorRes(R.color.od_outline_variant))
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            cornerRadius = dp(18)
            insetTop = 0
            insetBottom = 0
            isAllCaps = false
            setOnClickListener { click() }
        }

    private fun matchWidth(top: Int = 0) = LinearLayout.LayoutParams(
        LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }
}
