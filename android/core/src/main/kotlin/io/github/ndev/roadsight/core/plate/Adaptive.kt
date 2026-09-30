package io.github.ndev.roadsight.core.plate

/** A live quality tier: which plate finder, and which reader for new and already-confirmed plates. */
class Tier(val key: String, val label: String, val finder: String, val finderSize: Int, val read: String, val readNew: String)

object Tiers {
    val FAST = Tier("fast", "Fast", "det384", 384, "ocrFast", "ocrFast")
    val BALANCED = Tier("balanced", "Balanced", "det384", 384, "ocrFast", "ocrAcc")
    val SHARP = Tier("sharp", "Sharp", "det640", 640, "ocrFast", "ocrAcc")
    val ORDER = listOf(FAST, BALANCED, SHARP)
    operator fun get(key: String): Tier = ORDER.firstOrNull { it.key == key } ?: FAST
}

/**
 * Live quality controller (PlateSight's adaptive.js): sharper models when frames are fast, back to
 * faster ones when they're slow (a busy or hot phone). Upgrades need sustained headroom; downgrades
 * react quickly; a tier that proved too slow waits longer each time before it's tried again.
 */
class Adaptive(
    var mode: String = "auto",
    var budget: Double = 70.0,
    private val upAfter: Double = 3000.0,
    private val downAfter: Double = 1200.0,
    private val cooldown: Double = 2000.0,
    private val retryAfter: Double = 30000.0,
) {
    var tier: Tier = if (mode == "auto") Tiers.FAST else Tiers[mode]
        private set
    var reason: String = if (mode == "auto") "starting fast" else "fixed in settings"
        private set
    private var ema = 0.0
    private var since = 0.0
    private var fastSince: Double? = null
    private var slowSince: Double? = null
    private val blocked = HashMap<String, Double>()
    private val fails = HashMap<String, Int>()

    class Change(val from: Tier, val to: Tier, val reason: String)

    fun reset(t: Double) {
        ema = 0.0
        since = t
        fastSince = null
        slowSince = null
    }

    fun setMode(m: String, t: Double = 0.0) {
        mode = m
        if (m != "auto") {
            tier = Tiers[m]
            reason = "fixed in settings"
        }
        reset(t)
    }

    /** Feed one analysed frame's time (ms) at time t (ms). Returns a change of tier, if any. */
    fun observe(ms: Double, t: Double): Change? {
        if (mode != "auto") return null
        ema = if (ema == 0.0) ms else ema * 0.8 + ms * 0.2
        val i = Tiers.ORDER.indexOf(tier)
        val settled = t - since >= cooldown
        val slow = ema > budget * 1.15
        slowSince = if (slow) (slowSince ?: t) else null
        if (slow && i > 0 && settled && t - slowSince!! >= downAfter) {
            val n = (fails[tier.key] ?: 0) + 1
            fails[tier.key] = n
            blocked[tier.key] = t + retryAfter * (1 shl minOf(4, n - 1))
            return move(i - 1, t, "${ema.toInt()} ms per frame is too slow")
        }
        val fast = ema < budget * 0.55
        fastSince = if (fast) (fastSince ?: t) else null
        val next = Tiers.ORDER.getOrNull(i + 1)
        if (fast && next != null && (blocked[next.key] ?: 0.0) <= t && settled && t - fastSince!! >= upAfter) {
            return move(i + 1, t, "headroom (${ema.toInt()} ms per frame)")
        }
        return null
    }

    private fun move(i: Int, t: Double, why: String): Change {
        val from = tier
        tier = Tiers.ORDER[i]
        reason = why
        reset(t)
        return Change(from, tier, why)
    }
}

/** Idle mode: after `idleMs` without anything in view, analyse only every `idleFrameMs`. */
class Idle(private val idleMs: Double = 2500.0, val idleFrameMs: Double = 250.0) {
    private var lastSeen: Double? = null

    fun observe(found: Int, t: Double): Boolean {
        if (found > 0 || lastSeen == null) lastSeen = t
        return isIdle(t)
    }

    fun isIdle(t: Double): Boolean = lastSeen?.let { t - it > idleMs } ?: false

    fun wake(t: Double) {
        lastSeen = t
    }
}
