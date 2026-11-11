package app.calendar.logic

/** Placement of one item inside a day column. */
data class Placement(val column: Int, val columnCount: Int)

/**
 * Lays out overlapping intervals side by side. Items are grouped into clusters of
 * transitively-overlapping intervals; each cluster gets as many columns as it needs,
 * and every item in the cluster shares that column count so widths line up.
 *
 * Returns placements in the same order as [intervals].
 */
fun layoutColumns(intervals: List<IntRange>): List<Placement> {
    if (intervals.isEmpty()) return emptyList()
    val order = intervals.indices.sortedWith(
        compareBy<Int> { intervals[it].first }.thenByDescending { intervals[it].last },
    )
    val result = arrayOfNulls<Placement>(intervals.size)
    val cluster = ArrayList<Pair<Int, Int>>() // index, column
    val columnEnds = ArrayList<Int>()
    var clusterEnd = Int.MIN_VALUE

    fun flush() {
        val count = columnEnds.size
        for ((idx, col) in cluster) result[idx] = Placement(col, count)
        cluster.clear()
        columnEnds.clear()
    }

    for (idx in order) {
        val r = intervals[idx]
        // An interval is [first, last) in minutes; touching end-to-start does not overlap.
        val start = r.first
        val end = maxOf(r.last, r.first + 1)
        if (cluster.isNotEmpty() && start >= clusterEnd) flush()
        var col = columnEnds.indexOfFirst { it <= start }
        if (col == -1) {
            col = columnEnds.size
            columnEnds += end
        } else {
            columnEnds[col] = end
        }
        cluster += idx to col
        clusterEnd = if (cluster.size == 1) end else maxOf(clusterEnd, end)
    }
    flush()
    return result.map { it!! }
}

/**
 * Assigns rows ("lanes") to horizontal bars covering day ranges, e.g. multi-day
 * events in the all-day row. Ranges are inclusive day indexes.
 */
fun layoutLanes(ranges: List<IntRange>): List<Int> {
    val order = ranges.indices.sortedWith(
        compareBy<Int> { ranges[it].first }.thenByDescending { ranges[it].last - ranges[it].first },
    )
    val laneEnds = ArrayList<Int>()
    val lanes = IntArray(ranges.size)
    for (idx in order) {
        val r = ranges[idx]
        var lane = laneEnds.indexOfFirst { it < r.first }
        if (lane == -1) {
            lane = laneEnds.size
            laneEnds += r.last
        } else {
            laneEnds[lane] = r.last
        }
        lanes[idx] = lane
    }
    return lanes.toList()
}
