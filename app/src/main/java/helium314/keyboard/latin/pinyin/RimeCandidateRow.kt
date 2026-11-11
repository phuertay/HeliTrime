package helium314.keyboard.latin.pinyin

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import helium314.keyboard.latin.suggestions.SuggestedWordInfo
import helium314.keyboard.latin.suggestions.SuggestedWords
import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class CandidateSlide { STAY, PREVIOUS, NEXT }

/**
 * [overscroll] is how far the finger pulled past the end of the row.
 * Positive is to the right, which asks for the previous page.
 * A pull shorter than [slop] stays, so a flick that is really a tap does not turn the page.
 */
internal fun candidateSlide(
    overscroll: Float,
    velocity: Float,
    threshold: Float,
    slop: Float,
    minFling: Float,
    hasPrevious: Boolean,
    hasNext: Boolean,
): CandidateSlide {
    val previous = hasPrevious && (overscroll >= threshold || (overscroll >= slop && velocity >= minFling))
    val next = hasNext && (overscroll <= -threshold || (overscroll <= -slop && velocity <= -minFling))
    return when {
        previous -> CandidateSlide.PREVIOUS
        next -> CandidateSlide.NEXT
        else -> CandidateSlide.STAY
    }
}

/** [fingerDx] positive means the finger moved right, so the row can slide only when there is room that way. */
internal fun stripCanScroll(fingerDx: Float, scrollX: Int, viewport: Int, content: Int): Boolean {
    val max = (content - viewport).coerceAtLeast(0)
    if (max == 0 || fingerDx == 0f) return false
    if (fingerDx > 0f) return scrollX > 0
    return scrollX < max
}

object RimeCandidateRow {
    fun isRime(words: SuggestedWords?): Boolean {
        if (words == null || words.isEmpty()) return false
        return words.getInfo(0).isKindOf(SuggestedWordInfo.KIND_RIME)
    }

    fun bind(
        parent: ViewGroup,
        words: SuggestedWords,
        onPick: (SuggestedWordInfo) -> Unit,
        onPage: (Boolean) -> Unit,
        onExpand: (() -> Unit)? = null,
    ) {
        val context = parent.context
        val scroll = CandidateStrip(
            context,
            hasPrevious = words.mRimePageNo > 0,
            hasNext = !words.mRimeLastPage,
            onPage = onPage,
        )
        scroll.isHorizontalScrollBarEnabled = false
        scroll.isFillViewport = true
        scroll.overScrollMode = ViewGroup.OVER_SCROLL_NEVER
        // A focused scroller finishes the input before the drag can move the row.
        scroll.isFocusable = false
        scroll.isFocusableInTouchMode = false
        scroll.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.isFocusable = false
        if (words.mRimePageNo > 0) row.addView(pageButton(context, "‹") { onPage(true) })
        for (index in 0 until words.size) {
            val info = words.getInfo(index)
            if (info.mCandidateIndex >= 0) row.addView(candidate(context, info) { onPick(info) })
            else row.addView(candidate(context, info, null))
        }
        if (!words.mRimeLastPage) row.addView(pageButton(context, "›") { onPage(false) })
        if (onExpand != null && (0 until words.size).any { words.getInfo(it).mCandidateIndex >= 0 }) {
            row.addView(pageButton(context, "查") { onExpand() })
        }
        scroll.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        parent.addView(scroll, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun candidate(context: Context, info: SuggestedWordInfo, onPick: (() -> Unit)?): LinearLayout {
        val column = LinearLayout(context)
        column.orientation = LinearLayout.VERTICAL
        column.gravity = Gravity.CENTER
        column.isFocusable = false
        val pad = dp(context, 10)
        column.setPadding(pad, 0, pad, 0)
        val word = TextView(context)
        word.text = info.word
        word.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        word.gravity = Gravity.CENTER
        word.isFocusable = false
        column.addView(word)
        if (info.mComment.isNotEmpty()) {
            val comment = TextView(context)
            comment.text = info.mComment
            comment.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            comment.gravity = Gravity.CENTER
            comment.alpha = 0.7f
            comment.isFocusable = false
            column.addView(comment)
        }
        if (onPick != null) column.setOnClickListener { onPick() }
        return column
    }

    private fun pageButton(context: Context, label: String, onClick: () -> Unit): TextView {
        val view = TextView(context)
        view.text = label
        view.gravity = Gravity.CENTER
        view.setTypeface(Typeface.DEFAULT_BOLD)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        view.setPadding(dp(context, 12), 0, dp(context, 12), 0)
        view.isFocusable = false
        view.setOnClickListener { onClick() }
        return view
    }

    private fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }
}

/** A horizontal drag slides the hanzi. Past either end it turns the page. A tap still selects. */
@SuppressLint("ViewConstructor")
private class CandidateStrip(
    context: Context,
    private val hasPrevious: Boolean,
    private val hasNext: Boolean,
    private val onPage: (Boolean) -> Unit,
) : HorizontalScrollView(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat()
    private val threshold = (36 * resources.displayMetrics.density)
    private var tracker: VelocityTracker? = VelocityTracker.obtain()
    private var trackKey = Long.MIN_VALUE
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var dragging = false
    private var overscroll = 0f

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> resetGesture(event)
            MotionEvent.ACTION_MOVE -> noteMove(event)
        }
        return dragging && event.actionMasked == MotionEvent.ACTION_MOVE
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> resetGesture(event)
            MotionEvent.ACTION_MOVE -> noteMove(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                track(event)
                finishDrag(event.actionMasked == MotionEvent.ACTION_UP)
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        tracker?.recycle()
        tracker = null
        super.onDetachedFromWindow()
    }

    private fun resetGesture(event: MotionEvent) {
        downX = event.x
        downY = event.y
        lastX = event.x
        dragging = false
        overscroll = 0f
        trackKey = Long.MIN_VALUE
        tracker?.clear()
        track(event)
        getChildAt(0)?.let { child ->
            child.animate().cancel()
            child.translationX = 0f
        }
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun noteMove(event: MotionEvent) {
        track(event)
        val dx = event.x - downX
        val dy = event.y - downY
        if (!dragging) {
            if (abs(dx) <= slop || abs(dx) <= abs(dy)) return
            dragging = true
            parent?.requestDisallowInterceptTouchEvent(true)
            // The move that crosses the slop is not delivered again, so apply it here.
            val sign = if (dx > 0f) 1f else -1f
            lastX = event.x
            dragBy(dx - sign * slop)
            return
        }
        val delta = event.x - lastX
        lastX = event.x
        dragBy(delta)
    }

    private fun dragBy(fingerDx: Float) {
        if (fingerDx == 0f) return
        val child = getChildAt(0) ?: return
        if (overscroll != 0f) {
            val next = overscroll + fingerDx
            val crossed = (overscroll > 0f && next <= 0f) || (overscroll < 0f && next >= 0f)
            if (!crossed) {
                overscroll = next
                child.translationX = overscroll
                return
            }
            overscroll = 0f
            child.translationX = 0f
            scrollByFinger(next)
            return
        }
        scrollByFinger(fingerDx)
    }

    private fun scrollByFinger(fingerDx: Float) {
        val child = getChildAt(0) ?: return
        val max = (child.width - width).coerceAtLeast(0)
        if (max == 0) {
            overscroll = fingerDx
            child.translationX = fingerDx
            return
        }
        val before = scrollX
        val target = (before - fingerDx.roundToInt()).coerceIn(0, max)
        if (target != before) scrollTo(target, 0)
        val consumed = (before - target).toFloat()
        val leftover = fingerDx - consumed
        if (leftover != 0f && !stripCanScroll(leftover, scrollX, width, child.width)) {
            overscroll = leftover
            child.translationX = leftover
        }
    }

    private fun finishDrag(commit: Boolean) {
        val shift = overscroll
        val speed = tracker?.let {
            it.computeCurrentVelocity(1000)
            it.xVelocity
        } ?: 0f
        val direction = if (commit && dragging) {
            candidateSlide(shift, speed, threshold, slop, minFling, hasPrevious, hasNext)
        } else {
            CandidateSlide.STAY
        }
        dragging = false
        overscroll = 0f
        if (direction != CandidateSlide.STAY) {
            onPage(direction == CandidateSlide.PREVIOUS)
            return
        }
        val child = getChildAt(0)
        if (child != null && child.translationX != 0f) {
            child.animate().translationX(0f).setDuration(120).start()
        }
        val max = if (child == null) 0 else (child.width - width).coerceAtLeast(0)
        if (commit && shift == 0f && max > 0 && abs(speed) >= minFling) fling((-speed).toInt())
    }

    private fun track(event: MotionEvent) {
        val key = event.eventTime xor (event.actionMasked.toLong() shl 32) xor event.x.toBits().toLong()
        if (key == trackKey) return
        trackKey = key
        tracker?.addMovement(event)
    }
}
