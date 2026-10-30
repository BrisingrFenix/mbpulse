package pt.ua.mbpulse.data.model

/**
 * A unit expressed in UCUM: a human-facing display [unit] (FHIR `Quantity.unit`)
 * and the case-sensitive machine [code] (FHIR `Quantity.code`, UCUM system
 * `http://unitsofmeasure.org`).
 */
internal data class UcumUnit(val unit: String, val code: String)

/**
 * Normalizes the free-text units that OMH bodies carry — per the openMHealth
 * unit value sets, e.g. "beats/min", "C", "μS" — to UCUM, the unit system FHIR
 * `Quantity` requires.
 *
 * Normalization happens here, at the FHIR boundary, **on purpose**: the OMH
 * records keep their OMH-conformant units (and the local UI keeps showing them),
 * while only the FHIR projection is UCUM. Rewriting the units at the source
 * ([pt.ua.mbpulse.collection.SignalProcessor] implementations) would
 * break OMH conformance and change what the history screens display.
 *
 * The schema terminology table ([OmhSchemas.terminologyFor]) is the primary,
 * authoritative source of UCUM codes per schema; this map is the fallback for
 * units not covered there (imported data, future schemas) and a single place to
 * validate the unit strings the bodies actually produce.
 */
internal object Ucum {

    private val MAP: Map<String, UcumUnit> = buildMap {
        // Heart rate — OMH "beats/min" → UCUM "/min" (display "beats/minute").
        putAll(listOf("beats/min", "beats/minute", "/min"), UcumUnit("beats/minute", "/min"))

        // Time / HRV — already UCUM.
        putAll(listOf("ms", "msec"), UcumUnit("ms", "ms"))

        // Temperature — OMH "C"/"F"/"K" → UCUM.
        putAll(listOf("C", "Cel", "°C", "celsius"), UcumUnit("Cel", "Cel"))
        putAll(listOf("F", "[degF]", "°F", "fahrenheit"), UcumUnit("[degF]", "[degF]"))
        putAll(listOf("K", "kelvin"), UcumUnit("K", "K"))

        // Electrodermal activity — microsiemens. Accept both the Greek mu (μ,
        // U+03BC) the processor emits and the micro sign (µ, U+00B5).
        putAll(listOf("μS", "µS", "uS", "microsiemens"), UcumUnit("uS", "uS"))

        // Common units of host-defined schemas (SchemaField.unit).
        putAll(listOf("breaths/min", "breaths/minute"), UcumUnit("breaths/minute", "/min"))
        putAll(listOf("mV", "millivolt"), UcumUnit("mV", "mV"))
        putAll(listOf("μV", "µV", "uV", "microvolt"), UcumUnit("uV", "uV"))
        putAll(listOf("%", "percent"), UcumUnit("%", "%"))
        putAll(listOf("s", "sec", "seconds"), UcumUnit("s", "s"))
        putAll(listOf("Hz", "hertz"), UcumUnit("Hz", "Hz"))
    }

    /** UCUM equivalent of an OMH/free-text unit string, or null if unmapped. */
    fun normalize(unit: String): UcumUnit? = MAP[unit.trim()]

    private fun MutableMap<String, UcumUnit>.putAll(keys: List<String>, value: UcumUnit) {
        keys.forEach { put(it, value) }
    }
}
