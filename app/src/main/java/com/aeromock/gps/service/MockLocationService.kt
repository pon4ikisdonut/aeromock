package com.aeromock.gps.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.aeromock.gps.AeroMockApp
import com.aeromock.gps.MainActivity
import com.aeromock.gps.data.GeoMath
import com.aeromock.gps.data.SpoofStateHolder
import com.aeromock.gps.model.GeoPointData
import com.aeromock.gps.model.LoopMode
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.*
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

class MockLocationService : Service() {

    companion object {
        const val ACTION_START = "com.aeromock.gps.ACTION_START"
        const val ACTION_STOP = "com.aeromock.gps.ACTION_STOP"
        const val ACTION_PAUSE_TOGGLE = "com.aeromock.gps.ACTION_PAUSE_TOGGLE"
        const val ACTION_TELEPORT = "com.aeromock.gps.ACTION_TELEPORT"
        const val ACTION_START_ROUTE = "com.aeromock.gps.ACTION_START_ROUTE"
        const val ACTION_STOP_ROUTE = "com.aeromock.gps.ACTION_STOP_ROUTE"

        const val EXTRA_LAT = "extra_lat"
        const val EXTRA_LON = "extra_lon"
        const val EXTRA_ROUTE_NAME = "extra_route_name"
        const val EXTRA_ROUTE_LATS = "extra_route_lats"
        const val EXTRA_ROUTE_LONS = "extra_route_lons"
        const val EXTRA_LOOP_MODE = "extra_loop_mode"
        const val EXTRA_SPEED_KMH = "extra_speed_kmh"

        private const val NOTIF_ID = 1001
    }

    private lateinit var locationManager: LocationManager
    private lateinit var fusedClient: FusedLocationProviderClient
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var mockJob: Job? = null

    private var routePoints: List<GeoPointData> = emptyList()
    private var routeIndex = 0
    private var routeDirection = 1
    private var routeLoopMode = LoopMode.LOOP
    private var routeSpeedKmh = 15f

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val lat = intent.getDoubleExtra(EXTRA_LAT, SpoofStateHolder.state.value.currentLat)
                val lon = intent.getDoubleExtra(EXTRA_LON, SpoofStateHolder.state.value.currentLon)
                startSpoofing(lat, lon)
            }
            ACTION_TELEPORT -> {
                val lat = intent.getDoubleExtra(EXTRA_LAT, SpoofStateHolder.state.value.currentLat)
                val lon = intent.getDoubleExtra(EXTRA_LON, SpoofStateHolder.state.value.currentLon)
                SpoofStateHolder.updateState {
                    it.copy(currentLat = lat, currentLon = lon, isRoutePlaying = false, activeRouteName = null)
                }
                if (!SpoofStateHolder.state.value.isRunning) {
                    startSpoofing(lat, lon)
                }
            }
            ACTION_START_ROUTE -> {
                val lats = intent.getDoubleArrayExtra(EXTRA_ROUTE_LATS) ?: doubleArrayOf()
                val lons = intent.getDoubleArrayExtra(EXTRA_ROUTE_LONS) ?: doubleArrayOf()
                val name = intent.getStringExtra(EXTRA_ROUTE_NAME) ?: "Route"
                val modeName = intent.getStringExtra(EXTRA_LOOP_MODE) ?: LoopMode.LOOP.name
                routeLoopMode = LoopMode.valueOf(modeName)
                routeSpeedKmh = intent.getFloatExtra(EXTRA_SPEED_KMH, 15f)
                routePoints = lats.indices.map { GeoPointData(lats[it], lons[it]) }
                routeIndex = 0
                routeDirection = 1
                if (routePoints.size >= 2) {
                    SpoofStateHolder.updateState {
                        it.copy(
                            currentLat = routePoints[0].latitude,
                            currentLon = routePoints[0].longitude,
                            isRoutePlaying = true,
                            activeRouteName = name,
                            routeProgress = 0f
                        )
                    }
                    if (!SpoofStateHolder.state.value.isRunning) {
                        startSpoofing(routePoints[0].latitude, routePoints[0].longitude)
                    }
                }
            }
            ACTION_STOP_ROUTE -> {
                routePoints = emptyList()
                SpoofStateHolder.updateState { it.copy(isRoutePlaying = false, activeRouteName = null, routeProgress = 0f) }
            }
            ACTION_PAUSE_TOGGLE -> {
                SpoofStateHolder.updateState { it.copy(isPaused = !it.isPaused) }
                updateNotification()
            }
            ACTION_STOP -> {
                stopSpoofing()
            }
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun setupTestProviders() {
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        for (provider in providers) {
            runCatching { locationManager.removeTestProvider(provider) }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    locationManager.addTestProvider(
                        provider,
                        false, false, false, false,
                        true, true, true,
                        ProviderProperties.POWER_USAGE_LOW,
                        ProviderProperties.ACCURACY_FINE
                    )
                } else {
                    @Suppress("DEPRECATION")
                    locationManager.addTestProvider(
                        provider,
                        false, false, false, false,
                        true, true, true, 1, 1
                    )
                }
                locationManager.setTestProviderEnabled(provider, true)
            }
        }
        runCatching { fusedClient.setMockMode(true) }
    }

    private fun startSpoofing(lat: Double, lon: Double) {
        setupTestProviders()
        SpoofStateHolder.updateState {
            it.copy(isRunning = true, isPaused = false, currentLat = lat, currentLon = lon)
        }
        startForeground(NOTIF_ID, buildNotification())

        mockJob?.cancel()
        mockJob = serviceScope.launch {
            while (isActive) {
                val state = SpoofStateHolder.state.value
                val settings = SpoofStateHolder.settings.value
                val dtSec = settings.updateIntervalMs / 1000.0

                if (!state.isPaused) {
                    var nextLat = state.currentLat
                    var nextLon = state.currentLon
                    var bearing = state.currentBearing
                    var speedMps = 0f

                    // 1. Joystick movement
                    val (jx, jy) = SpoofStateHolder.joystickVector.value
                    val mag = hypot(jx, jy)
                    if (mag > 0.08f) {
                        val maxSpeedMps = settings.speedKmh / 3.6
                        val actualSpeed = maxSpeedMps * mag.coerceAtMost(1f)
                        speedMps = actualSpeed.toFloat()
                        val dEast = (jx / mag) * actualSpeed * dtSec
                        val dNorth = (-jy / mag) * actualSpeed * dtSec // screen Y is down
                        val moved = GeoMath.moveByMeters(nextLat, nextLon, dNorth, dEast)
                        nextLat = moved.first
                        nextLon = moved.second
                        bearing = ((Math.toDegrees(atan2(dEast, dNorth)) + 360.0) % 360.0).toFloat()
                    } else if (state.isRoutePlaying && routePoints.size >= 2) {
                        // 2. Route simulation
                        val target = routePoints.getOrNull(routeIndex + routeDirection)
                        if (target != null) {
                            val dist = GeoMath.distanceMeters(nextLat, nextLon, target.latitude, target.longitude)
                            val stepMeters = (routeSpeedKmh / 3.6) * dtSec
                            speedMps = (routeSpeedKmh / 3.6f)
                            bearing = GeoMath.bearingDegrees(nextLat, nextLon, target.latitude, target.longitude)
                            if (dist <= stepMeters) {
                                nextLat = target.latitude
                                nextLon = target.longitude
                                routeIndex += routeDirection
                                if (routeIndex <= 0 || routeIndex >= routePoints.lastIndex) {
                                    when (routeLoopMode) {
                                        LoopMode.ONCE -> {
                                            SpoofStateHolder.updateState {
                                                it.copy(isRoutePlaying = false, routeProgress = 1f)
                                            }
                                        }
                                        LoopMode.LOOP -> {
                                            routeIndex = 0
                                            nextLat = routePoints[0].latitude
                                            nextLon = routePoints[0].longitude
                                        }
                                        LoopMode.PING_PONG -> {
                                            routeDirection *= -1
                                        }
                                    }
                                }
                            } else {
                                val ratio = stepMeters / dist
                                nextLat += (target.latitude - nextLat) * ratio
                                nextLon += (target.longitude - nextLon) * ratio
                            }
                            val progress = routeIndex.toFloat() / (routePoints.lastIndex.coerceAtLeast(1))
                            SpoofStateHolder.updateState { it.copy(routeProgress = progress) }
                        }
                    }

                    SpoofStateHolder.updateState {
                        it.copy(
                            currentLat = nextLat,
                            currentLon = nextLon,
                            currentBearing = bearing,
                            currentSpeedMps = speedMps
                        )
                    }

                    // 3. Apply subtle drift if enabled
                    var injectLat = nextLat
                    var injectLon = nextLon
                    if (settings.enableDrift && settings.driftRadiusMeters > 0f) {
                        val angle = Random.nextDouble(0.0, 2 * Math.PI)
                        val r = Random.nextDouble(0.0, settings.driftRadiusMeters.toDouble())
                        val drifted = GeoMath.moveByMeters(nextLat, nextLon, r * cos(angle), r * sin(angle))
                        injectLat = drifted.first
                        injectLon = drifted.second
                    }

                    injectLocation(injectLat, injectLon, settings.altitudeMeters, settings.accuracyMeters, bearing, speedMps)
                    updateNotification()
                }
                delay(settings.updateIntervalMs.coerceIn(100L, 3000L))
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun injectLocation(
        lat: Double,
        lon: Double,
        alt: Double,
        accuracy: Float,
        bearing: Float,
        speed: Float
    ) {
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtimeNanos()
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            val loc = Location(provider).apply {
                latitude = lat
                longitude = lon
                altitude = alt
                this.accuracy = accuracy
                this.bearing = bearing
                this.speed = speed
                time = now
                elapsedRealtimeNanos = elapsed
            }
            runCatching { locationManager.setTestProviderLocation(provider, loc) }
        }
        val fusedLoc = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = lat
            longitude = lon
            altitude = alt
            this.accuracy = accuracy
            this.bearing = bearing
            this.speed = speed
            time = now
            elapsedRealtimeNanos = elapsed
        }
        runCatching { fusedClient.setMockLocation(fusedLoc) }
    }

    @SuppressLint("MissingPermission")
    private fun stopSpoofing() {
        mockJob?.cancel()
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            runCatching { locationManager.removeTestProvider(provider) }
        }
        runCatching { fusedClient.setMockMode(false) }
        SpoofStateHolder.updateState {
            it.copy(isRunning = false, isPaused = false, isRoutePlaying = false, currentSpeedMps = 0f)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val state = SpoofStateHolder.state.value
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val pauseIntent = PendingIntent.getService(
            this, 1,
            Intent(this, MockLocationService::class.java).setAction(ACTION_PAUSE_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 2,
            Intent(this, MockLocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val status = if (state.isPaused) "На паузе" else "Активен"
        val coords = String.format("%.5f, %.5f", state.currentLat, state.currentLon)
        val speedKmh = (state.currentSpeedMps * 3.6f).toInt()

        return NotificationCompat.Builder(this, AeroMockApp.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("AeroMock GPS • $status")
            .setContentText("$coords • $speedKmh км/ч")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                if (state.isPaused) "Продолжить" else "Пауза",
                pauseIntent
            )
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Стоп", stopIntent)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildNotification())
    }

    override fun onDestroy() {
        mockJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
