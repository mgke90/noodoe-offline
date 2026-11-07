package com.noodoe.offline.protocol

enum class DeviceType {
    VERSION_1_0,
    VERSION_1_5,
    VERSION_2_0,
    INVALID
}

data class DeviceInfo(
    val firmwareMajor: Int,
    val firmwareMinor: Int,
    val protocolMajor: Int,
    val protocolMinor: Int,
    val hardwareVersion: Int,
    val mac: String,
    val bootloaderMajor: Int,
    val bootloaderMinor: Int,
    val resourceMajor: Int,
    val resourceMinor: Int,
    val modelName: String,
    val bikeMaxSpeed: Int,
    val bikeSeriesNumber: String,
    val bikeType: Int,
    val pinCode: Int,
    val motorSeries: Int,
    val pcbaHardwareVersion: String,
    val defaultDashboardId: Int
) {
    val firmware: String get() = "$firmwareMajor.$firmwareMinor"
    val protocol: String get() = "$protocolMajor.$protocolMinor"

    val deviceType: DeviceType
        get() {
            val deprecated = protocolMajor == 0 && protocolMinor == 0 &&
                    hardwareVersion == 0 && firmwareMajor < 4
            return when {
                deprecated -> DeviceType.VERSION_1_0
                protocolMajor == 0 && protocolMinor == 0 -> DeviceType.VERSION_1_5
                protocolMajor >= 2 -> DeviceType.VERSION_2_0
                else -> DeviceType.INVALID
            }
        }
}

data class RidingStatus(
    val keyOn: Boolean,
    val odoKmRaw: Long,
    val maxSpeedKph: Int,
    val currentSpeedKph: Int,
    val stopDuration: Int
) {
    val odoKm: Double get() = odoKmRaw.toDouble()
}

data class DeviceFrame(
    val index: Int,
    val hasPayload: Boolean
)
