package helium314.keyboard.latin.pinyin

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings

interface LookupListener {
    fun onClose()
    fun onPick(row: LookupRow)
    fun onRadical(radical: Int)
    fun onDraw()
    fun onLeavePad()
    fun onStroke(points: FloatArray)
    fun onUndo()
    fun onClear()
    fun onWiden()
    fun onScript()
}

/** Grid and handwriting pad. Covers the keys, not the suggestion strip. */
@SuppressLint("ViewConstructor")
class LookupPanel(context: Context) : LinearLayout(context) {
    var listener: LookupListener? = null
    private val chips = LinearLayout(context)
    private val grid = LinearLayout(context)
    private val scrollGrid: ScrollView
    private val ink = InkView(context)
    private val buttons = LinearLayout(context)
    private var model = LookupModel(emptyList(), emptyList(), 0, false, false, true, false, emptyList())

    init {
        tag = TAG
        orientation = VERTICAL
        isClickable = true
        // A focused child finishes the IME before the click can commit the character.
        isFocusable = false
        isFocusableInTouchMode = false
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        applyTheme()
        val scrollChips = HorizontalScrollView(context)
        scrollChips.isHorizontalScrollBarEnabled = false
        scrollChips.isFocusable = false
        scrollChips.isFocusableInTouchMode = false
        chips.orientation = HORIZONTAL
        scrollChips.addView(chips)
        addView(scrollChips, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        scrollGrid = ScrollView(context)
        scrollGrid.isFocusable = false
        scrollGrid.isFocusableInTouchMode = false
        grid.orientation = VERTICAL
        scrollGrid.addView(grid)
        addView(scrollGrid, LayoutParams(LayoutParams.MATCH_PARENT, 0, 2f))
        addView(ink, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        buttons.orientation = HORIZONTAL
        buttons.gravity = Gravity.CENTER_VERTICAL
        // ponytail: Samsung draws the IME switcher over the bottom-left of the IME and reports no inset. Reserve that corner. Upgrade: drop the fixed dp if the platform starts reporting the switcher.
        addView(buttons, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(SWITCHER_CLEARANCE_DP)
            marginStart = dp(SWITCHER_CLEARANCE_DP)
            marginEnd = dp(4)
        })
    }

    fun setModel(next: LookupModel) {
        model = next
        applyTheme()
        bindChips()
        bindGrid()
        ink.strokes = next.strokes
        ink.visibility = if (next.pad) VISIBLE else GONE
        bindButtons()
    }

    private fun bindChips() {
        chips.removeAllViews()
        for ((code, label) in model.radicals) {
            val view = text(label, 16f)
            view.setPadding(dp(12), dp(6), dp(12), dp(6))
            view.background = face(
                if (code == model.selectedRadical) ColorType.FUNCTIONAL_KEY_BACKGROUND
                else ColorType.KEY_BACKGROUND
            )
            view.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(3), dp(4), dp(3), dp(4))
            }
            view.setOnClickListener {
                listener?.onRadical(if (code == model.selectedRadical) 0 else code)
            }
            chips.addView(view)
        }
        chips.visibility = if (model.radicals.isEmpty()) GONE else VISIBLE
    }

    private fun bindGrid() {
        grid.removeAllViews()
        val columns = 8
        var row: LinearLayout? = null
        model.rows.forEachIndexed { index, item ->
            if (index % columns == 0) {
                row = LinearLayout(context)
                row!!.orientation = HORIZONTAL
                grid.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }
            val cell = text(item.text, 20f)
            cell.gravity = Gravity.CENTER
            cell.background = face(ColorType.KEY_BACKGROUND)
            cell.layoutParams = LayoutParams(0, dp(44), 1f).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            }
            cell.setOnClickListener { listener?.onPick(item) }
            row!!.addView(cell)
        }
        val remainder = model.rows.size % columns
        if (remainder != 0 && row != null) {
            repeat(columns - remainder) {
                row!!.addView(View(context), LayoutParams(0, dp(44), 1f))
            }
        }
    }

    private fun bindButtons() {
        buttons.removeAllViews()
        if (!model.pad) button("写", { listener?.onDraw() })
        else {
            button("⌫", { listener?.onUndo() })
            button("清", { listener?.onClear() })
            if (model.hasSpelling) button(if (model.widened) "全·开" else "全", { listener?.onWiden() })
            button(if (model.simplified) "简" else "繁", { listener?.onScript() })
            button("⌨", { listener?.onLeavePad() })
        }
        button("×", { listener?.onClose() })
    }

    private fun button(label: String, onClick: () -> Unit) {
        val view = text(label, 16f)
        view.setPadding(dp(14), dp(8), dp(14), dp(8))
        view.background = face(ColorType.FUNCTIONAL_KEY_BACKGROUND)
        view.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(6)
        }
        view.setOnClickListener { onClick() }
        buttons.addView(view)
    }

    private fun applyTheme() {
        // ponytail: solid MAIN_BACKGROUND. Colors' image background is one shared drawable with the key frame, so it is not assigned here. Upgrade: copy that bitmap.
        val colors = Settings.getValues().mColors
        setBackgroundColor(colors.get(ColorType.MAIN_BACKGROUND))
        ink.inkColor = colors.get(ColorType.KEY_TEXT)
    }

    private fun face(type: ColorType) = GradientDrawable().apply {
        cornerRadius = dp(6).toFloat()
        setColor(Settings.getValues().mColors.get(type))
    }

    private fun text(label: String, sp: Float): TextView {
        val view = TextView(context)
        view.text = label
        view.gravity = Gravity.CENTER
        view.isFocusable = false
        view.isFocusableInTouchMode = false
        view.setTextColor(Settings.getValues().mColors.get(ColorType.KEY_TEXT))
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        return view
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private inner class InkView(context: Context) : View(context) {
        var strokes: List<FloatArray> = emptyList()
            set(value) {
                field = value
                live = FloatArray(0)
                invalidate()
            }
        private var live = FloatArray(0)
        var inkColor: Int
            get() = paint.color
            set(value) { paint.color = value }

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(4).toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = 0xFF222222.toInt()
        }
        private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1).toFloat()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> live = floatArrayOf(event.x, event.y)
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    live += floatArrayOf(event.x, event.y)
                    if (event.actionMasked == MotionEvent.ACTION_UP && live.size >= 4) {
                        listener?.onStroke(live)
                    }
                }
            }
            invalidate()
            return true
        }

        override fun onDraw(canvas: Canvas) {
            drawMiZhi(canvas)
            drawAll(canvas, strokes)
            if (live.size >= 4) drawOne(canvas, live)
        }

        /** 米字格: square, cross, both diagonals. Marks the writing box when no tiles are showing. */
        private fun drawMiZhi(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w < 2f || h < 2f) return
            val side = minOf(w, h) * 0.86f
            val left = (w - side) / 2f
            val top = (h - side) / 2f
            val right = left + side
            val bottom = top + side
            val midX = (left + right) / 2f
            val midY = (top + bottom) / 2f
            guide.color = inkColor
            guide.alpha = 56
            canvas.drawRect(left, top, right, bottom, guide)
            canvas.drawLine(left, midY, right, midY, guide)
            canvas.drawLine(midX, top, midX, bottom, guide)
            canvas.drawLine(left, top, right, bottom, guide)
            canvas.drawLine(right, top, left, bottom, guide)
        }

        private fun drawAll(canvas: Canvas, all: List<FloatArray>) {
            for (stroke in all) drawOne(canvas, stroke)
        }

        private fun drawOne(canvas: Canvas, stroke: FloatArray) {
            val path = Path()
            path.moveTo(stroke[0], stroke[1])
            var i = 2
            while (i + 1 < stroke.size) {
                path.lineTo(stroke[i], stroke[i + 1])
                i += 2
            }
            canvas.drawPath(path, paint)
        }
    }

    companion object {
        const val TAG = "hanzi_lookup"
        private const val SWITCHER_CLEARANCE_DP = 56
    }
}
