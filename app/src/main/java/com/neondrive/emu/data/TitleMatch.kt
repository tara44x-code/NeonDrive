package com.neondrive.emu.data

/**
 * Fuzzy title matching between No-Intro names, GoodTools file names
 * ("Earthworm Jim (U) [!].bin") and names read from the ROM header ("EARTHWORM JIM").
 */
object TitleMatch {
    private val brackets = Regex("""\s*[(\[][^)\]]*[)\]]""")
    private val roman = mapOf("ii" to "2", "iii" to "3", "iv" to "4", "v" to "5", "vi" to "6")
    private val goodRegions = mapOf('U' to "usa", 'E' to "europe", 'J' to "japan", 'W' to "world", 'B' to "brazil", 'K' to "korea")

    /** "Earthworm Jim (USA) [!].md" -> "Earthworm Jim" */
    fun base(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        val noExt = if (ext in knownExt) name.substringBeforeLast('.') else name // keep "Dr. Robotnik"
        return noExt.replace(brackets, "").replace('_', ' ').trim()
    }

    private val knownExt = GameSystem.allExtensions + GameSystem.archiveExtensions + setOf("cht", "7z")

    /** Normalized comparison key: "The Story of Thor II" -> "story of thor 2" */
    fun norm(name: String): String {
        val s = base(name).lowercase()
            .replace("&", " and ")
            .replace(Regex("""^the\s+|,\s*the\b"""), " ")
            .replace(Regex("""[^a-z0-9]+"""), " ")
        return s.split(' ').filter { it.isNotEmpty() }.joinToString(" ") { roman[it] ?: it }
    }

    /** Region / variant tags, with GoodTools codes expanded: "(U) [!]" -> {usa}. */
    fun tags(name: String): Set<String> {
        val out = mutableSetOf<String>()
        Regex("""\(([^)]*)\)""").findAll(name).forEach { m ->
            val inner = m.groupValues[1].trim()
            if (inner.matches(Regex("[JUEWBK]{1,4}"))) inner.forEach { c -> goodRegions[c]?.let(out::add) }
            else inner.split(',').forEach { out += it.trim().lowercase() }
        }
        return out
    }

    /** Sørensen–Dice similarity on character bigrams (0..1). */
    fun similarity(a: String, b: String): Double {
        val x = a.replace(" ", ""); val y = b.replace(" ", "")
        if (x == y) return 1.0
        if (x.length < 2 || y.length < 2) return 0.0
        val bx = x.windowed(2).groupingBy { it }.eachCount().toMutableMap()
        var hits = 0
        y.windowed(2).forEach { g -> val c = bx[g] ?: 0; if (c > 0) { hits++; bx[g] = c - 1 } }
        return 2.0 * hits / (x.length - 1 + y.length - 1)
    }

    /**
     * Picks the best names from [pool] for any of the [candidates].
     * Exact normalized matches win (all variants returned, e.g. "(USA)", "(Action Replay)", "(Rumbles)");
     * otherwise the closest name above [threshold] and its siblings.
     */
    fun bestMatches(candidates: List<String>, pool: List<String>, preferTags: Set<String>, threshold: Double = 0.82): List<String> {
        val keys = candidates.map(::norm).filter { it.length >= 2 }.distinct()
        if (keys.isEmpty() || pool.isEmpty()) return emptyList()
        val normed = pool.map { it to norm(it) }

        var hits = normed.filter { (_, n) -> n in keys }
        if (hits.isEmpty()) {
            val (best, score) = normed.map { (name, n) -> name to keys.maxOf { similarity(n, it) } }
                .maxByOrNull { it.second } ?: return emptyList()
            if (score < threshold) return emptyList()
            val bestNorm = norm(best)
            hits = normed.filter { it.second == bestNorm }
        }
        return hits.map { it.first }
            .sortedByDescending { name -> tags(name).count { it in preferTags } }
    }
}
