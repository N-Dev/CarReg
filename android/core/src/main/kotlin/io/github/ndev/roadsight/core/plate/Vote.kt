package io.github.ndev.roadsight.core.plate

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * One reading of a plate by the plate reader: the text, its mean character confidence, the weakest
 * character, and the country guess. `partial` marks a plate cut off by the edge of the picture.
 */
data class Read(
    val text: String,
    val conf: Double,
    val minP: Double = 1.0,
    val region: String? = null,
    val regionProb: Double? = null,
    val partial: Boolean = false,
    val weight: Double = 1.0,
    val model: String? = null,
)

/** The consensus of several reads: the plate, how much the reads agreed (conf) and how sure the reader was (prob). */
data class PlateResult(
    val text: String,
    val key: String,
    val valid: Boolean,
    val format: String,
    val conf: Double,
    val prob: Double,
    val region: String?,
    val regionConf: Double,
    val profile: String,
    val n: Int,
    val info: PlateInfo?,
) {
    /** Overall confidence, 0..1. */
    val score: Double get() = (conf * prob).coerceIn(0.0, 1.0)

    /** "Irish", "UK", "NI" or "" (not a recognised format). */
    val formatName: String
        get() = when {
            !valid -> ""
            profile == "IE" -> "Irish"
            format == "NI" -> "NI"
            profile == "UK" -> "UK"
            else -> ""
        }

    /** When the car was registered, e.g. "Jan–Jun 2024" or "Sep 2012 – Feb 2013", or "". */
    val registered: String
        get() {
            val i = info ?: return ""
            val y = i.year ?: return ""
            return when (profile) {
                "IE" -> if (i.period != null) "${i.period} $y" else "$y"
                "UK" -> if (i.period == "Mar–Aug") "Mar–Aug $y" else "Sep $y – Feb ${y + 1}"
                else -> "$y"
            }
        }
}

/** Levenshtein distance between two short strings. */
fun editDistance(a: String, b: String): Int {
    val m = a.length
    val n = b.length
    if (m == 0) return n
    if (n == 0) return m
    var prev = IntArray(n + 1) { it }
    for (i in 1..m) {
        val cur = IntArray(n + 1)
        cur[0] = i
        for (j in 1..n) {
            cur[j] = min(min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + (if (a[i - 1] == b[j - 1]) 0 else 1))
        }
        prev = cur
    }
    return prev[n]
}

/** Same plate, allowing for reader slips: up to a quarter of the characters may differ (at least one). */
fun similar(a: String?, b: String?): Boolean {
    if (a.isNullOrEmpty() || b.isNullOrEmpty()) return false
    return editDistance(a, b) <= max(1, floor(max(a.length, b.length) * 0.25).toInt())
}

/** One reading is a fragment of the other (a plate partly out of view). */
fun contains(a: String?, b: String?): Boolean {
    if (a == null || b == null || min(a.length, b.length) < 4) return false
    return a.contains(b) || b.contains(a)
}

private fun <K> argmax(map: Map<K, Double>): Pair<K?, Double> {
    var best: K? = null
    var bw = Double.NEGATIVE_INFINITY
    for ((k, w) in map) if (w > bw) {
        best = k
        bw = w
    }
    return best to bw
}

private fun <K> MutableMap<K, Double>.add(k: K, w: Double) {
    this[k] = (this[k] ?: 0.0) + w
}

private class Item(val v: Validated, val p: Double, val w: Double)

/**
 * Consensus over reads: only reads of the same plate are combined (never two plates blended into a
 * third), then characters are voted position by position, weighted by confidence and validity.
 */
fun vote(reads: List<Read>, format: String = "auto", minConf: Double = 0.35): PlateResult? {
    val good = reads.filter { it.text.isNotEmpty() && it.conf >= minConf }
    if (good.isEmpty()) return null

    // Country: weighted by the country head's probability, ignoring "Unknown" when anything else was seen.
    val regions = LinkedHashMap<String, Double>()
    var rTotal = 0.0
    for (r in good) {
        val reg = r.region
        if (reg.isNullOrEmpty()) continue
        val w = (r.regionProb ?: 0.5) * r.conf
        rTotal += w
        if (reg != "Unknown") regions.add(reg, w)
    }
    val (region, rw) = argmax(regions)
    val regionConf = if (rTotal > 0) max(0.0, rw) / rTotal else 0.0
    val profile = Formats.profileFor(format, region)

    // Reads of a plate cut off by the frame edge count for much less.
    val items = good.map { r ->
        val v = Formats.validate(r.text, profile)
        Item(v, r.conf, r.conf * (if (v.valid) 1.6 else 0.6) * (if (r.partial) 0.3 else 1.0) * r.weight)
    }.filter { it.v.key.isNotEmpty() }
    if (items.isEmpty()) return null

    // The strongest group of mutually similar reads.
    var anchor = items[0].v.key
    var anchorW = -1.0
    for (k in LinkedHashSet(items.map { it.v.key })) {
        var w = 0.0
        for (x in items) if (similar(x.v.key, k)) w += x.w
        if (w > anchorW) {
            anchorW = w
            anchor = k
        }
    }
    val group = items.filter { similar(it.v.key, anchor) }

    // Within the group: 1) dominant length, 2) weighted vote per character position.
    val byLen = LinkedHashMap<Int, Double>()
    for (x in group) byLen.add(x.v.key.length, x.w)
    val len = argmax(byLen).first ?: return null
    val same = group.filter { it.v.key.length == len }
    val chars = buildString {
        for (i in 0 until len) {
            val sc = LinkedHashMap<Char, Double>()
            for (x in same) sc.add(x.v.key[i], x.w)
            append(argmax(sc).first!!)
        }
    }
    var best = Formats.validate(chars, profile)
    if (!best.valid) {
        // Character voting produced an invalid plate: fall back to the strongest valid read in the group, if any.
        val totals = LinkedHashMap<String, Double>()
        for (x in group) if (x.v.valid) totals.add(x.v.key, x.w)
        val k = argmax(totals).first
        if (k != null) best = Formats.validate(k, profile)
    }
    val total = items.sumOf { it.w }.let { if (it == 0.0) 1.0 else it }
    val agreeing = items.filter { it.v.key == best.key }
    val agree = agreeing.sumOf { it.w }
    val prob = if (agreeing.isNotEmpty()) agreeing.sumOf { it.p } / agreeing.size else 0.0
    return PlateResult(
        text = best.text, key = best.key, valid = best.valid, format = best.format,
        conf = min(1.0, agree / total), prob = prob, region = region, regionConf = regionConf,
        profile = profile, n = items.size, info = Formats.decode(best.key, profile),
    )
}
