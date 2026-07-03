package org.cygnusx1.openbu.network

data class AmsTray(
    val id: String = "",
    val trayType: String = "",
    val trayColor: String = "",
    val trayInfoIdx: String = "",
    val remainPercent: Int = -1,
    val trayWeight: Int = 0,
) {
    val remainGrams: Int?
        get() = if (remainPercent in 0..100 && trayWeight > 0)
            remainPercent * trayWeight / 100
        else null
}

data class AmsUnit(
    val id: String = "",
    val model: String = "",
    val temp: String = "",
    val humidity: String = "",
    val trays: List<AmsTray> = emptyList(),
)

data class HmsError(val attr: Long, val code: Long) {
    val hmsCode: String get() = "%04X_%04X_%04X_%04X".format(
        attr ushr 16, attr and 0xFFFF,
        code ushr 16, code and 0xFFFF
    )
}

data class PrinterStatus(
    val gcodeState: String = "IDLE",
    val gcodeFile: String = "",
    val subtaskName: String = "",
    val mcPercent: Int = 0,
    val layerNum: Int = 0,
    val totalLayerNum: Int = 0,
    val mcRemainingTime: Int = 0,
    val nozzleTemper: Float = 0f,
    val nozzleTargetTemper: Float = 0f,
    val bedTemper: Float = 0f,
    val bedTargetTemper: Float = 0f,
    val chamberTemper: Float = 0f,
    // True when the connected "printer" is an openbu-mock instance (it labels itself
    // via a "mock" field in its status report). Used to skip auto-saving mocks.
    val isMock: Boolean = false,
    val heatbreakFanSpeed: Int = 0,
    val coolingFanSpeed: Int = 0,
    val bigFan1Speed: Int = 0,
    val bigFan2Speed: Int = 0,
    val amsUnits: List<AmsUnit> = emptyList(),
    val vtTray: AmsTray? = null,
    // Global index of the tray currently loaded into the toolhead, from ams.tray_now.
    // For AMS slots: amsUnitId * 4 + slotIndex. 254 = external spool, 255 = none loaded.
    val trayNow: String = "255",
    val spdLvl: Int = 2, // 2 = Normal = 100%
    val skippedObjects: List<Int> = emptyList(), // from s_obj
    val hmsErrors: List<HmsError> = emptyList(),
)

data class SavedPrinter(
    val ip: String,
    val serialNumber: String,
    val accessCode: String,
    val deviceName: String = "",
)

data class FtpFileEntry(
    val name: String,
    val size: Long,
    val lastModified: String,
    val isDirectory: Boolean,
)
