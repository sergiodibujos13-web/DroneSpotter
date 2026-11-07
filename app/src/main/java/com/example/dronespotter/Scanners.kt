package com.example.dronespotter

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.*
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.aware.*
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid

/** (clave única, fuente, RSSI, payload ODID: mensaje o message pack) */
typealias OnDrone = (String, String, Int?, ByteArray) -> Unit

/** Remote ID por Bluetooth 4/5 (incluye extended advertising / Long Range). */
class BleScanner(private val ctx: Context, private val on: OnDrone) {
    private val uuid = ParcelUuid.fromString("0000fffa-0000-1000-8000-00805f9b34fb")
    private var scanner: BluetoothLeScanner? = null
    private val cb = object : ScanCallback() {
        override fun onScanResult(t: Int, r: ScanResult) {
            val d = r.scanRecord?.getServiceData(uuid) ?: return
            if (d.size > 2 && d[0] == 0x0D.toByte())
                on(r.device.address, "BLE", r.rssi, d.copyOfRange(2, d.size))
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        scanner = ctx.getSystemService(BluetoothManager::class.java).adapter?.bluetoothLeScanner
        val filter = ScanFilter.Builder()
            .setServiceData(uuid, byteArrayOf(0x0D), byteArrayOf(0xFF.toByte())).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setLegacy(false).setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED).build()
        scanner?.startScan(listOf(filter), settings, cb)
    }

    @SuppressLint("MissingPermission")
    fun stop() { scanner?.stopScan(cb) }
}

/** Remote ID por Wi-Fi Beacon: IE vendor-specific (221), OUI FA:0B:BC, tipo 0x0D. */
class WifiBeaconScanner(private val ctx: Context, private val on: OnDrone) {
    private val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
    private val h = Handler(Looper.getMainLooper())
    private var running = false
    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = read()
    }
    private val loop = object : Runnable {
        @Suppress("DEPRECATION")
        override fun run() {
            if (!running) return
            wm.startScan()                 // Android limita a ~4 escaneos / 2 min
            h.postDelayed(this, 31_000)
        }
    }

    fun start() {
        running = true
        ctx.registerReceiver(rx, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        loop.run()
    }

    fun stop() {
        running = false
        h.removeCallbacks(loop)
        runCatching { ctx.unregisterReceiver(rx) }
    }

    @SuppressLint("MissingPermission")
    private fun read() {
        val results = runCatching { wm.scanResults }.getOrNull() ?: return
        for (r in results) for (ie in r.informationElements) {
            if (ie.id != 221) continue
            val buf = ie.bytes.duplicate()
            val b = ByteArray(buf.remaining()).also { buf.get(it) }
            if (b.size > 30 && b[0] == 0xFA.toByte() && b[1] == 0x0B.toByte() &&
                b[2] == 0xBC.toByte() && b[3] == 0x0D.toByte()
            ) on(r.BSSID, "WIFI", r.level, b.copyOfRange(5, b.size))  // salta OUI+tipo+contador
        }
    }
}

/** Remote ID por Wi-Fi Aware (NAN): suscripción a "org.opendroneid.remoteid". */
class WifiAwareScanner(private val ctx: Context, private val on: OnDrone) {
    private var session: WifiAwareSession? = null
    private val h = Handler(Looper.getMainLooper())

    /** @return false si el teléfono no soporta Wi-Fi Aware. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (!ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) return false
        val m = ctx.getSystemService(WifiAwareManager::class.java) ?: return false
        if (!m.isAvailable) return false
        m.attach(object : AttachCallback() {
            override fun onAttached(s: WifiAwareSession) {
                session = s
                val cfg = SubscribeConfig.Builder()
                    .setServiceName("org.opendroneid.remoteid")
                    .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE).build()
                s.subscribe(cfg, object : DiscoverySessionCallback() {
                    override fun onServiceDiscovered(
                        peer: PeerHandle, info: ByteArray?, filter: MutableList<ByteArray>?
                    ) {
                        // info = [contador][message pack]
                        if (info != null && info.size > 26)
                            on("nan:${peer.hashCode()}", "NAN", null, info.copyOfRange(1, info.size))
                    }
                }, h)
            }
        }, h)
        return true
    }

    fun stop() { session?.close(); session = null }
}
