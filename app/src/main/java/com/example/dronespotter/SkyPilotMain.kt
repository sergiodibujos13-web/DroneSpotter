package com.example.dronespotter

import android.Manifest
import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val drones = mutableStateMapOf<String, Drone>()
    private val me = mutableStateOf<Location?>(null)
    private val scanning = mutableStateOf(false)
    private val threshold = mutableFloatStateOf(500f)
    private val status = mutableStateOf("")

    private val ble by lazy { BleScanner(this, ::onDrone) }
    private val wifi by lazy { WifiBeaconScanner(this, ::onDrone) }
    private val aware by lazy { WifiAwareScanner(this, ::onDrone) }
    private val alerts by lazy { AlertManager(this) }
    private val locListener = LocationListener { me.value = it }

    private fun onDrone(key: String, src: String, rssi: Int?, payload: ByteArray) {
        runOnUiThread {
            val prev = drones[key] ?: Drone(key, src)
            val d = OdidParser.parse(prev, payload)
                .copy(rssi = rssi ?: prev.rssi, lastSeen = System.currentTimeMillis())
            drones[key] = d
            val m = distanceTo(me.value, d)
            if ((m != null && m <= threshold.floatValue) || (m == null && (d.rssi ?: -100) > -50))
                alerts.alert(d, m)
        }
    }

    @SuppressLint("MissingPermission")
    private fun start() {
        runCatching { ble.start() }
        runCatching { wifi.start() }
        val nan = runCatching { aware.start() }.getOrDefault(false)
        status.value = if (nan) "BLE + Wi-Fi Beacon + Wi-Fi NAN" else "BLE + Wi-Fi Beacon (sin Wi-Fi Aware)"
        val lm = getSystemService(LocationManager::class.java)
        runCatching {
            me.value = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, locListener)
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 5f, locListener)
        }
        scanning.value = true
    }

    private fun stop() {
        runCatching { ble.stop() }
        runCatching { wifi.stop() }
        runCatching { aware.stop() }
        runCatching { getSystemService(LocationManager::class.java).removeUpdates(locListener) }
        scanning.value = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFD69408),
                    onPrimary = Color(0xFF0B1B2E),
                    secondary = Color(0xFF4FC3F7),
                    background = Color(0xFF0B1B2E),
                    surface = Color(0xFF0B1B2E),
                    onBackground = Color(0xFFE8EEF2),
                    onSurface = Color(0xFFE8EEF2),
                    surfaceVariant = Color(0xFF16293A),
                    onSurfaceVariant = Color(0xFFE8EEF2)
                )
            ) {
                val perms = buildList {
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                    if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_SCAN)
                    if (Build.VERSION.SDK_INT >= 33) {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                        add(Manifest.permission.NEARBY_WIFI_DEVICES)
                    }
                }.toTypedArray()
                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { start() }

                var tab by remember { mutableIntStateOf(0) }

                // Elimina drones sin señal desde hace 20 s
                LaunchedEffect(scanning.value) {
                    while (scanning.value) {
                        delay(3000)
                        val now = System.currentTimeMillis()
                        drones.entries.removeAll { now - it.value.lastSeen > 20_000 }
                    }
                }

                val list = drones.values.toList()
                val near = list.filter { (distanceTo(me.value, it) ?: Float.MAX_VALUE) <= threshold.floatValue }

                Box(Modifier.fillMaxSize()) {
                AuroraBackground()
                Scaffold(
                    containerColor = Color.Transparent,
                    contentColor = Color(0xFFE8EEF2)
                ) { pad ->
                    Column(Modifier.padding(pad).padding(12.dp)) {
                        Text(
                            "SkyPilot",
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color(0xFFD69408),
                            fontWeight = FontWeight.Bold
                        )
                        // En la pestaña Cuaderno se ocultan los controles de detección para dejar sitio
                        if (tab != 2) {
                            if (status.value.isNotEmpty()) Text(status.value, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { if (scanning.value) stop() else launcher.launch(perms) }) {
                                Text(if (scanning.value) "Detener" else "Buscar drones")
                            }
                            Text("Alertar a menos de ${threshold.floatValue.toInt()} m")
                            Slider(
                                value = threshold.floatValue, onValueChange = { threshold.floatValue = it },
                                valueRange = 100f..2000f
                            )
                            if (near.isNotEmpty()) {
                                Card(
                                    Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFCDD2))
                                ) {
                                    Text(
                                        "⚠ ${near.size} dron(es) dentro del radio de alerta",
                                        Modifier.padding(10.dp), color = Color(0xFFB71C1C)
                                    )
                                }
                            }
                        }
                        TabRow(
                            selectedTabIndex = tab,
                            containerColor = Color.Transparent,
                            contentColor = Color(0xFFD69408)
                        ) {
                            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Mapa") })
                            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Lista (${list.size})") })
                            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Cuaderno") })
                        }
                        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                            when (tab) {
                                0 -> DroneMap(me.value, list, threshold.floatValue)
                                1 -> LazyColumn(
                                    Modifier.padding(top = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) { items(list.sortedBy { distanceTo(me.value, it) ?: Float.MAX_VALUE }) { DroneCard(it, me.value) } }
                                else -> CuadernoTab()
                            }
                        }
                    }
                }
                }
            }
        }
    }

    override fun onDestroy() { stop(); super.onDestroy() }
}

@Composable
fun DroneCard(d: Drone, me: Location?) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xCC16293A),
            contentColor = Color(0xFFE8EEF2)
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("ID: ${d.id}  [${d.source}]", style = MaterialTheme.typography.titleSmall)
            distanceTo(me, d)?.let { Text("Distancia: ${it.toInt()} m") }
            d.rssi?.let { Text("Señal: $it dBm") }
            if (d.lat != null && d.lon != null) Text("Posición: %.5f, %.5f".format(d.lat, d.lon))
            d.altM?.let { Text("Altitud: %.0f m".format(it)) }
            d.speedMs?.let { Text("Velocidad: %.1f m/s · Rumbo: ${d.headingDeg}°".format(it)) }
        }
    }
}
