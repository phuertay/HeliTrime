package helium314.keyboard.latin.pinyin

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import helium314.keyboard.latin.suggestions.SuggestedWordInfo
import helium314.keyboard.latin.suggestions.SuggestedWords

object RimeCandidateRow {
    fun isRime(words: SuggestedWords?): Boolean {
        if (words == null || words.isEmpty()) return false
        return words.getInfo(0).isKindOf(SuggestedWordInfo.KIND_RIME)
    }

    fun scrollOf(parent: ViewGroup): Int = findScroll(parent)?.scrollX ?: 0

    fun bind(
        parent: ViewGroup,
        words: SuggestedWords,
        onPick: (SuggestedWordInfo) -> Unit,
        onExpand: (() -> Unit)? = null,
        initialScroll: Int = 0,
    ) {
        val context = parent.context
        val bar = LinearLayout(context)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.isFocusable = false
        val scroll = HorizontalScrollView(context)
        scroll.isHorizontalScrollBarEnabled = false
        scroll.isFillViewport = true
        scroll.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        // A focused scroller finishes the input before the drag can move the row.
        scroll.isFocusable = false
        scroll.isFocusableInTouchMode = false
        scroll.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.isFocusable = false
        for (index in 0 until words.size) {
            val info = words.getInfo(index)
            if (info.mCandidateIndex >= 0) row.addView(candidate(context, info) { onPick(info) })
            else row.addView(candidate(context, info, null))
        }
        scroll.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        bar.addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        if (onExpand != null && (0 until words.size).any { words.getInfo(it).mCandidateIndex >= 0 }) {
            bar.addView(pageButton(context, "查") { onExpand() })
        }
        parent.addView(bar, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (initialScroll > 0) {
            scroll.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (scroll.viewTreeObserver.isAlive) scroll.viewTreeObserver.removeOnPreDrawListener(this)
                    val child = scroll.getChildAt(0)
                    val max = if (child == null) 0 else (child.width - scroll.width).coerceAtLeast(0)
                    scroll.scrollTo(initialScroll.coerceAtMost(max), 0)
                    return true
                }
            })
        }
    }

    private fun findScroll(view: View): HorizontalScrollView? {
        if (view is HorizontalScrollView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findScroll(view.getChildAt(index))?.let { return it }
            }
        }
        return null
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

