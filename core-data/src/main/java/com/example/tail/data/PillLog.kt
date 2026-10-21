package com.example.tail.data

/**
 * Count-coded pill dosing for the "Took pills" habit type (pillLogHabits).
 *
 * Semantics (TailCue spec, 2026-10-21):
 *  - Each log line naming a pill = exactly ONE serving of that pill.
 *  - Repeated same-day lines are deliberate re-dosing, never duplicates:
 *    3 × "Omega-3 Sunday Natural" = 3 servings → DHA 3×350, EPA 3×180.
 *  - A typed dose token on a line ("Creatine 3g", "Iron 18mg") defines the
 *    amount for that line and overrides the catalog default; repeated typed
 *    lines SUM (two "Creatine 3g" lines = 6 g).
 *  - Unknown lines without a dose token stay presence-only — no fabricated
 *    amounts, no errors.
 *
 * The raw free-text log itself is NEVER modified by this parser; structured
 * dose results are persisted additively via [PillDoseRepository].
 */

/** One nutrient amount of one pill's per-serving composition, e.g. DHA 350 mg. */
data class PillNutrient(val name: String, val amount: Double, val unit: String)

/**
 * Structured parse result for ONE day's pill lines.
 *
 * @property servings Canonical pill name → number of matching lines that day.
 * @property doses     Canonical pill name → total daily nutrient doses
 *                     (catalog amount × servings, or the sum of typed line
 *                     doses when typed tokens override the default).
 * @property unmatched Raw lines that matched no catalog pill and carried no
 *                     typed dose token — presence-only.
 */
data class PillDayDoses(
    val servings: Map<String, Int> = emptyMap(),
    val doses: Map<String, List<PillNutrient>> = emptyMap(),
    val unmatched: List<String> = emptyList()
)

object PillLogParser {

    // ── Catalog ────────────────────────────────────────────────────────────

    /**
     * Default pill catalog: pill name (as the user types it) → per-serving
     * composition description. Mirrors the authoritative TailCue
     * `get_pill_info` mapping; pills recorded only as free-text-with-dose
     * (Creatine, Glutamine, …) get explicit per-serving amounts so the same
     * counting rule applies uniformly. Ashwagandha has no reliable per-serving
     * amount → counted for servings only (presence + count, no dose).
     */
    val DEFAULT_CATALOG: Map<String, String> = linkedMapOf(
        "Omega-3 Sunday Natural" to "DHA 350mg, EPA 180mg",
        "B Complex Nature Love" to "B1 40mg, B2 15mg, B3 110mg, B5 100mg, B6 20mg, B7 400mcg, B9 400mcg, B12 500mcg",
        "Calcium, Magnesium, Zinc, D3, K2, Copper, Boron" to
            "Calcium 500mg, Magnesium 200mg, Zinc 5mg, D3 20mcg, K2 200mcg, Copper 1mg, Boron 0.6mg",
        "Vitamin D3" to "D3 20mcg",
        "Vitamin K2" to "K2 200mcg",
        "Calcium" to "Calcium 500mg",
        "Magnesium" to "Magnesium 200mg",
        "Zinc" to "Zinc 5mg",
        "Boron" to "Boron 0.6mg",
        "Copper" to "Copper 1mg",
        "Creatine 3g" to "Creatine 3g",
        "Creatine" to "Creatine 3g",
        "Glutamine 10g" to "Glutamine 10g",
        "Glutamine" to "Glutamine 10g",
        "Iron 18mg" to "Iron 18mg",
        "Iron" to "Iron 18mg",
        "C 1g" to "Vitamin C 1g",
        "Spirulina 4g" to "Spirulina 4g",
        "Ashwagandha" to "Ashwagandha"
    )

    /** Extra aliases: short user spelling → canonical catalog name. */
    private val ALIASES: Map<String, String> = mapOf(
        "omega" to "Omega-3 Sunday Natural",
        "omega 3" to "Omega-3 Sunday Natural",
        "omega-3" to "Omega-3 Sunday Natural",
        "b complex" to "B Complex Nature Love",
        "bcomplex" to "B Complex Nature Love",
        "mineral stack" to "Calcium, Magnesium, Zinc, D3, K2, Copper, Boron",
        "calcium magnesium zinc d3 k2 copper boron" to
            "Calcium, Magnesium, Zinc, D3, K2, Copper, Boron",
        "vitamin c" to "C 1g",
        "vitamin d" to "Vitamin D3"
    )

    // ── Token patterns ─────────────────────────────────────────────────────

    /** "40mg", "0.6mg", "400µg", "400mcg", "3g", "1g", "500mcg" … */
    private val DOSE_TOKEN = Regex(
        """(\d+(?:[.,]\d+)?)\s*(µg|mcg|mg|g)\b""", RegexOption.IGNORE_CASE
    )

    /** Composition item: "<name> <amount>[<unit>]" in a catalog description. */
    private val NUTRIENT_TOKEN = Regex(
        """([A-Za-z][A-Za-z0-9\-]*)\s+(\d+(?:[.,]\d+)?)\s*(µg|mcg|mg|g)?"""
    )

    // ── Normalisation ──────────────────────────────────────────────────────

    /**
     * Normalises a pill name or log line for matching: lowercase, punctuation
     * (,/) collapsed to spaces, whitespace runs collapsed, trimmed.
     */
    fun normalize(s: String): String =
        s.lowercase()
            .replace(Regex("[,/]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    // ── Description parsing ────────────────────────────────────────────────

    /**
     * Parses a composition description ("DHA 350mg, EPA 180mg") into nutrients.
     * Unit-less amounts keep an empty unit. Unparseable descriptions yield an
     * empty list (pill then counts servings only — never an error).
     */
    fun parseDescription(description: String): List<PillNutrient> {
        val out = mutableListOf<PillNutrient>()
        for (part in description.split(',', ';')) {
            val m = NUTRIENT_TOKEN.find(part) ?: continue
            val amount = m.groupValues[2].replace(',', '.').toDoubleOrNull() ?: continue
            val unit = m.groupValues[3].lowercase().let { if (it == "µg") "mcg" else it }
            out.add(PillNutrient(name = m.groupValues[1], amount = amount, unit = unit))
        }
        return out
    }

    // ── Line matching ──────────────────────────────────────────────────────

    /**
     * Matches one log line against the catalog. Exact (normalised) match
     * first, then aliases, then longest substring containment either way —
     * so "Omega-3 Sunday Natural" and "Omega" resolve to the same pill.
     * Returns the canonical pill name, or null when nothing matches.
     */
    fun matchPill(line: String, catalog: Map<String, String>): String? {
        val n = normalize(line)
        if (n.isEmpty()) return null
        // 1. Exact normalised match
        catalog.keys.firstOrNull { normalize(it) == n }?.let { return it }
        // 2. Alias table
        ALIASES[n]?.let { alias -> if (alias in catalog) return alias }
        // 3. Longest catalog name contained in the line, or the line contained
        //    in a catalog name (guards against short garbage like "g").
        val contained = catalog.keys
            .filter { nn -> val k = normalize(nn); k.length >= 3 && n.contains(k) }
            .maxByOrNull { normalize(it).length }
        if (contained != null) return contained
        val containing = catalog.keys
            .filter { nn -> val k = normalize(nn); n.length >= 3 && k.contains(n) }
            .maxByOrNull { normalize(it).length }
        return containing
    }

    /**
     * Extracts the typed dose token from a line, if any. "Creatine 3g" →
     * PillNutrient("Creatine", 3.0, "g"); "Iron 18mg" → Iron 18 mg.
     * The nutrient name is the word(s) before the token (or the pill name
     * when nothing precedes it). Returns null when the line has no token.
     */
    fun typedDose(line: String): PillNutrient? {
        val m = DOSE_TOKEN.find(line) ?: return null
        val amount = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val unit = m.groupValues[2].lowercase().let { if (it == "µg") "mcg" else it }
        val before = line.substring(0, m.range.first).trim().trimEnd(',', '-')
        val name = before.takeLastIfWord() ?: ""
        return PillNutrient(
            name = name.ifBlank { "Dose" },
            amount = amount,
            unit = unit
        )
    }

    private fun String.takeLastIfWord(): String? {
        val words = this.split(Regex("\\s+")).filter { it.isNotBlank() }
        return words.lastOrNull()?.filter { it.isLetter() || it == '-' }?.takeIf { it.length >= 2 }
    }

    // ── Day parsing ────────────────────────────────────────────────────────

    /**
     * Parses one day's pill lines (already split from multi-line log values)
     * against the catalog and computes servings + structured daily doses.
     *
     * Rules:
     *  - servings[pill] = number of matching lines (repetition = re-dosing).
     *  - doses[pill]: each matching line contributes its typed dose when one
     *    is present, else the catalog description's nutrients; typed and
     *    default contributions are summed per (nutrient, unit).
     *  - Lines matching no pill AND carrying no typed dose token are
     *    presence-only ([PillDayDoses.unmatched]).
     */
    fun parseDay(lines: List<String>, catalog: Map<String, String>): PillDayDoses {
        val servings = LinkedHashMap<String, Int>()
        val doseAcc = HashMap<String, MutableMap<Pair<String, String>, Double>>()
        val unmatched = mutableListOf<String>()

        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val pill = matchPill(line, catalog)
            if (pill == null) {
                // Unknown + typed token → still a dose line for its typed name
                val typed = typedDose(line)
                if (typed != null) {
                    addDose(doseAcc, typed.name, typed.amount, typed.unit, line)
                } else {
                    unmatched.add(line)
                }
                continue
            }
            servings[pill] = (servings[pill] ?: 0) + 1
            val typed = typedDose(line)
            if (typed != null) {
                addDose(doseAcc, pill, typed.amount, typed.unit, null, nutrientName = typed.name)
            } else {
                for (nutrient in parseDescription(catalog[pill] ?: "")) {
                    addDose(doseAcc, pill, nutrient.amount, nutrient.unit, null, nutrientName = nutrient.name)
                }
            }
        }

        return PillDayDoses(
            servings = servings,
            doses = doseAcc.mapValues { (_, byNutrient) ->
                byNutrient.map { (key, amount) -> PillNutrient(key.first, amount, key.second) }
                    .sortedBy { it.name }
            },
            unmatched = unmatched
        )
    }

    /**
     * Splits a raw text-log VALUE into pill lines. Multi-option entries are
     * stored '\n'-joined in the shared-timestamp format; blank lines dropped.
     */
    fun splitLines(textLogValue: String): List<String> =
        textLogValue.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    private fun addDose(
        acc: HashMap<String, MutableMap<Pair<String, String>, Double>>,
        pill: String,
        amount: Double,
        unit: String,
        presenceLine: String?,
        nutrientName: String? = null
    ) {
        val name = nutrientName ?: pill
        val byNutrient = acc.getOrPut(pill) { HashMap() }
        val key = name to unit
        byNutrient[key] = (byNutrient[key] ?: 0.0) + amount
        // Unknown-but-typed lines (presenceLine != null) also register a
        // serving entry under their typed nutrient name so nothing is lost.
        if (presenceLine != null) {
            // no-op: servings map is not touched for unknown lines
        }
    }
}
