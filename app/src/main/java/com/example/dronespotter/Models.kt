package com.example.dronespotter

import android.location.Location
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class Drone(
    val key: String,
    val source: String,            // BLE | WIFI | NAN
    val id: String = "—",
    val lat: Double? = null,
    val lon: Double? = null,
    val altM: Float? = null,
    val speedMs: Float? = null,
    val headingDeg: Int? = null,
    val rssi: Int? = null,
    val lastSeen: Long = System.currentTimeMillis()
)

/** Distancia en metros entre yo y el dron (null si falta alguna posición). */
fun distanceTo(me: Location?, d: Drone): Float? {
    if (me == null || d.lat == null || d.lon == null) return null
    val r = FloatArray(1)
    Location.distanceBetween(me.latitude, me.longitude, d.lat, d.lon, r)
    return r[0]
}

/** Parser Open Drone ID: acepta un mensaje de 25 bytes o un message pack (tipo 0xF). */
object OdidParser {
    fun parse(prev: Drone, p: ByteArray): Drone {
        if (p.size < 25) return prev
        val type = (p[0].toInt() and 0xF0) shr 4
        return when (type) {
            0xF -> {
                val size = p[1].toInt() and 0xFF
                val n = p[2].toInt() and 0xFF
                var d = prev
                for (i in 0 until n) {
                    val s = 3 + i * size
                    if (size < 25 || s + 25 > p.size) break
                    d = parse(d, p.copyOfRange(s, s + 25))
                }
                d
            }
            0 -> prev.copy(
                id = String(p.copyOfRange(2, 22), Charsets.US_ASCII)
                    .trim { it == '\u0000' || it == ' ' }.ifEmpty { "—" }
            )
            1 -> {
                val b = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
                val flags = p[1].toInt()
                var dir = p[2].toInt() and 0xFF
                if (flags and 0x02 != 0) dir += 180
                val raw = p[3].toInt() and 0xFF
                val speed = if (flags and 0x01 != 0) raw * 0.75f + 255 * 0.25f else raw * 0.25f
                val lat = b.getInt(5) * 1e-7
                val lon = b.getInt(9) * 1e-7
                val altRaw = b.getShort(15).toInt() and 0xFFFF
                val known = lat != 0.0 || lon != 0.0
                prev.copy(
                    lat = if (known) lat else prev.lat,
                    lon = if (known) lon else prev.lon,
                    altM = if (altRaw != 0) altRaw * 0.5f - 1000f else prev.altM,
                    speedMs = speed, headingDeg = dir % 360
                )
            }
            else -> prev
        }
    }
}
