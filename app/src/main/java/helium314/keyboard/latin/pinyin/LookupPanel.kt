package helium314.keyboard.latin.pinyin

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

interface LookupListener {
    fun onClose()
    fun onPress(row: LookupRow)
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
    private val scrollGrid = ScrollView(context)
    private val ink = InkView(context)
    private val fileFaces = loadSystemCjkFaces()
    private val extensionFace = loadExtensionFace(context)
    private val glyphPaint = Paint().apply { textSize = 64f }
    private val faceByCode = HashMap<Int, Typeface>()
    private val buttons = LinearLayout(context)
    private var model = LookupModel(emptyList(), emptyList(), 0, false, false, true, false, emptyList())
    private var gridColumns = -1
    private var buttonKey = ""
    private val scrollChips: HorizontalScrollView
    private val body: LinearLayout

    init {
        tag = TAG
        orientation = VERTICAL
        isClickable = true
        // A focused child finishes the IME before the click can commit the character.
        isFocusable = false
        isFocusableInTouchMode = false
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        applyTheme()
        scrollChips = HorizontalScrollView(context)
        scrollChips.isHorizontalScrollBarEnabled = false
        scrollChips.isFocusable = false
        scrollChips.isFocusableInTouchMode = false
        chips.orientation = HORIZONTAL
        scrollChips.addView(chips)
        addView(scrollChips, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        buttons.orientation = HORIZONTAL
        buttons.gravity = Gravity.CENTER_VERTICAL or Gravity.END
        buttons.isFocusable = false
        buttons.isFocusableInTouchMode = false
        scrollGrid.isFocusable = false
        scrollGrid.isFocusableInTouchMode = false
        grid.orientation = VERTICAL
        scrollGrid.addView(grid)
        ink.tag = INK_TAG
        body = WritingBody(context)
        body.addView(scrollGrid, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        body.addView(ink, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!model.pad) return
        if (columnCount() != gridColumns) bindGrid()
    }

    fun setModel(next: LookupModel) {
        val sameGrid = next.rows == model.rows && next.pad == model.pad
        model = next
        if (!sameGrid) scrollGrid.scrollTo(0, 0)
        // The line is stored before the candidate grid is rebuilt.
        ink.strokes = next.strokes
        ink.visibility = if (next.pad) VISIBLE else GONE
        applyTheme()
        bindChips()
        bindGrid()
        bindButtons()
    }

    /** Already drawn. The candidate list can follow later. */
    fun showStrokes(strokes: List<FloatArray>) {
        ink.strokes = strokes
        if (model.pad) ink.visibility = VISIBLE
    }

    /** 写, ×, and the pad controls. The strip hosts this row while 查 is open. */
    fun actionBar(): View = buttons

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

    /** Every candidate. A window of rows rebuilt during the drag ate the scroll. */
    private fun bindGrid() {
        grid.removeAllViews()
        val rows = model.rows
        if (rows.isEmpty()) return
        // A character stays about an eighth of the keyboard. The pad keeps the rest, as a square.
        val columns = columnCount()
        gridColumns = columns
        var line = newLine()
        var used = 0
        for (item in rows) {
            val span = lookupSpan(item.text, columns)
            if (used + span > columns) {
                padLine(line, columns - used)
                line = newLine()
                used = 0
            }
            val cell = candidate(item)
            cell.layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, span.toFloat()).apply {
                setMargins(dp(2), dp(1), dp(2), dp(1))
            }
            line.addView(cell)
            used += span
        }
        padLine(line, columns - used)
    }

    /** Pad open: the writing square takes the body height. The grid keeps what is left. */
    private fun columnCount(): Int {
        if (!model.pad) return 8
        if (width <= 0 || height <= 0) return 4
        val bodyHeight = (height - scrollChips.height).coerceAtLeast(1)
        val inkSide = min(bodyHeight, width)
        val gridWidth = (width - inkSide).coerceAtLeast(0)
        return max(1, (8f * gridWidth / width).roundToInt())
    }

    private fun newLine(): LinearLayout {
        val line = LinearLayout(context)
        line.orientation = HORIZONTAL
        grid.addView(line, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        return line
    }

    private fun padLine(line: LinearLayout, rest: Int) {
        if (rest <= 0) return
        // Width only. A fixed height here stretched every tile to that height.
        line.addView(View(context), LayoutParams(0, 0, rest.toFloat()))
    }

    /** One column per character, syllable under the hanzi. A word takes one column per character. */
    private fun candidate(item: LookupRow): LinearLayout {
        val cell = LinearLayout(context)
        cell.orientation = HORIZONTAL
        cell.gravity = Gravity.CENTER_VERTICAL
        cell.background = face(ColorType.KEY_BACKGROUND)
        var offset = 0
        var index = 0
        while (offset < item.text.length) {
            val end = offset + Character.charCount(item.text.codePointAt(offset))
            cell.addView(
                glyph(item.text.substring(offset, end), item.readings.getOrElse(index) { "" }),
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
            )
            offset = end
            index++
        }
        if (offset == 0) cell.addView(glyph("", ""), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        pickTarget(cell, item)
        return cell
    }

    private fun glyph(hanzi: String, reading: String): LinearLayout {
        val column = LinearLayout(context)
        column.orientation = VERTICAL
        column.gravity = Gravity.CENTER_HORIZONTAL
        val word = text(hanzi, 20f)
        word.includeFontPadding = false
        word.setPadding(0, 0, 0, 0)
        word.minHeight = 0
        val face = if (hanzi.isEmpty()) Typeface.DEFAULT else faceFor(hanzi.codePointAt(0))
        // The box is the face's full line. A shorter one clips the stroke and the syllable covers it.
        word.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, textLineHeight(face, 20f),
        ).apply { bottomMargin = dp(2) }
        column.addView(word)
        column.addView(readingLabel(reading))
        return column
    }

    /** Ascent through descent, and at least the em. Tone marks and CJK strokes both sit in that span. */
    private fun textLineHeight(face: Typeface, sp: Float): Int {
        val paint = TextPaint()
        paint.typeface = face
        paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
        val metrics = paint.fontMetricsInt
        return max(metrics.descent - metrics.ascent, ceil(paint.textSize).toInt())
    }

    /** Latin face. The hanzi face does not carry the tone marks. */
    private fun readingLabel(reading: String): TextView {
        val view = TextView(context)
        view.text = reading
        view.gravity = Gravity.CENTER
        view.isFocusable = false
        view.isFocusableInTouchMode = false
        view.setSingleLine(true)
        view.includeFontPadding = false
        view.setPadding(0, 0, 0, 0)
        view.minHeight = 0
        view.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, textLineHeight(Typeface.DEFAULT, 9f),
        )
        view.typeface = Typeface.DEFAULT
        view.setTextColor(Settings.getValues().mColors.get(ColorType.KEY_TEXT))
        view.alpha = 0.75f
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
        return view
    }

    private fun bindButtons() {
        val key = buttonKey()
        if (key == buttonKey && buttons.childCount > 0) return
        buttonKey = key
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

    private fun buttonKey(): String {
        if (!model.pad) return "grid"
        val widen = if (!model.hasSpelling) "" else if (model.widened) "全·开" else "全"
        val script = if (model.simplified) "简" else "繁"
        return "pad$widen$script"
    }

    /** A drag scrolls. A tap selects. Finger-down used to commit, so the list could not move. */
    @SuppressLint("ClickableViewAccessibility")
    private fun pickTarget(view: View, row: LookupRow) {
        val slop = dp(12).toFloat()
        var downX = 0f
        var downY = 0f
        var moved = false
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved && (abs(event.x - downX) > slop || abs(event.y - downY) > slop)) {
                        moved = true
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val near = abs(event.x - downX) <= slop && abs(event.y - downY) <= slop
                    if (!moved && near) listener?.onPick(row)
                }
            }
            !moved
        }
    }

    /** Finger-down. A click is too late: the press rebuilds this row, or the editor has already dropped the connection. */
    @SuppressLint("ClickableViewAccessibility")
    private fun button(label: String, onClick: () -> Unit) {
        val view = text(label, 16f)
        view.includeFontPadding = false
        view.minHeight = 0
        view.setPadding(dp(12), 0, dp(12), 0)
        view.background = face(ColorType.FUNCTIONAL_KEY_BACKGROUND)
        view.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).apply {
            marginEnd = dp(4)
        }
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) onClick()
            true
        }
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
        view.text = withGlyphFace(label)
        view.gravity = Gravity.CENTER
        view.isFocusable = false
        view.isFocusableInTouchMode = false
        view.typeface = Typeface.DEFAULT
        view.setTextColor(Settings.getValues().mColors.get(ColorType.KEY_TEXT))
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        return view
    }

    /** System CJK faces stop before most of Extension B. The bundled face has those glyphs. */
    private fun withGlyphFace(label: String): CharSequence {
        if (label.isEmpty() || Build.VERSION.SDK_INT < 23) return label
        val span = SpannableString(label)
        var offset = 0
        while (offset < label.length) {
            val codePoint = label.codePointAt(offset)
            val end = offset + Character.charCount(codePoint)
            span.setSpan(FaceSpan(faceFor(codePoint)), offset, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            offset = end
        }
        return span
    }

    private fun faceFor(codePoint: Int): Typeface {
        faceByCode[codePoint]?.let { return it }
        val sample = String(Character.toChars(codePoint))
        val fromFile = fileFaces.firstOrNull { face ->
            glyphPaint.typeface = face
            glyphPaint.hasGlyph(sample)
        }
        val found = when {
            fromFile != null -> fromFile
            extensionFace != null && (fileFaces.isNotEmpty() || !fallbackHas(sample)) -> extensionFace
            else -> Typeface.DEFAULT
        }
        faceByCode[codePoint] = found
        return found
    }

    private fun fallbackHas(sample: String): Boolean {
        glyphPaint.typeface = Typeface.DEFAULT
        return glyphPaint.hasGlyph(sample)
    }

    private class FaceSpan(private val face: Typeface) : MetricAffectingSpan() {
        override fun updateDrawState(paint: TextPaint) {
            paint.typeface = face
        }
        override fun updateMeasureState(paint: TextPaint) {
            paint.typeface = face
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private inner class InkView(context: Context) : View(context) {
        var strokes: List<FloatArray> = emptyList()
            set(value) {
                field = value
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
            parent?.requestDisallowInterceptTouchEvent(true)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> live = floatArrayOf(event.x, event.y)
                MotionEvent.ACTION_MOVE -> live += floatArrayOf(event.x, event.y)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> finishStroke(event)
            }
            invalidate()
            return true
        }

        /** The line stays in this view. A later score must not be what makes it visible. */
        private fun finishStroke(event: MotionEvent) {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                live += floatArrayOf(event.x, event.y)
            }
            val normalized = normalize(live)
            live = FloatArray(0)
            if (normalized.size < 4) return
            val kept = strokes + normalized
            strokes = kept
            if (event.actionMasked != MotionEvent.ACTION_UP) return
            listener?.onStroke(normalized)
            if (strokes.size < kept.size) strokes = kept
        }

        /** 0..1000 of this view. A later resize redraws the same stroke inside the new box. */
        private fun normalize(points: FloatArray): FloatArray {
            val sx = width.toFloat().coerceAtLeast(1f)
            val sy = height.toFloat().coerceAtLeast(1f)
            val out = FloatArray(points.size)
            var index = 0
            while (index + 1 < points.size) {
                out[index] = points[index] / sx * INK_UNITS
                out[index + 1] = points[index + 1] / sy * INK_UNITS
                index += 2
            }
            return out
        }

        override fun onDraw(canvas: Canvas) {
            drawMiZhi(canvas)
            val sx = width.toFloat() / INK_UNITS
            val sy = height.toFloat() / INK_UNITS
            drawAll(canvas, strokes, sx, sy)
            if (live.size >= 4) drawOne(canvas, live, 1f, 1f)
        }

        /** 米字格: square, cross, both diagonals. Fills the writing view, which is already the tall square. */
        private fun drawMiZhi(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val inset = dp(4).toFloat()
            val side = minOf(w, h) - inset * 2f
            if (side < 2f) return
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

        private fun drawAll(canvas: Canvas, all: List<FloatArray>, sx: Float, sy: Float) {
            for (stroke in all) drawOne(canvas, stroke, sx, sy)
        }

        private fun drawOne(canvas: Canvas, stroke: FloatArray, sx: Float, sy: Float) {
            val path = Path()
            path.moveTo(stroke[0] * sx, stroke[1] * sy)
            var i = 2
            while (i + 1 < stroke.size) {
                path.lineTo(stroke[i] * sx, stroke[i + 1] * sy)
                i += 2
            }
            canvas.drawPath(path, paint)
        }
    }

    /** While writing, the pad is a square of the body height. The grid keeps the width that remains. */
    private inner class WritingBody(context: Context) : LinearLayout(context) {
        init { orientation = HORIZONTAL }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (!model.pad) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                return
            }
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val height = MeasureSpec.getSize(heightMeasureSpec)
            val inkSide = min(height, width)
            val gridWidth = (width - inkSide).coerceAtLeast(0)
            val exactHeight = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
            scrollGrid.measure(MeasureSpec.makeMeasureSpec(gridWidth, MeasureSpec.EXACTLY), exactHeight)
            ink.measure(MeasureSpec.makeMeasureSpec(inkSide, MeasureSpec.EXACTLY), exactHeight)
            setMeasuredDimension(width, height)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            if (!model.pad) {
                super.onLayout(changed, left, top, right, bottom)
                return
            }
            val width = right - left
            val height = bottom - top
            val inkSide = min(height, width)
            val gridWidth = width - inkSide
            val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
            if (rtl) {
                ink.layout(0, 0, inkSide, height)
                scrollGrid.layout(inkSide, 0, width, height)
            } else {
                scrollGrid.layout(0, 0, gridWidth, height)
                ink.layout(gridWidth, 0, width, height)
            }
        }
    }

    companion object {
        const val TAG = "hanzi_lookup"
        const val INK_TAG = "hanzi_ink"
        private const val INK_UNITS = 1000f

        /** Simplified Chinese face. Index 2 is SC in Noto CJK and in Samsung's SEC CJK collection. */
        private fun loadSystemCjkFaces(): List<Typeface> {
            if (Build.VERSION.SDK_INT < 26) return emptyList()
            val paths = arrayOf(
                "/system/fonts/NotoSansCJK-Regular.ttc",
                "/system/fonts/NotoSerifCJK-Regular.ttc",
                "/system/fonts/SECCJK-Regular.ttc",
            )
            val loaded = ArrayList<Typeface>()
            for (path in paths) {
                val file = File(path)
                if (!file.isFile) continue
                val face = runCatching { Typeface.Builder(file).setTtcIndex(2).build() }.getOrNull() ?: continue
                loaded.add(face)
            }
            return loaded
        }

        private fun loadExtensionFace(context: Context): Typeface? {
            return runCatching { Typeface.createFromAsset(context.assets, "fonts/cjk_ext.ttf") }.getOrNull()
        }
    }
}
