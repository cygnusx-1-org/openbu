package org.cygnusx1.openbu.network

data class AmsTray(
    val id: String = "",
    val trayType: String = "",
    val trayColor: String = "",
)

data class AmsUnit(
    val id: String = "",
    val model: String = "",
    val temp: String = "",
    val humidity: String = "",
    val trays: List<AmsTray> = emptyList(),
)

data class PrinterStatus(
    val gcodeState: String = "IDLE",
    val gcodeFile: String = "",
    val mcPercent: Int = 0,
    val layerNum: Int = 0,
    val totalLayerNum: Int = 0,
    val mcRemainingTime: Int = 0,
    val nozzleTemper: Float = 0f,
    val nozzleTargetTemper: Float = 0f,
    val bedTemper: Float = 0f,
    val bedTargetTemper: Float = 0f,
    val heatbreakFanSpeed: String = "0",
    val coolingFanSpeed: String = "0",
    val bigFan1Speed: String = "0",
    val amsUnits: List<AmsUnit> = emptyList(),
    val vtTray: AmsTray? = null,
)

data class SavedPrinter(
    val ip: String,
    val serialNumber: String,
    val accessCode: String,
    val deviceName: String = "",
)
