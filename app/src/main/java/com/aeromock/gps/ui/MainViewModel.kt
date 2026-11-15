package com.aeromock.gps.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aeromock.gps.data.SpoofRepository
import com.aeromock.gps.data.SpoofStateHolder
import com.aeromock.gps.model.*
import com.aeromock.gps.service.JoystickOverlayService
import com.aeromock.gps.service.MockLocationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = SpoofRepository(application)

    val spoofState = SpoofStateHolder.state
    val settings = SpoofStateHolder.settings

    private val _favorites = MutableStateFlow(repo.loadFavorites())
    val favorites: StateFlow<List<FavoriteLocation>> = _favorites.asStateFlow()

    private val _routes = MutableStateFlow(repo.loadRoutes())
    val routes: StateFlow<List<SavedRoute>> = _routes.asStateFlow()

    private val _draftRoutePoints = MutableStateFlow<List<GeoPointData>>(emptyList())
    val draftRoutePoints: StateFlow<List<GeoPointData>> = _draftRoutePoints.asStateFlow()

    private val _joystickEnabled = MutableStateFlow(false)
    val joystickEnabled: StateFlow<Boolean> = _joystickEnabled.asStateFlow()

    private val _searchStatus = MutableStateFlow<String?>(null)
    val searchStatus: StateFlow<String?> = _searchStatus.asStateFlow()

    init {
        SpoofStateHolder.updateSettings(repo.loadSettings())
    }

    fun teleportTo(lat: Double, lon: Double) {
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_TELEPORT
            putExtra(MockLocationService.EXTRA_LAT, lat)
            putExtra(MockLocationService.EXTRA_LON, lon)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent)
        } else {
            ctx.startService(intent)
        }
    }

    fun stopSpoofing() {
        val ctx = getApplication<Application>()
        ctx.startService(Intent(ctx, MockLocationService::class.java).setAction(MockLocationService.ACTION_STOP))
        if (_joystickEnabled.value) toggleJoystick(false)
    }

    fun togglePause() {
        val ctx = getApplication<Application>()
        ctx.startService(Intent(ctx, MockLocationService::class.java).setAction(MockLocationService.ACTION_PAUSE_TOGGLE))
    }

    fun toggleJoystick(enable: Boolean) {
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, JoystickOverlayService::class.java)
        if (enable) ctx.startService(intent) else ctx.stopService(intent)
        _joystickEnabled.value = enable
    }

    fun addFavorite(name: String, category: String, lat: Double, lon: Double) {
        val updated = listOf(FavoriteLocation(name = name, category = category, latitude = lat, longitude = lon)) + _favorites.value
        _favorites.value = updated
        repo.saveFavorites(updated)
    }

    fun removeFavorite(id: String) {
        val updated = _favorites.value.filterNot { it.id == id }
        _favorites.value = updated
        repo.saveFavorites(updated)
    }

    fun addDraftPoint(lat: Double, lon: Double) {
        _draftRoutePoints.value = _draftRoutePoints.value + GeoPointData(lat, lon)
    }

    fun clearDraftRoute() {
        _draftRoutePoints.value = emptyList()
    }

    fun saveDraftRoute(name: String, loopMode: LoopMode, speedKmh: Float) {
        if (_draftRoutePoints.value.size < 2) return
        val route = SavedRoute(name = name, points = _draftRoutePoints.value, loopMode = loopMode, speedKmh = speedKmh)
        val updated = listOf(route) + _routes.value
        _routes.value = updated
        repo.saveRoutes(updated)
        _draftRoutePoints.value = emptyList()
    }

    fun deleteRoute(id: String) {
        val updated = _routes.value.filterNot { it.id == id }
        _routes.value = updated
        repo.saveRoutes(updated)
    }

    fun playRoute(route: SavedRoute) {
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_START_ROUTE
            putExtra(MockLocationService.EXTRA_ROUTE_NAME, route.name)
            putExtra(MockLocationService.EXTRA_ROUTE_LATS, route.points.map { it.latitude }.toDoubleArray())
            putExtra(MockLocationService.EXTRA_ROUTE_LONS, route.points.map { it.longitude }.toDoubleArray())
            putExtra(MockLocationService.EXTRA_LOOP_MODE, route.loopMode.name)
            putExtra(MockLocationService.EXTRA_SPEED_KMH, route.speedKmh)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent)
        } else {
            ctx.startService(intent)
        }
    }

    fun stopRoute() {
        val ctx = getApplication<Application>()
        ctx.startService(Intent(ctx, MockLocationService::class.java).setAction(MockLocationService.ACTION_STOP_ROUTE))
    }

    fun importGpxUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { stream ->
                    val pts = repo.parseGpx(stream)
                    if (pts.size >= 2) {
                        val route = SavedRoute(name = "GPX Импорт (${pts.size} точек)", points = pts)
                        val updated = listOf(route) + _routes.value
                        _routes.value = updated
                        repo.saveRoutes(updated)
                        _searchStatus.value = "Импортирован GPX маршрут: ${pts.size} точек"
                    } else {
                        _searchStatus.value = "GPX файл содержит меньше 2 точек"
                    }
                }
            }.onFailure {
                _searchStatus.value = "Ошибка чтения GPX: ${it.localizedMessage}"
            }
        }
    }

    fun updateSettings(newSettings: SpoofSettings) {
        repo.saveSettings(newSettings)
    }

    fun searchLocation(query: String, onFound: (Double, Double) -> Unit) {
        val trimmed = query.trim()
        val coordParts = trimmed.split(",", " ").mapNotNull { it.trim().toDoubleOrNull() }
        if (coordParts.size == 2 && coordParts[0] in -90.0..90.0 && coordParts[1] in -180.0..180.0) {
            onFound(coordParts[0], coordParts[1])
            _searchStatus.value = null
            return
        }

        viewModelScope.launch {
            _searchStatus.value = "Поиск..."
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val encoded = URLEncoder.encode(trimmed, "UTF-8")
                    val url = URL("https://nominatim.openstreetmap.org/search?q=$encoded&format=json&limit=1")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        setRequestProperty("User-Agent", "AeroMockGPS/1.0")
                        connectTimeout = 5000
                        readTimeout = 5000
                    }
                    val body = conn.inputStream.bufferedReader().readText()
                    val arr = JSONArray(body)
                    if (arr.length() > 0) {
                        val obj = arr.getJSONObject(0)
                        Pair(obj.getDouble("lat"), obj.getDouble("lon"))
                    } else null
                }.getOrNull()
            }
            if (result != null) {
                _searchStatus.value = null
                onFound(result.first, result.second)
            } else {
                _searchStatus.value = "Ничего не найдено"
            }
        }
    }
}
