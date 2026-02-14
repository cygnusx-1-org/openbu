package org.cygnusx1.openbu.network

data class PrinterStatus(
    val gcodeState: String = "IDLE",
    val nozzleTemper: Float = 0f,
    val nozzleTargetTemper: Float = 0f,
    val bedTemper: Float = 0f,
    val bedTargetTemper: Float = 0f,
    val heatbreakFanSpeed: String = "0",
    val coolingFanSpeed: String = "0",
    val bigFan1Speed: String = "0",
    val amsTemp: String = "",
    val amsHumidity: String = "",
    val amsTrayType: String = "",
    val amsTrayColor: String = "",
)
