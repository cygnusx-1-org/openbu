package org.cygnusx1.openbu.data

enum class PrinterSeries(
    val isEnclosed: Boolean,
    val usesMjpegCamera: Boolean,
    val maxNozzleTemp: Int,
    val maxBedTemp: Int,
    val hasChamberTemp: Boolean,  // has an active chamber temperature sensor
    val wikiTag: String,  // tag used in hms-errors.json / Bambu wiki URLs
) {
    P1(isEnclosed = true,  usesMjpegCamera = true,  maxNozzleTemp = 300, maxBedTemp = 100, hasChamberTemp = false, wikiTag = "x1"),
    P2(isEnclosed = true,  usesMjpegCamera = false, maxNozzleTemp = 300, maxBedTemp = 110, hasChamberTemp = true,  wikiTag = "p2s"),
    A1(isEnclosed = false, usesMjpegCamera = true,  maxNozzleTemp = 300, maxBedTemp = 100, hasChamberTemp = false, wikiTag = "a1"),
    A1_MINI(isEnclosed = false, usesMjpegCamera = true,  maxNozzleTemp = 300, maxBedTemp = 80,  hasChamberTemp = false, wikiTag = "a1-mini"),
    H2(isEnclosed = true,  usesMjpegCamera = false, maxNozzleTemp = 350, maxBedTemp = 120, hasChamberTemp = true,  wikiTag = "h2"),
    X1(isEnclosed = true,  usesMjpegCamera = false, maxNozzleTemp = 300, maxBedTemp = 110, hasChamberTemp = true,  wikiTag = "x1"),
    X1E(isEnclosed = true, usesMjpegCamera = false, maxNozzleTemp = 320, maxBedTemp = 110, hasChamberTemp = true,  wikiTag = "x1"),
    UNKNOWN(isEnclosed = true, usesMjpegCamera = false, maxNozzleTemp = 300, maxBedTemp = 110, hasChamberTemp = false, wikiTag = ""),
}

private val SERIAL_PREFIX_MAP = mapOf(
    "01S" to PrinterSeries.P1,
    "01P" to PrinterSeries.P1,
    "22E" to PrinterSeries.P2,
    "030" to PrinterSeries.A1_MINI,
    "039" to PrinterSeries.A1,
    "31B" to PrinterSeries.H2,
    "094" to PrinterSeries.H2,
    "239" to PrinterSeries.H2,
    "093" to PrinterSeries.H2,
    "00M" to PrinterSeries.X1,
    "03W" to PrinterSeries.X1E,
)

fun printerSeriesFromSerial(serial: String): PrinterSeries =
    SERIAL_PREFIX_MAP.entries.firstOrNull { serial.startsWith(it.key) }?.value
        ?: PrinterSeries.UNKNOWN
