package com.example.dronespotter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.location.Location
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.io.File

/** Notificación + vibración, máx. una vez por minuto por dron. */
class AlertManager(private val ctx: Context) {
    private val last = HashMap<String, Long>()
    private val nm = ctx.getSystemService(NotificationManager::class.java)

    init {
        nm.createNotificationChannel(
            NotificationChannel("drones", "Alertas de drones", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    fun alert(d: Drone, meters: Float?) {
        val now = System.currentTimeMillis()
        if (now - (last[d.key] ?: 0L) < 60_000) return
        last[d.key] = now
        ctx.getSystemService(Vibrator::class.java)
            ?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1))
        val txt = if (meters != null) "A ${meters.toInt()} m de ti (ID ${d.id})"
        else "Señal muy fuerte: está muy cerca (ID ${d.id})"
        nm.notify(
            d.key.hashCode(),
            Notification.Builder(ctx, "drones")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("⚠ Dron cercano")
                .setContentText(txt).setAutoCancel(true).build()
        )
    }
}

/** Mapa OpenStreetMap (sin API key): yo, drones y círculo de alerta. */
@Composable
fun DroneMap(me: Location?, drones: List<Drone>, radiusM: Float, modifier: Modifier = Modifier.fillMaxSize()) {
    AndroidView(
        modifier = modifier,
        factory = { c ->
            Configuration.getInstance().apply {
                load(c, c.getSharedPreferences("osm", 0))
                userAgentValue = "DroneSpotter/1.0 (uso personal; github.com/sergiodibujos13-web/DroneSpotter)"
                osmdroidBasePath = c.cacheDir
                osmdroidTileCache = File(c.cacheDir, "tiles")
            }
            MapView(c).apply {
                setTileSource(org.osmdroid.tileprovider.tilesource.XYTileSource("CartoVoyager", 0, 19, 256, ".png", arrayOf("https://a.basemaps.cartocdn.com/rastertiles/voyager/", "https://b.basemaps.cartocdn.com/rastertiles/voyager/")))
                setMultiTouchControls(true)
                controller.setZoom(16.0)
            }
        },
        update = { map ->
            map.overlays.clear()
            if (me != null) {
                val p = GeoPoint(me.latitude, me.longitude)
                if (map.tag == null) { map.controller.setCenter(p); map.tag = true }
                map.overlays.add(Polygon().apply {
                    points = Polygon.pointsAsCircle(p, radiusM.toDouble())
                    fillPaint.color = 0x22FF0000
                    outlinePaint.color = Color.RED
                    outlinePaint.strokeWidth = 3f
                })
                map.overlays.add(Marker(map).apply { position = p; title = "Tú" })
            }
            drones.filter { it.lat != null && it.lon != null }.forEach { d ->
                map.overlays.add(Marker(map).apply {
                    position = GeoPoint(d.lat!!, d.lon!!)
                    icon = icon.mutate().apply { setTint(Color.RED) }
                    title = "Dron ${d.id}"
                    snippet = distanceTo(me, d)?.let { "${it.toInt()} m · ${d.source}" } ?: d.source
                })
            }
            map.invalidate()
        },
        onRelease = { it.onDetach() }
    )
}
