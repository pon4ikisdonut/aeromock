package com.aeromock.gps.model

import java.util.UUID

data class GeoPointData(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = 45.0
)

data class FavoriteLocation(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val category: String = "General",
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = 45.0,
    val createdAt: Long = System.currentTimeMillis()
)

data class SavedRoute(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val points: List<GeoPointData>,
    val loopMode: LoopMode = LoopMode.LOOP,
    val speedKmh: Float = 15f
)

enum class LoopMode(val label: String) {
    ONCE("Один раз"),
    LOOP("По кругу"),
    PING_PONG("Туда-обратно")
}

enum class SpeedPreset(val label: String, val kmh: Float) {
    WALK("Пешком", 5f),
    JOG("Бег", 11f),
    BIKE("Вело", 22f),
    CAR("Авто", 60f),
    FAST("Трасса", 110f)
}

data class SpoofSettings(
    val accuracyMeters: Float = 3.5f,
    val altitudeMeters: Double = 48.0,
    val updateIntervalMs: Long = 400L,
    val enableDrift: Boolean = true,
    val driftRadiusMeters: Float = 1.8f,
    val speedKmh: Float = 5f,
    val joystickSizeDp: Float = 136f,
    val joystickAlpha: Float = 0.85f
)

data class SpoofState(
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val isRoutePlaying: Boolean = false,
    val currentLat: Double = 59.3498,
    val currentLon: Double = 18.0707,
    val currentBearing: Float = 0f,
    val currentSpeedMps: Float = 0f,
    val routeProgress: Float = 0f,
    val activeRouteName: String? = null,
    val lastError: String? = null
)
