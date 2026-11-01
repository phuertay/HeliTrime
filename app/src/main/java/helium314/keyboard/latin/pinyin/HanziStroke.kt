package helium314.keyboard.latin.pinyin

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * One stroke, packed to match `tools/build_hanzi_lookup.py`.
 * Bits: direction 0–2, length bucket 3–4, start quadrant 5–6, 3×3 cell 7–10.
 * Y grows downward, the same way a finger does.
 */
fun inkFeatures(strokes: List<FloatArray>): IntArray {
    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    for (stroke in strokes) {
        var i = 0
        while (i + 1 < stroke.size) {
            minX = min(minX, stroke[i])
            maxX = max(maxX, stroke[i])
            minY = min(minY, stroke[i + 1])
            maxY = max(maxY, stroke[i + 1])
            i += 2
        }
    }
    if (minX == Float.POSITIVE_INFINITY) return IntArray(0)
    return IntArray(strokes.size) { packStroke(strokes[it], minX, minY, maxX, maxY) }
}

fun packStroke(stroke: FloatArray, minX: Float, minY: Float, maxX: Float, maxY: Float): Int {
    val width = max(maxX - minX, 1f)
    val height = max(maxY - minY, 1f)
    val x0 = stroke[0]
    val y0 = stroke[1]
    val x1 = stroke[stroke.size - 2]
    val y1 = stroke[stroke.size - 1]
    var angle = atan2(y1 - y0, x1 - x0)
    if (angle < 0f) angle += (Math.PI * 2).toFloat()
    val direction = (((angle + Math.PI / 8.0) / (Math.PI / 4.0)).toInt()) and 7
    var length = 0.0
    var i = 2
    while (i + 1 < stroke.size) {
        length += hypot((stroke[i] - stroke[i - 2]).toDouble(), (stroke[i + 1] - stroke[i - 1]).toDouble())
        i += 2
    }
    val diagonal = max(1.0, hypot(width.toDouble(), height.toDouble()))
    val ratio = length / diagonal
    val bucket = when {
        ratio < 0.25 -> 0
        ratio < 0.5 -> 1
        ratio < 0.75 -> 2
        else -> 3
    }
    val startX = ((x0 - minX) / width).coerceIn(0f, 0.999f)
    val startY = ((y0 - minY) / height).coerceIn(0f, 0.999f)
    val quadrant = (if (startX < 0.5f) 0 else 1) + (if (startY < 0.5f) 0 else 2)
    var cx = 0.0
    var cy = 0.0
    var count = 0
    i = 0
    while (i + 1 < stroke.size) {
        cx += stroke[i]
        cy += stroke[i + 1]
        count++
        i += 2
    }
    cx /= count
    cy /= count
    val cellX = (((cx - minX) / width) * 3).toInt().coerceIn(0, 2)
    val cellY = (((cy - minY) / height) * 3).toInt().coerceIn(0, 2)
    return direction or (bucket shl 3) or (quadrant shl 5) or ((cellY * 3 + cellX) shl 7)
}

/**
 * Higher is closer. [Int.MIN_VALUE] when the stroke counts differ by more than the allowance.
 * ponytail: greedy pairing, not min-cost. Crossed strokes can swap. Upgrade path is
 * Hungarian on the same similarity, still per character.
 */
fun matchScore(ink: IntArray, model: IntArray): Int {
    if (ink.isEmpty() || model.isEmpty()) return Int.MIN_VALUE
    val extra = abs(ink.size - model.size)
    // ponytail: a 7-stroke character written without its dots is off by two. Short characters stay at one. Upgrade: stroke-edit distance instead of dropping whole strokes.
    val allowance = if (max(ink.size, model.size) >= 6) 2 else 1
    if (extra > allowance) return Int.MIN_VALUE
    if (extra == 0) return assignStrokes(ink, model)
    return dropScore(ink, model, extra) - 8 * extra
}

fun collapseHits(
    hits: List<Pair<Int, Int>>,
    simplified: Boolean,
    display: (Int, Boolean) -> Int,
): List<Int> {
    val best = HashMap<Int, Int>()
    val order = ArrayList<Int>()
    for ((codePoint, score) in hits) {
        val shown = display(codePoint, simplified)
        if (shown == 0) continue
        val previous = best[shown]
        if (previous == null) {
            best[shown] = score
            order.add(shown)
        } else if (score > previous) {
            best[shown] = score
        }
    }
    return order.sortedByDescending { best[it] ?: 0 }.take(48)
}

private fun assignStrokes(ink: IntArray, model: IntArray): Int {
    val used = BooleanArray(model.size)
    var total = 0
    for (stroke in ink) {
        var bestIndex = -1
        var bestValue = Int.MIN_VALUE
        for (index in model.indices) {
            if (used[index]) continue
            val value = strokeSimilarity(stroke, model[index])
            if (value > bestValue) {
                bestValue = value
                bestIndex = index
            }
        }
        if (bestIndex < 0) return Int.MIN_VALUE
        used[bestIndex] = true
        total += bestValue
    }
    return total
}

private fun strokeSimilarity(left: Int, right: Int): Int {
    val direction = directionDistance(left and 7, right and 7)
    val length = abs(((left shr 3) and 3) - ((right shr 3) and 3))
    val quadrant = if (((left shr 5) and 3) == ((right shr 5) and 3)) 0 else 1
    val cell = cellDistance((left shr 7) and 15, (right shr 7) and 15)
    return 40 - direction * 6 - length * 3 - quadrant * 4 - cell * 4
}

private fun directionDistance(left: Int, right: Int): Int {
    val delta = abs(left - right)
    return min(delta, 8 - delta)
}

private fun cellDistance(left: Int, right: Int): Int {
    val dx = abs(left % 3 - right % 3)
    val dy = abs(left / 3 - right / 3)
    return max(dx, dy)
}

private fun dropScore(ink: IntArray, model: IntArray, extra: Int): Int {
    var best = Int.MIN_VALUE
    if (extra == 1) {
        if (ink.size > model.size) {
            for (skip in ink.indices) best = max(best, assignStrokes(without(ink, skip), model))
        } else {
            for (skip in model.indices) best = max(best, assignStrokes(ink, without(model, skip)))
        }
        return best
    }
    if (ink.size > model.size) {
        for (first in ink.indices) {
            for (second in first + 1 until ink.size) {
                best = max(best, assignStrokes(without(ink, first, second), model))
            }
        }
    } else {
        for (first in model.indices) {
            for (second in first + 1 until model.size) {
                best = max(best, assignStrokes(ink, without(model, first, second)))
            }
        }
    }
    return best
}

private fun without(values: IntArray, skip: Int): IntArray = without(values, skip, -1)

private fun without(values: IntArray, skip: Int, also: Int): IntArray {
    val out = IntArray(values.size - if (also >= 0) 2 else 1)
    var write = 0
    for (index in values.indices) {
        if (index == skip || index == also) continue
        out[write++] = values[index]
    }
    return out
}
