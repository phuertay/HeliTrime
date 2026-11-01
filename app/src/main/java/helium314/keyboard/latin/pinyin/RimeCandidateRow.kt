package helium314.keyboard.latin.pinyin

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import helium314.keyboard.latin.SuggestedWords
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo

object RimeCandidateRow {
    fun isRime(words: SuggestedWords?): Boolean {
        if (words == null || words.isEmpty) return false
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
        val scroll = HorizontalScrollView(context)
        scroll.isHorizontalScrollBarEnabled = false
        scroll.isFillViewport = true
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        if (words.mRimePageNo > 0) row.addView(pageButton(context, "‹") { onPage(true) })
        for (index in 0 until words.size()) {
            val info = words.getInfo(index)
            if (info.mCandidateIndex >= 0) row.addView(candidate(context, info) { onPick(info) })
            else row.addView(candidate(context, info, null))
        }
        if (!words.mRimeLastPage) row.addView(pageButton(context, "›") { onPage(false) })
        if (onExpand != null && (0 until words.size()).any { words.getInfo(it).mCandidateIndex >= 0 }) {
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
        val pad = dp(context, 10)
        column.setPadding(pad, 0, pad, 0)
        val word = TextView(context)
        word.text = info.mWord
        word.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        word.gravity = Gravity.CENTER
        column.addView(word)
        if (info.mComment.isNotEmpty()) {
            val comment = TextView(context)
            comment.text = info.mComment
            comment.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            comment.gravity = Gravity.CENTER
            comment.alpha = 0.7f
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
        view.setOnClickListener { onClick() }
        return view
    }

    private fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }
}
