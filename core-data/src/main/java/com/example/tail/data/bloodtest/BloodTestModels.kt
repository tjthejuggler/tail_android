package com.example.tail.data.bloodtest

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  BLOOD TEST HABIT TYPE — data models
 * ═══════════════════════════════════════════════════════════════════════
 *
 * One blood-test habit square is backed by a large item database: every
 * entry (one lab draw / pasted report) carries any number of measured
 * items. The habit's daily count in habitsdb.txt is the number of items
 * measured that day.
 */

/** Prefix for blood-test graph metric keys (`blood:<ItemName>`). */
const val BLOOD_METRIC_PREFIX = "blood:"

/** Synthetic metric key for the default "Items tested" daily-count series. */
const val BLOOD_METRIC_COUNT = "blood:__count";

/** True when [metricKey] is a blood-test metric (count or per-item). */
fun isBloodMetric(metricKey: String): Boolean = metricKey.startsWith(BLOOD_METRIC_PREFIX)

/** Human label for a blood metric key ("Items tested" or the item name). */
fun bloodMetricLabel(metricKey: String): String =
    if (metricKey == BLOOD_METRIC_COUNT) "Items tested"
    else metricKey.removePrefix(BLOOD_METRIC_PREFIX)

/** Builds the per-item metric key for [itemName]. */
fun bloodMetricKey(itemName: String): String = BLOOD_METRIC_PREFIX + itemName

/**
 * One measured value in a blood test entry. Any analyte qualifies —
 * glucose, ketones, vitamin D, ferritin, HbA1c, full-panel counts…
 */
data class BloodTestItem(
    /** Canonical item name, e.g. "Glucose", "Vitamin D", "HbA1c". */
    val name: String,
    /** Measured value. */
    val value: Double,
    /** Unit, e.g. "mg/dL", "mmol/L", "ng/mL" (optional). */
    val unit: String? = null,
    /** Reference range as printed on the report, e.g. "70-99" (optional). */
    val range: String? = null,
    /** Out-of-range flag from the report: "low" / "high" / "normal" (optional). */
    val flag: String? = null
)

/**
 * One blood test entry: a single draw/report on one date. Multiple entries
 * per day are allowed (morning glucose + evening panel); the day's item
 * count is the sum over all entries.
 */
data class BloodTestEntry(
    val id: String,
    /** "YYYY-MM-DD". */
    val date: String,
    /** "HH:mm" (optional). */
    val time: String? = null,
    /** How the items were entered: "manual" or "ai". */
    val source: String = "manual",
    /** Original text the AI parsed (AI entries only; kept for audit). */
    val rawText: String? = null,
    /** Free-form context note: fasting hours, diet, activity, etc. */
    val note: String? = null,
    val items: List<BloodTestItem> = emptyList()
)
