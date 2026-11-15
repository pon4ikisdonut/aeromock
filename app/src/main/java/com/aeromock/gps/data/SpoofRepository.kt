package com.aeromock.gps.data

import android.app.AppOpsManager
import android.content.Context
import android.os.Process
import com.aeromock.gps.model.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import kotlin.math.*

object SpoofStateHolder {
    private val _state = MutableStateFlow(SpoofState())
    val state: StateFlow<SpoofState> = _state.asStateFlow()

    private val _settings = MutableStateFlow(SpoofSettings())
    val settings: StateFlow<SpoofSettings> = _settings.asStateFlow()

    private val _joystickVector = MutableStateFlow(Pair(0f, 0f))
    val joystickVector: StateFlow<Pair<Float, Float>> = _joystickVector.asStateFlow()

    fun updateState(transform: (SpoofState) -> SpoofState) {
        _state.value = transform(_state.value)
    }

    fun updateSettings(newSettings: SpoofSettings) {
        _settings.value = newSettings
    }

    fun setJoystickVector(dx: Float, dy: Float) {
        _joystickVector.value = Pair(dx, dy)
    }
}

class SpoofRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("aeromock_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun isMockLocationSelected(): Boolean {
        return runCatching {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_MOCK_LOCATION,
                Process.myUid(),
                context.packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }

    fun loadFavorites(): List<FavoriteLocation> {
        val json = prefs.getString("favorites", null) ?: return defaultFavorites()
        val type = object : TypeToken<List<FavoriteLocation>>() {}.type
        return runCatching { gson.fromJson<List<FavoriteLocation>>(json, type) }.getOrDefault(defaultFavorites())
    }

    fun saveFavorites(list: List<FavoriteLocation>) {
        prefs.edit().putString("favorites", gson.toJson(list)).apply()
    }

    fun loadRoutes(): List<SavedRoute> {
        val json = prefs.getString("routes", null) ?: return emptyList()
        val type = object : TypeToken<List<SavedRoute>>() {}.type
        return runCatching { gson.fromJson<List<SavedRoute>>(json, type) }.getOrDefault(emptyList())
    }

    fun saveRoutes(list: List<SavedRoute>) {
        prefs.edit().putString("routes", gson.toJson(list)).apply()
    }

    fun loadSettings(): SpoofSettings {
        val json = prefs.getString("settings", null) ?: return SpoofSettings()
        return runCatching { gson.fromJson(json, SpoofSettings::class.java) }.getOrDefault(SpoofSettings())
    }

    fun saveSettings(settings: SpoofSettings) {
        prefs.edit().putString("settings", gson.toJson(settings)).apply()
        SpoofStateHolder.updateSettings(settings)
    }

    fun parseGpx(inputStream: InputStream): List<GeoPointData> {
        val points = mutableListOf<GeoPointData>()
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(inputStream, null)
        var eventType = parser.eventType
        var currentLat: Double? = null
        var currentLon: Double? = null
        var currentEle = 45.0
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "trkpt" || parser.name == "rtept" || parser.name == "wpt") {
                        currentLat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                        currentLon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                    } else if (parser.name == "ele") {
                        currentEle = parser.nextText().toDoubleOrNull() ?: 45.0
                    }
                }
                XmlPullParser.END_TAG -> {
                    if ((parser.name == "trkpt" || parser.name == "rtept" || parser.name == "wpt")
                        && currentLat != null && currentLon != null
                    ) {
                        points.add(GeoPointData(currentLat, currentLon, currentEle))
                        currentLat = null
                        currentLon = null
                    }
                }
            }
            eventType = parser.next()
        }
        return points
    }

    private fun defaultFavorites() = listOf(
        FavoriteLocation(name = "Stockholm (KTH)", category = "Study", latitude = 59.3498, longitude = 18.0707),
        FavoriteLocation(name = "Tokyo Shibuya", category = "Travel", latitude = 35.6595, longitude = 139.7004),
        FavoriteLocation(name = "New York Times Sq", category = "Travel", latitude = 40.7580, longitude = -73.9855),
        FavoriteLocation(name = "London Big Ben", category = "Travel", latitude = 51.5007, longitude = -0.1246)
    )
}

object GeoMath {
    private const val EARTH_RADIUS_M = 6371000.0

    fun moveByMeters(lat: Double, lon: Double, dNorthMeters: Double, dEastMeters: Double): Pair<Double, Double> {
        val dLat = dNorthMeters / EARTH_RADIUS_M
        val dLon = dEastMeters / (EARTH_RADIUS_M * cos(Math.toRadians(lat)))
        return Pair(
            (lat + Math.toDegrees(dLat)).coerceIn(-85.0, 85.0),
            ((lon + Math.toDegrees(dLon) + 540.0) % 360.0) - 180.0
        )
    }

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val dLat = rLat2 - rLat1
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(rLat1) * cos(rLat2) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a))
    }

    fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(rLat2)
        val x = cos(rLat1) * sin(rLat2) - sin(rLat1) * cos(rLat2) * cos(dLon)
        return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
    }
}
