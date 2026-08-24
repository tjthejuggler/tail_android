package com.example.tail.widget

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/**
 * A floating dialog rendered by [FloatingBubbleService] directly through the
 * [WindowManager] (TYPE_APPLICATION_OVERLAY) — NOT via an Activity.
 *
 * Because no activity is started, the app underneath (e.g. the chess app)
 * stays fully resumed, visible and "dominant"; the dialog simply draws on
 * top of it. While the dialog is open its window takes input focus (so
 * sliders/fields work); closing the window returns focus to the underlying
 * app automatically.
 *
 * All WindowManager operations are exception-guarded: a failure to
 * add/remove the window can never crash the hosting bubble service.
 */
class ChessOverlayDialog(private val context: Context) {

    // ── Palette (matches the previous Compose dialog look) ─────────────────
    private object C {
        val CARD = 0xEE161616.toInt()
        val STROKE = 0xFF334455.toInt()
        val TITLE = 0xFFBB88FF.toInt()
        val BODY = 0xFFDDDDDD.toInt()
        val MUTED = 0xFF999999.toInt()
        val FAINT = 0xFF777777.toInt()
        val ACCENT = 0xFF66CCFF.toInt()
        val BTN_BG = 0xFF3A2A5A.toInt()
        val BTN_TEXT = 0xFFDDBBFF.toInt()
        val BTN_DANGER_BG = 0xFF5A1A2A.toInt()
        val BTN_DANGER_TEXT = 0xFFFFAAAA.toInt()
        val CHIP_BG = 0xFF1E1E1E.toInt()
        val CHIP_BG_SEL = 0xFF2A2A3A.toInt()
        val GREEN = 0xFF66BB6A.toInt()
        val RED = 0xFFEF4444.toInt()
    }

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var root: FrameLayout? = null
    private var cardColumn: LinearLayout? = null

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).toInt()

    fun isShowing(): Boolean = root != null

    /** Adds the overlay window to the screen (content is set via [setContent]). */
    fun show() {
        if (root != null) return

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(20))
            background = GradientDrawable().apply {
                setColor(C.CARD)
                cornerRadius = 16f * density
                setStroke(dp(1), C.STROKE)
            }
        }
        cardColumn = column

        val centerFrame = FrameLayout(context).apply {
            addView(
                column,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }

        val scroll = ScrollView(context).apply {
            isFillViewport = false
            addView(
                centerFrame,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val container = FrameLayout(context).apply {
            // Swallow taps on the dimmed area around the card (modal dialog)
            isClickable = true
            isFocusable = true
            addView(
                scroll,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                ).apply {
                    leftMargin = dp(20); rightMargin = dp(20)
                    topMargin = dp(28); bottomMargin = dp(28)
                }
            )
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_DIM_BEHIND or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            dimAmount = 0.6f
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }

        try {
            windowManager.addView(container, params)
            root = container
        } catch (e: Exception) {
            root = null
            cardColumn = null
        }
    }

    /** Replaces the card's content (used to move between wizard steps). */
    fun setContent(title: String, subtitle: String? = null, build: OverlayScope.() -> Unit) {
        val column = cardColumn ?: return
        column.removeAllViews()
        column.addView(titleView(title))
        if (subtitle != null) column.addView(subtitleView(subtitle))
        OverlayScope(column).build()
    }

    /** Removes the overlay window. Safe to call repeatedly. */
    fun dismiss() {
        root?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) { /* already removed */ }
        }
        root = null
        cardColumn = null
    }

    // ──────────────────────────────────────────────────────────────────────
    //  View-builder scope handed to step content lambdas
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Builder DSL for dialog content. Every helper appends a styled view to
     * the card column — deliberately terse so the step screens read like a
     * script of exactly what the user must do and report back.
     */
    inner class OverlayScope(private val column: LinearLayout) {

        fun body(text: String, color: Int = C.BODY, size: Int = 13, bold: Boolean = false) {
            column.addView(TextView(context).apply {
                this.text = text
                setTextColor(color)
                textSize = size.toFloat()
                setTypeface(null, if (bold) Typeface.BOLD else Typeface.NORMAL)
            })
        }

        fun hint(text: String) {
            column.addView(TextView(context).apply {
                this.text = text
                setTextColor(C.MUTED)
                textSize = 11f
            })
        }

        fun spacer(h: Int = 12) {
            column.addView(View(context), LinearLayout.LayoutParams(1, dp(h)))
        }

        fun bigScore(text: String, colorHex: String) {
            column.addView(TextView(context).apply {
                this.text = text
                setTextColor(android.graphics.Color.parseColor(colorHex))
                textSize = 52f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
            })
        }

        fun stateLabel(text: String, colorHex: String) {
            column.addView(TextView(context).apply {
                this.text = text
                setTextColor(android.graphics.Color.parseColor(colorHex))
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
            })
        }

        fun bullet(text: String, color: Int) {
            column.addView(TextView(context).apply {
                this.text = text
                setTextColor(color)
                textSize = 12f
            })
        }

        fun keyValue(label: String, value: String) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(context).apply {
                text = label
                setTextColor(0xFFAAAAAA.toInt()); textSize = 12f
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(context).apply {
                text = value
                setTextColor(C.BODY); textSize = 12f
                setTypeface(null, Typeface.BOLD)
            })
            column.addView(row)
        }

        fun primaryButton(
            label: String,
            enabled: Boolean = true,
            danger: Boolean = false,
            onClick: () -> Unit
        ): TextView {
            val btn = TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(
                    when {
                        !enabled -> C.FAINT
                        danger -> C.BTN_DANGER_TEXT
                        else -> C.BTN_TEXT
                    }
                )
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = GradientDrawable().apply {
                    setColor(if (danger) C.BTN_DANGER_BG else C.BTN_BG)
                    cornerRadius = 10f * density
                    setStroke(dp(1), C.STROKE)
                }
                isEnabled = enabled
                setOnClickListener { if (enabled) onClick() }
            }
            column.addView(
                btn,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(14) }
            )
            return btn
        }

        fun textButton(label: String, onClick: () -> Unit) {
            val btn = TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 12f
                setTextColor(C.FAINT)
                setPadding(dp(8), dp(10), dp(8), dp(4))
                setOnClickListener { onClick() }
            }
            column.addView(
                btn,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
            )
        }

        /**
         * A row of single-select chips (e.g. Solved/Failed, 0–3 strikes).
         * [selected] is the currently chosen index, or -1 for none.
         * Tapping a chip restyles the whole row immediately so the choice is
         * clearly visible without the caller having to re-render its step.
         */
        fun chipRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val chips = ArrayList<TextView>(options.size)

            fun applySelection(sel: Int) {
                chips.forEachIndexed { i, chip ->
                    val isSel = i == sel
                    chip.setTextColor(if (isSel) C.ACCENT else 0xFFAAAAAA.toInt())
                    chip.setTypeface(null, if (isSel) Typeface.BOLD else Typeface.NORMAL)
                    (chip.background as? GradientDrawable)?.apply {
                        setColor(if (isSel) C.CHIP_BG_SEL else C.CHIP_BG)
                        setStroke(dp(1), if (isSel) C.ACCENT else C.CHIP_BG)
                    }
                    chip.invalidate()
                }
            }

            options.forEachIndexed { i, option ->
                val chip = TextView(context).apply {
                    text = option
                    gravity = Gravity.CENTER
                    textSize = 13f
                    setTextColor(if (i == selected) C.ACCENT else 0xFFAAAAAA.toInt())
                    setTypeface(null, if (i == selected) Typeface.BOLD else Typeface.NORMAL)
                    setPadding(dp(6), dp(10), dp(6), dp(10))
                    background = GradientDrawable().apply {
                        setColor(if (i == selected) C.CHIP_BG_SEL else C.CHIP_BG)
                        cornerRadius = 10f * density
                        setStroke(dp(1), if (i == selected) C.ACCENT else C.CHIP_BG)
                    }
                    setOnClickListener {
                        applySelection(i)
                        onSelect(i)
                    }
                }
                chips.add(chip)
                row.addView(
                    chip,
                    LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    ).apply {
                        if (i > 0) leftMargin = dp(6)
                        if (i < options.lastIndex) rightMargin = dp(6)
                    }
                )
            }
            column.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            )
        }

        /**
         * A discrete slider over 1..[maxValue] (default 5-point). Every
         * point is always reachable — the SeekBar snaps to integers over
         * max = [maxValue] − 1.
         */
        fun slider(
            label: String,
            lowAnchor: String,
            highAnchor: String,
            initial: Int,
            maxValue: Int = 5,
            onChange: (Int) -> Unit
        ) {
            val wrap = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

            val valueText = TextView(context).apply {
                text = "$initial / $maxValue"
                setTextColor(C.ACCENT); textSize = 13f
                setTypeface(null, Typeface.BOLD)
            }
            val labelRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            labelRow.addView(TextView(context).apply {
                text = label
                setTextColor(0xFFCCCCCC.toInt()); textSize = 13f
                setTypeface(null, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            labelRow.addView(valueText)
            wrap.addView(labelRow)

            val seek = SeekBar(context).apply {
                max = maxValue - 1
                progress = (initial - 1).coerceIn(0, maxValue - 1)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        bar: SeekBar?, value: Int, fromUser: Boolean
                    ) {
                        val v = value + 1
                        valueText.text = "$v / $maxValue"
                        onChange(v)
                    }

                    override fun onStartTrackingTouch(bar: SeekBar?) {}
                    override fun onStopTrackingTouch(bar: SeekBar?) {}
                })
            }
            wrap.addView(
                seek,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )

            val anchors = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            anchors.addView(TextView(context).apply {
                text = lowAnchor; setTextColor(C.FAINT); textSize = 10f
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            anchors.addView(TextView(context).apply {
                text = highAnchor; setTextColor(C.FAINT); textSize = 10f
                gravity = Gravity.END
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            wrap.addView(anchors)

            column.addView(
                wrap,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            )
        }

        /** A labeled numeric input field; returns the EditText for reading. */
        fun numberField(
            label: String,
            initial: String,
            maxLength: Int,
            decimal: Boolean = false
        ): EditText {
            if (label.isNotBlank()) {
                column.addView(TextView(context).apply {
                    text = label
                    setTextColor(0xFFCCCCCC.toInt()); textSize = 13f
                    setTypeface(null, Typeface.BOLD)
                })
            }
            val field = EditText(context).apply {
                setText(initial)
                setTextColor(C.BODY)
                setHintTextColor(C.FAINT)
                setSingleLine()
                inputType = if (decimal)
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                else InputType.TYPE_CLASS_NUMBER
                filters = arrayOf(InputFilter.LengthFilter(maxLength))
                background?.setTint(0xFF666688.toInt())
            }
            column.addView(
                field,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
            )
            return field
        }

        /**
         * Appends an arbitrary view to the card column (used by the v2
         * readiness wizard for the PVT-B surface).
         * Purely additive — all v1 wizard screens use the helpers above.
         */
        fun customView(view: android.view.View, heightDp: Int) {
            column.addView(
                view,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (heightDp * density).toInt()
                ).apply { topMargin = dp(8) }
            )
        }

        fun checkRow(label: String, checked: Boolean, onCheck: (Boolean) -> Unit) {
            val checkBox = CheckBox(context).apply {
                isChecked = checked
                setOnClickListener { onCheck(isChecked) }
            }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener { onCheck(!checkBox.isChecked) }
            }
            row.addView(checkBox)
            row.addView(TextView(context).apply {
                text = label
                setTextColor(0xFFCCCCCC.toInt()); textSize = 12f
            })
            column.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            )
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Card header helpers
    // ──────────────────────────────────────────────────────────────────────

    private fun titleView(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(C.TITLE)
        textSize = 18f
        setTypeface(null, Typeface.BOLD)
    }

    private fun subtitleView(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(C.MUTED)
        textSize = 12f
    }
}
