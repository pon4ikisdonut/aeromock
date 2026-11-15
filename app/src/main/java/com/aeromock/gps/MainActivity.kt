package com.aeromock.gps

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aeromock.gps.model.LoopMode
import com.aeromock.gps.model.SpeedPreset
import com.aeromock.gps.ui.MainViewModel
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                dynamicDarkColorScheme(LocalContext.current)
            } else {
                darkColorScheme()
            }
            MaterialTheme(colorScheme = colorScheme) {
                AeroMockRoot()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AeroMockRoot(vm: MainViewModel = viewModel()) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by vm.spoofState.collectAsState()
    val settings by vm.settings.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val routes by vm.routes.collectAsState()
    val draftPoints by vm.draftRoutePoints.collectAsState()
    val joystickOn by vm.joystickEnabled.collectAsState()
    val mockAllowed by vm.mockAllowed.collectAsState()
    val searchStatus by vm.searchStatus.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var searchText by remember { mutableStateOf("") }
    var pinLat by remember { mutableDoubleStateOf(state.currentLat) }
    var pinLon by remember { mutableDoubleStateOf(state.currentLon) }
    var cameraCenterTrigger by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var routeBuildMode by remember { mutableStateOf(false) }
    var showFavDialog by remember { mutableStateOf(false) }
    var showRouteDialog by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        vm.refreshMockPermissionStatus()
    }

    val gpxLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.importGpxUri(it) } }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                vm.refreshMockPermissionStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AeroMock GPS", fontWeight = FontWeight.Bold)
                        Text(
                            if (state.isRunning) {
                                val st = if (state.isPaused) "Пауза" else "Спуфинг активен"
                                "$st • ${String.format("%.5f, %.5f", state.currentLat, state.currentLon)}"
                            } else "Пин: ${String.format("%.5f, %.5f", pinLat, pinLon)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (state.isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (!Settings.canDrawOverlays(ctx)) {
                            ctx.startActivity(
                                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                            )
                        } else {
                            vm.toggleJoystick(!joystickOn)
                        }
                    }) {
                        Icon(
                            Icons.Default.Gamepad,
                            contentDescription = "Joystick",
                            tint = if (joystickOn) MaterialTheme.colorScheme.primary else LocalContentColor.current
                        )
                    }
                    IconButton(onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                    }) {
                        Icon(
                            Icons.Default.DeveloperMode,
                            contentDescription = "Dev Options",
                            tint = if (!mockAllowed) MaterialTheme.colorScheme.error else LocalContentColor.current
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = selectedTab == 0, onClick = { selectedTab = 0 }, icon = { Icon(Icons.Default.Map, null) }, label = { Text("Карта") })
                NavigationBarItem(selected = selectedTab == 1, onClick = { selectedTab = 1 }, icon = { Icon(Icons.Default.Route, null) }, label = { Text("Маршруты") })
                NavigationBarItem(selected = selectedTab == 2, onClick = { selectedTab = 2 }, icon = { Icon(Icons.Default.Star, null) }, label = { Text("Избранное") })
                NavigationBarItem(selected = selectedTab == 3, onClick = { selectedTab = 3 }, icon = { Icon(Icons.Default.Tune, null) }, label = { Text("Настройки") })
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (selectedTab) {
                0 -> {
                    Column(Modifier.fillMaxSize()) {
                        if (!mockAllowed || state.lastError != null) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = state.lastError
                                            ?: "Выбери AeroMock GPS в 'Для разработчиков -> Приложение для фиктивных местоположений'",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = {
                                        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                                    }) {
                                        Text("Открыть")
                                    }
                                }
                            }
                        }

                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = searchText,
                                onValueChange = { searchText = it },
                                placeholder = { Text("Координаты или город...") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            FilledTonalIconButton(onClick = {
                                vm.searchLocation(searchText) { lat, lon ->
                                    pinLat = lat
                                    pinLon = lon
                                    cameraCenterTrigger = Pair(lat, lon)
                                    vm.teleportTo(lat, lon)
                                }
                            }) {
                                Icon(Icons.Default.Search, contentDescription = "Search")
                            }
                        }

                        searchStatus?.let {
                            Text(it, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 14.dp))
                        }

                        Box(Modifier.weight(1f)) {
                            OsmMapContainer(
                                targetLat = if (state.isRunning) state.currentLat else pinLat,
                                targetLon = if (state.isRunning) state.currentLon else pinLon,
                                draftPoints = draftPoints.map { GeoPoint(it.latitude, it.longitude) },
                                cameraTrigger = cameraCenterTrigger,
                                onCameraMovedHandled = { cameraCenterTrigger = null },
                                onTap = { lat, lon ->
                                    if (routeBuildMode) {
                                        vm.addDraftPoint(lat, lon)
                                    } else {
                                        pinLat = lat
                                        pinLon = lon
                                    }
                                },
                                onLongPress = { lat, lon ->
                                    pinLat = lat
                                    pinLon = lon
                                    vm.teleportTo(lat, lon)
                                }
                            )

                            Column(
                                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                horizontalAlignment = Alignment.End
                            ) {
                                SmallFloatingActionButton(onClick = { showFavDialog = true }) {
                                    Icon(Icons.Default.BookmarkAdd, contentDescription = "Save Favorite")
                                }
                                SmallFloatingActionButton(onClick = { routeBuildMode = !routeBuildMode }) {
                                    Icon(
                                        if (routeBuildMode) Icons.Default.Done else Icons.Default.Timeline,
                                        contentDescription = "Route Mode"
                                    )
                                }
                                if (routeBuildMode && draftPoints.size >= 2) {
                                    ExtendedFloatingActionButton(
                                        onClick = { showRouteDialog = true },
                                        icon = { Icon(Icons.Default.Save, null) },
                                        text = { Text("Сохранить (${draftPoints.size})") }
                                    )
                                }
                                if (state.isRunning) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FloatingActionButton(onClick = { vm.togglePause() }) {
                                            Icon(if (state.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause, null)
                                        }
                                        FloatingActionButton(
                                            onClick = { vm.stopSpoofing() },
                                            containerColor = MaterialTheme.colorScheme.errorContainer
                                        ) {
                                            Icon(Icons.Default.Stop, null)
                                        }
                                    }
                                } else {
                                    ExtendedFloatingActionButton(
                                        onClick = { vm.teleportTo(pinLat, pinLon) },
                                        icon = { Icon(Icons.Default.MyLocation, null) },
                                        text = { Text("Телепорт") }
                                    )
                                }
                            }
                        }

                        Row(
                            Modifier.fillMaxWidth().padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            SpeedPreset.entries.forEach { preset ->
                                FilterChip(
                                    selected = settings.speedKmh == preset.kmh,
                                    onClick = { vm.updateSettings(settings.copy(speedKmh = preset.kmh)) },
                                    label = { Text(preset.label, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                    }
                }

                1 -> {
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Маршруты и GPX", style = MaterialTheme.typography.titleLarge)
                            Button(onClick = { gpxLauncher.launch(arrayOf("*/*")) }) {
                                Icon(Icons.Default.UploadFile, null)
                                Spacer(Modifier.width(6.dp))
                                Text("GPX")
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        if (state.isRoutePlaying) {
                            Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Играет: ${state.activeRouteName}", fontWeight = FontWeight.Bold)
                                        LinearProgressIndicator(progress = { state.routeProgress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                                    }
                                    IconButton(onClick = { vm.stopRoute() }) {
                                        Icon(Icons.Default.Stop, contentDescription = "Stop Route")
                                    }
                                }
                            }
                        }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(routes, key = { it.id }) { route ->
                                Card(Modifier.fillMaxWidth()) {
                                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(route.name, fontWeight = FontWeight.SemiBold)
                                            Text("${route.points.size} точек • ${route.speedKmh.toInt()} км/ч • ${route.loopMode.label}", style = MaterialTheme.typography.bodySmall)
                                        }
                                        IconButton(onClick = {
                                            vm.playRoute(route)
                                            route.points.firstOrNull()?.let { cameraCenterTrigger = Pair(it.latitude, it.longitude) }
                                            selectedTab = 0
                                        }) { Icon(Icons.Default.PlayArrow, null) }
                                        IconButton(onClick = { vm.deleteRoute(route.id) }) { Icon(Icons.Default.Delete, null) }
                                    }
                                }
                            }
                        }
                    }
                }

                2 -> {
                    LazyColumn(
                        Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(favorites, key = { it.id }) { fav ->
                            Card(Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(fav.name, fontWeight = FontWeight.SemiBold)
                                        Text("${fav.category} • ${String.format("%.4f, %.4f", fav.latitude, fav.longitude)}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    IconButton(onClick = {
                                        pinLat = fav.latitude
                                        pinLon = fav.longitude
                                        cameraCenterTrigger = Pair(fav.latitude, fav.longitude)
                                        vm.teleportTo(fav.latitude, fav.longitude)
                                        selectedTab = 0
                                    }) { Icon(Icons.Default.FlightTakeoff, null) }
                                    IconButton(onClick = { vm.removeFavorite(fav.id) }) { Icon(Icons.Default.Delete, null) }
                                }
                            }
                        }
                    }
                }

                3 -> {
                    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        item {
                            Text("Телеметрия и реализм", style = MaterialTheme.typography.titleLarge)
                        }
                        item {
                            Text("Точность (Accuracy): ${String.format("%.1f", settings.accuracyMeters)} м")
                            Slider(
                                value = settings.accuracyMeters,
                                onValueChange = { vm.updateSettings(settings.copy(accuracyMeters = it)) },
                                valueRange = 1f..30f
                            )
                        }
                        item {
                            Text("Высота (Altitude): ${settings.altitudeMeters.toInt()} м")
                            Slider(
                                value = settings.altitudeMeters.toFloat(),
                                onValueChange = { vm.updateSettings(settings.copy(altitudeMeters = it.toDouble())) },
                                valueRange = 0f..500f
                            )
                        }
                        item {
                            Text("Интервал обновления: ${settings.updateIntervalMs} мс")
                            Slider(
                                value = settings.updateIntervalMs.toFloat(),
                                onValueChange = { vm.updateSettings(settings.copy(updateIntervalMs = it.toLong())) },
                                valueRange = 150f..2000f
                            )
                        }
                        item {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Микро-дрейф (Anti-Static Jitter)", fontWeight = FontWeight.Medium)
                                    Text("Имитация естественного шума GPS на месте", style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(
                                    checked = settings.enableDrift,
                                    onCheckedChange = { vm.updateSettings(settings.copy(enableDrift = it)) }
                                )
                            }
                        }
                        item {
                            Text("Радиус дрейфа: ${String.format("%.1f", settings.driftRadiusMeters)} м")
                            Slider(
                                value = settings.driftRadiusMeters,
                                onValueChange = { vm.updateSettings(settings.copy(driftRadiusMeters = it)) },
                                valueRange = 0.5f..8f
                            )
                        }
                    }
                }
            }
        }
    }

    if (showFavDialog) {
        var favName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showFavDialog = false },
            title = { Text("Сохранить точку") },
            text = {
                OutlinedTextField(value = favName, onValueChange = { favName = it }, label = { Text("Название") })
            },
            confirmButton = {
                TextButton(onClick = {
                    if (favName.isNotBlank()) {
                        vm.addFavorite(favName, "Custom", pinLat, pinLon)
                    }
                    showFavDialog = false
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { showFavDialog = false }) { Text("Отмена") } }
        )
    }

    if (showRouteDialog) {
        var routeName by remember { mutableStateOf("Маршрут ${routes.size + 1}") }
        var selectedLoop by remember { mutableStateOf(LoopMode.LOOP) }
        AlertDialog(
            onDismissRequest = { showRouteDialog = false },
            title = { Text("Сохранить маршрут") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = routeName, onValueChange = { routeName = it }, label = { Text("Название") })
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LoopMode.entries.forEach { mode ->
                            FilterChip(
                                selected = selectedLoop == mode,
                                onClick = { selectedLoop = mode },
                                label = { Text(mode.label) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.saveDraftRoute(routeName, selectedLoop, settings.speedKmh)
                    routeBuildMode = false
                    showRouteDialog = false
                }) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.clearDraftRoute()
                    routeBuildMode = false
                    showRouteDialog = false
                }) { Text("Сбросить") }
            }
        )
    }
}

@Composable
private fun OsmMapContainer(
    targetLat: Double,
    targetLon: Double,
    draftPoints: List<GeoPoint>,
    cameraTrigger: Pair<Double, Double>?,
    onCameraMovedHandled: () -> Unit,
    onTap: (Double, Double) -> Unit,
    onLongPress: (Double, Double) -> Unit
) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnLongPress by rememberUpdatedState(onLongPress)

    val mapView = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            isHorizontalMapRepetitionEnabled = false
            isVerticalMapRepetitionEnabled = false
            minZoomLevel = 3.0
            maxZoomLevel = 20.0
            setScrollableAreaLimitLatitude(
                MapView.getTileSystem().maxLatitude,
                MapView.getTileSystem().minLatitude,
                0
            )
            controller.setZoom(15.0)
            controller.setCenter(GeoPoint(targetLat, targetLon))
        }
    }

    val polyline = remember(mapView) {
        Polyline(mapView).also { mapView.overlays.add(it) }
    }
    val marker = remember(mapView) {
        Marker(mapView).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "Target GPS"
        }.also { mapView.overlays.add(it) }
    }

    DisposableEffect(mapView) {
        val eventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                currentOnTap(p.latitude, p.longitude)
                return true
            }
            override fun longPressHelper(p: GeoPoint): Boolean {
                currentOnLongPress(p.latitude, p.longitude)
                return true
            }
        })
        mapView.overlays.add(0, eventsOverlay)
        onDispose { mapView.overlays.remove(eventsOverlay) }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(cameraTrigger) {
        if (cameraTrigger != null) {
            mapView.controller.animateTo(GeoPoint(cameraTrigger.first, cameraTrigger.second), 16.0, 450L)
            onCameraMovedHandled()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { mapView },
        update = { view ->
            marker.position = GeoPoint(targetLat, targetLon)
            polyline.setPoints(draftPoints)
            view.invalidate()
        }
    )
}
