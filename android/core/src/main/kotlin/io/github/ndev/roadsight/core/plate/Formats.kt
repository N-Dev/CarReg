package io.github.ndev.roadsight.core.plate

/** A reading checked against a country's plate rules: display text, the plain key, and whether it's a valid plate. */
data class Validated(val text: String, val key: String, val valid: Boolean, val format: String)

/** What a plate says about the car: registration year and period, and the county or area. */
data class PlateInfo(
    val year: Int? = null,
    val period: String? = null,
    val county: String? = null,
    val countyGa: String? = null,
    val area: String? = null,
)

/** Plate text normalisation, per-country validation and decoding (Ireland, UK, Northern Ireland). */
object Formats {
    private val TO_DIGIT = mapOf('O' to '0', 'Q' to '0', 'D' to '0', 'I' to '1', 'L' to '1', 'Z' to '2', 'S' to '5', 'G' to '6', 'B' to '8')
    private val TO_LETTER = mapOf('0' to 'O', '1' to 'I', '2' to 'Z', '5' to 'S', '6' to 'G', '8' to 'B')

    fun clean(raw: String?): String = buildString {
        for (c in (raw ?: "").uppercase()) if (c in 'A'..'Z' || c in '0'..'9') append(c)
    }

    private fun fix(s: String, table: Map<Char, Char>) = buildString { for (c in s) append(table[c] ?: c) }
    private fun digits(s: String) = fix(s, TO_DIGIT)
    private fun letters(s: String) = fix(s, TO_LETTER)
    private fun isDigits(s: String) = s.isNotEmpty() && s.all { it in '0'..'9' }
    private fun isLetters(s: String) = s.isNotEmpty() && s.all { it in 'A'..'Z' }

    // ---------------------------------------------------------------- Ireland
    val IE_COUNTIES: Map<String, Pair<String, String>> = mapOf(
        "C" to ("Cork" to "Corcaigh"), "CE" to ("Clare" to "An Clár"), "CN" to ("Cavan" to "An Cabhán"),
        "CW" to ("Carlow" to "Ceatharlach"), "D" to ("Dublin" to "Baile Átha Cliath"), "DL" to ("Donegal" to "Dún na nGall"),
        "G" to ("Galway" to "Gaillimh"), "KE" to ("Kildare" to "Cill Dara"), "KK" to ("Kilkenny" to "Cill Chainnigh"),
        "KY" to ("Kerry" to "Ciarraí"), "L" to ("Limerick" to "Luimneach"), "LD" to ("Longford" to "An Longfort"),
        "LH" to ("Louth" to "Lú"), "LK" to ("Limerick" to "Luimneach"), "LM" to ("Leitrim" to "Liatroim"),
        "LS" to ("Laois" to "Laois"), "MH" to ("Meath" to "An Mhí"), "MN" to ("Monaghan" to "Muineachán"),
        "MO" to ("Mayo" to "Maigh Eo"), "OY" to ("Offaly" to "Uíbh Fhailí"), "RN" to ("Roscommon" to "Ros Comáin"),
        "SO" to ("Sligo" to "Sligeach"), "T" to ("Tipperary" to "Tiobraid Árann"), "TN" to ("Tipperary North" to "Tiobraid Árann"),
        "TS" to ("Tipperary South" to "Tiobraid Árann"), "W" to ("Waterford" to "Port Láirge"), "WD" to ("Waterford" to "Port Láirge"),
        "WH" to ("Westmeath" to "An Iarmhí"), "WX" to ("Wexford" to "Loch Garman"),
    )

    private class IeYear(val year: Int, val half: Int? = null)

    private fun ieYear(y: String): IeYear? {
        if (y.length == 2) {
            val n = y.toIntOrNull() ?: return null
            if (n >= 87) return IeYear(1900 + n)
            if (n <= 12) return IeYear(2000 + n)
            return null
        }
        if (y.length == 3 && (y[2] == '1' || y[2] == '2')) {
            val n = y.substring(0, 2).toIntOrNull() ?: return null
            if (n in 13..40) return IeYear(2000 + n, y[2] - '0')
        }
        return null
    }

    /** Characters changed by confusion fixes: fewer changes = a more plausible reading. */
    private fun changes(a: String, b: String): Int {
        var n = 0
        for (i in a.indices) if (i >= b.length || a[i] != b[i]) n++
        return n
    }

    private class Cand(val cost: Double, val text: String, val key: String, val format: String)

    /** The cheapest candidate; on a tie the first one found wins. */
    private fun cheapest(cands: List<Cand>): Cand? {
        var best: Cand? = null
        for (c in cands) if (best == null || c.cost < best.cost) best = c
        return best
    }

    fun validateIE(raw: String?): Validated {
        val s = clean(raw)
        val cands = ArrayList<Cand>()
        for (ylen in intArrayOf(3, 2)) {
            for (clen in intArrayOf(2, 1)) {
                if (s.length < ylen + clen + 1) continue
                val year = digits(s.substring(0, ylen))
                val county = letters(s.substring(ylen, ylen + clen))
                val num = digits(s.substring(ylen + clen))
                if (isDigits(year) && IE_COUNTIES.containsKey(county) && isDigits(num) && num.length <= 6 && num[0] != '0' && ieYear(year) != null) {
                    val key = year + county + num
                    cands.add(Cand(changes(s, key) + (if (ylen == 2) 0.2 else 0.0), "$year-$county-$num", key, "IE"))
                }
            }
        }
        val best = cheapest(cands) ?: return Validated(s, s, false, "IE")
        return Validated(best.text, best.key, true, "IE")
    }

    private val IE_KEY = Regex("^(\\d{2,3})([A-Z]{1,2})(\\d{1,6})$")

    fun decodeIE(key: String?): PlateInfo? {
        val m = IE_KEY.find(key ?: "") ?: return null
        val y = ieYear(m.groupValues[1]) ?: return null
        val c = IE_COUNTIES[m.groupValues[2]] ?: return null
        return PlateInfo(
            year = y.year,
            period = when (y.half) { 1 -> "Jan–Jun"; 2 -> "Jul–Dec"; else -> null },
            county = c.first,
            countyGa = c.second,
        )
    }

    // ---------------------------------------------------------------- United Kingdom
    val UK_AREAS: Map<Char, String> = mapOf(
        'A' to "Anglia", 'B' to "Birmingham", 'C' to "Cymru", 'D' to "Deeside", 'E' to "Essex", 'F' to "Forest & Fens",
        'G' to "Garden of England", 'H' to "Hampshire & Dorset", 'K' to "Milton Keynes", 'L' to "London",
        'M' to "Manchester & Merseyside", 'N' to "North", 'O' to "Oxford", 'P' to "Preston", 'R' to "Reading",
        'S' to "Scotland", 'V' to "Severn Valley", 'W' to "West of England", 'Y' to "Yorkshire",
    )

    private fun ukAgeOk(n: Int) = n in 2..40 || n in 51..90
    private val NI_CODE = Regex("^(I[A-Z]|[A-Z]Z)$")

    /** NI county codes are I? (IA, IB, IG, IJ, IL, IW) or ?Z; a 3-letter mark is serial letter + code. */
    private fun isNICode(l: String) = NI_CODE.matches(if (l.length == 3) l.substring(1) else l)

    private val GB_CURRENT = Regex("^[A-HJ-PR-Y]{2}\\d{2}[A-HJ-PR-Z]{3}$")
    private val GB_PREFIX = Regex("^([A-HJ-NPR-Y])(\\d{1,3})([A-Z]{3})$")
    private val GB_SUFFIX = Regex("^([A-Z]{3})(\\d{1,3})([A-HJ-NPR-Y])$")

    fun validateUK(raw: String?): Validated {
        val s = clean(raw)
        val cands = ArrayList<Cand>()
        // Current GB format (AB12 CDE): area letter + office letter (never I, Q, Z), age identifier, 3 random letters (never I, Q)
        if (s.length == 7) {
            val t = letters(s.substring(0, 2)) + digits(s.substring(2, 4)) + letters(s.substring(4))
            if (GB_CURRENT.matches(t) && UK_AREAS.containsKey(t[0]) && ukAgeOk(t.substring(2, 4).toInt())) {
                cands.add(Cand(changes(s, t).toDouble(), "${t.substring(0, 4)} ${t.substring(4)}", t, "UK"))
            }
        }
        // Northern Ireland: optional serial letter + 2-letter code (I? or ?Z), then up to 4 digits (ABZ 1234)
        for (k in intArrayOf(3, 2)) {
            if (s.length <= k || s.length > k + 4) continue
            val l = letters(s.substring(0, k))
            val n = digits(s.substring(k))
            if (isLetters(l) && isNICode(l) && isDigits(n) && n[0] != '0') {
                cands.add(Cand(changes(s, l + n) + 0.3, "$l $n", l + n, "NI"))
            }
        }
        // Older GB prefix (A123 BCD) and suffix (ABC 123D) registrations: rarer on the road, so they cost more.
        GB_PREFIX.find(s)?.let { m -> cands.add(Cand(1.5, "${m.groupValues[1]}${m.groupValues[2]} ${m.groupValues[3]}", s, "UK-prefix")) }
        GB_SUFFIX.find(s)?.let { m -> cands.add(Cand(1.5, "${m.groupValues[1]} ${m.groupValues[2]}${m.groupValues[3]}", s, "UK-suffix")) }
        val best = cheapest(cands) ?: return Validated(s, s, false, "UK")
        return Validated(best.text, best.key, true, best.format)
    }

    private val UK_KEY = Regex("^[A-Z]{2}\\d{2}[A-Z]{3}$")
    private val NI_KEY = Regex("^[A-Z]{2,3}\\d{1,4}$")

    fun decodeUK(key: String?): PlateInfo? {
        val k = key ?: return null
        if (UK_KEY.matches(k)) {
            val n = k.substring(2, 4).toInt()
            val march = n < 50
            return PlateInfo(
                year = 2000 + (if (march) n else n - 50),
                period = if (march) "Mar–Aug" else "Sep–Feb",
                area = UK_AREAS[k[0]],
            )
        }
        if (NI_KEY.matches(k) && isNICode(k.filter { !it.isDigit() })) return PlateInfo(area = "Northern Ireland")
        return null
    }

    // ---------------------------------------------------------------- Generic
    fun validateGeneric(raw: String?): Validated {
        val s = clean(raw)
        return Validated(s, s, s.length in 4..10 && s.any { it.isDigit() }, "ANY")
    }

    /** The validator for a format setting ("auto", "IE", "UK", "ANY") and a predicted country. */
    fun profileFor(setting: String?, region: String?): String {
        if (setting != null && setting != "auto") return if (setting == "IE" || setting == "UK" || setting == "ANY") setting else "ANY"
        return when (region) {
            "Ireland" -> "IE"
            "United Kingdom" -> "UK"
            else -> "ANY"
        }
    }

    fun validate(raw: String?, profile: String = "ANY"): Validated = when (profile) {
        "IE" -> validateIE(raw)
        "UK" -> validateUK(raw)
        else -> validateGeneric(raw)
    }

    fun decode(key: String?, profile: String?): PlateInfo? = when (profile) {
        "IE" -> decodeIE(key)
        "UK" -> decodeUK(key)
        else -> null
    }

    // ---------------------------------------------------------------- countries → flags
    private val ISO = mapOf(
        "Albania" to "AL", "Andorra" to "AD", "Argentina" to "AR", "Armenia" to "AM", "Australia" to "AU", "Austria" to "AT",
        "Azerbaijan" to "AZ", "Bahrain" to "BH", "Belarus" to "BY", "Belgium" to "BE", "Bosnia and Herzegovina" to "BA",
        "Brazil" to "BR", "Bulgaria" to "BG", "Cambodia" to "KH", "Canada" to "CA", "Croatia" to "HR", "Cyprus" to "CY",
        "Czech Republic" to "CZ", "Denmark" to "DK", "Estonia" to "EE", "Finland" to "FI", "France" to "FR", "Georgia" to "GE",
        "Germany" to "DE", "Gibraltar" to "GI", "Greece" to "GR", "Guernsey" to "GG", "Hungary" to "HU", "Iceland" to "IS",
        "Indonesia" to "ID", "Ireland" to "IE", "Israel" to "IL", "Italy" to "IT", "Latvia" to "LV", "Liechtenstein" to "LI",
        "Lithuania" to "LT", "Luxembourg" to "LU", "Malaysia" to "MY", "Malta" to "MT", "Mexico" to "MX", "Moldova" to "MD",
        "Monaco" to "MC", "Montenegro" to "ME", "Netherlands" to "NL", "New Zealand" to "NZ", "North Macedonia" to "MK",
        "Norway" to "NO", "Poland" to "PL", "Portugal" to "PT", "Qatar" to "QA", "Romania" to "RO", "San Marino" to "SM",
        "Serbia" to "RS", "Singapore" to "SG", "Slovakia" to "SK", "Slovenia" to "SI", "Spain" to "ES", "Sweden" to "SE",
        "Switzerland" to "CH", "Thailand" to "TH", "Turkey" to "TR", "United States" to "US", "Ukraine" to "UA",
        "United Kingdom" to "GB", "Vietnam" to "VN",
    )

    /** Code shown on the plate's blue band. */
    private val BAND = mapOf(
        "IE" to "IRL", "GB" to "UK", "DE" to "D", "FR" to "F", "IT" to "I", "ES" to "E", "NL" to "NL", "BE" to "B", "PT" to "P",
        "AT" to "A", "PL" to "PL", "CZ" to "CZ", "SE" to "S", "DK" to "DK", "FI" to "FIN", "NO" to "N", "CH" to "CH", "HU" to "H",
        "SK" to "SK", "SI" to "SLO", "HR" to "HR", "RO" to "RO", "BG" to "BG", "GR" to "GR", "LU" to "L", "LT" to "LT", "LV" to "LV",
        "EE" to "EST", "MT" to "M", "CY" to "CY",
    )

    fun isoFor(region: String?): String? = ISO[region]

    fun flagFor(region: String?): String {
        val iso = ISO[region] ?: return ""
        return buildString { for (c in iso) appendCodePoint(0x1F1E6 + (c - 'A')) }
    }

    fun bandFor(region: String?): String {
        val iso = ISO[region] ?: return ""
        return BAND[iso] ?: iso
    }
}
