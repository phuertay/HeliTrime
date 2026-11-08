package helium314.keyboard.latin.pinyin

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * One stroke, packed to match `tools/build_hanzi_lookup.py`.
 * Bits: direction 0–2, length bucket 3–4, start quadrant 5–6, 3×3 cell 7–10,
 * bend direction 11–13, bend flag 14.
 * The cell is where the stroke sits in the square, not in its own bounding box.
 * Y grows downward, the same way a finger does.
 */
const val INK_SQUARE = 1000f

fun inkFeatures(strokes: List<FloatArray>): IntArray {
    if (strokes.isEmpty()) return IntArray(0)
    return IntArray(strokes.size) { packStroke(strokes[it], 0f, 0f, INK_SQUARE, INK_SQUARE) }
}

fun packStroke(stroke: FloatArray, minX: Float, minY: Float, maxX: Float, maxY: Float): Int {
    val width = max(maxX - minX, 1f)
    val height = max(maxY - minY, 1f)
    val x0 = stroke[0]
    val y0 = stroke[1]
    val x1 = stroke[stroke.size - 2]
    val y1 = stroke[stroke.size - 1]
    val direction = directionOf(x1 - x0, y1 - y0)
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
    return direction or (bucket shl 3) or (quadrant shl 5) or ((cellY * 3 + cellX) shl 7) or bendBits(stroke)
}

/** Head and tail disagree by 90° or more. A hook is not a straight line. */
private fun bendBits(stroke: FloatArray): Int {
    val points = stroke.size / 2
    if (points < 3) return 0
    val headAt = max(1, points / 3)
    val tailAt = min(points - 2, (2 * points) / 3)
    val head = directionOf(stroke[headAt * 2] - stroke[0], stroke[headAt * 2 + 1] - stroke[1])
    val last = points - 1
    val tail = directionOf(
        stroke[last * 2] - stroke[tailAt * 2],
        stroke[last * 2 + 1] - stroke[tailAt * 2 + 1],
    )
    if (directionDistance(head, tail) < 2) return 0
    return (tail shl 11) or (1 shl 14)
}

private fun directionOf(dx: Float, dy: Float): Int {
    if (dx == 0f && dy == 0f) return 0
    var angle = atan2(dy, dx)
    if (angle < 0f) angle += (Math.PI * 2).toFloat()
    return (((angle + Math.PI / 8.0) / (Math.PI / 4.0)).toInt()) and 7
}

/**
 * Higher is closer. [Int.MIN_VALUE] when the stroke counts differ by more than the allowance.
 * ponytail: greedy pairing, not min-cost. Crossed strokes can swap. Upgrade path is
 * Hungarian on the same similarity, still per character.
 */
/** A short character can be off by one stroke. Six or more can be off by two. */
fun strokeCountFits(inkCount: Int, modelCount: Int): Boolean {
    if (inkCount <= 0 || modelCount <= 0) return false
    val allowance = if (max(inkCount, modelCount) >= 6) 2 else 1
    return abs(inkCount - modelCount) <= allowance
}

fun matchScore(ink: IntArray, model: IntArray): Int {
    if (!strokeCountFits(ink.size, model.size)) return Int.MIN_VALUE
    val extra = abs(ink.size - model.size)
    if (extra == 0) return assignStrokes(ink, model)
    return dropScore(ink, model, extra) - 8 * extra
}

fun collapseHits(
    hits: List<Pair<Int, Int>>,
    simplified: Boolean,
    bonus: (Int) -> Int = { 0 },
    display: (Int, Boolean) -> Int,
): List<Int> {
    val best = HashMap<Int, Int>()
    val order = ArrayList<Int>()
    for ((codePoint, score) in hits) {
        val shown = display(codePoint, simplified)
        if (shown == 0) continue
        val total = score + bonus(shown)
        val previous = best[shown]
        if (previous == null) {
            best[shown] = total
            order.add(shown)
        } else if (total > previous) {
            best[shown] = total
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
    val turn = directionDistance(left and 7, right and 7)
    // ponytail: exact reverse (系's top stroke drawn left-to-right) is distance 4.
    // Charge one bucket, and skip the start quadrant, which flips with the reversal.
    // 90° stays full price so 一 and 丨 still split. Upgrade: match the stroke axis.
    val reversed = turn == 4
    val direction = if (reversed) 1 else turn
    val length = abs(((left shr 3) and 3) - ((right shr 3) and 3))
    val sameStart = ((left shr 5) and 3) == ((right shr 5) and 3)
    val quadrant = if (sameStart || reversed) 0 else 1
    val cell = cellDistance((left shr 7) and 15, (right shr 7) and 15)
    val bendL = (left shr 14) and 1
    val bendR = (right shr 14) and 1
    val bend = if (reversed || (bendL == 0 && bendR == 0)) 0
    else if (bendL != bendR) 2
    else directionDistance((left shr 11) and 7, (right shr 11) and 7)
    return 40 - direction * 6 - length * 3 - quadrant * 4 - cell * 4 - bend * 3
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
