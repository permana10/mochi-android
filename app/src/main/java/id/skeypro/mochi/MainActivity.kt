package id.skeypro.mochi

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.UUID

class MainActivity : Activity() {
    companion object {
        const val DEVICE_NAME = "MOCHI"
        val SERVICE_UUID: UUID = UUID.fromString("7b8a0001-6d8f-4f3a-9c2e-1b4a5d6e7f80")
        val RX_UUID: UUID = UUID.fromString("7b8a0002-6d8f-4f3a-9c2e-1b4a5d6e7f80")
        val TX_UUID: UUID = UUID.fromString("7b8a0003-6d8f-4f3a-9c2e-1b4a5d6e7f80")
        const val PREFS = "mochi_settings"
        const val KEY_DEVICE = "device_address"
    }

    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private var adapter: BluetoothAdapter? = null
    private var scanning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        adapter = manager.adapter
        createNotificationChannel()
        buildUi()
        requestBlePermissions()
    }

    private fun buildUi() {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 24, 20, 16)
            setBackgroundColor(0xFFF5F7FA.toInt())
        }
        val title = TextView(this).apply {
            text = "MOCHI"
            textSize = 28f
            setTextColor(0xFF087F8C.toInt())
            gravity = Gravity.CENTER
        }
        statusText = TextView(this).apply {
            text = "Status: belum terhubung"
            textSize = 16f
            setPadding(0, 18, 0, 18)
        }
        val scanButton = Button(this).apply {
            text = "Cari / Hubungkan MOCHI"
            setOnClickListener { startScan() }
        }
        val timeButton = Button(this).apply {
            text = "Sinkronkan Jam"
            setOnClickListener { sendTime() }
        }
        val permissionButton = Button(this).apply {
            text = "Aktifkan Akses Notifikasi"
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
        val connectSettingsButton = Button(this).apply {
            text = "Pengaturan Bluetooth"
            setOnClickListener { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        }
        logText = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF202124.toInt())
            movementMethod = ScrollingMovementMethod()
            text = "Log Mochi siap.\n"
        }
        val scroll = ScrollView(this).apply {
            addView(logText)
        }
        outer.addView(title, matchWrap())
        outer.addView(statusText, matchWrap())
        outer.addView(scanButton, matchWrap())
        outer.addView(timeButton, matchWrap())
        outer.addView(permissionButton, matchWrap())
        outer.addView(connectSettingsButton, matchWrap())
        outer.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(outer)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun requestBlePermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            val needed = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), 100)
        }
    }

    private fun startScan() {
        if (Build.VERSION.SDK_INT >= 31 &&
            (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
             checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)) {
            requestBlePermissions()
            log("Izin BLE diperlukan.")
            return
        }
        val bt = adapter
        if (bt == null || !bt.isEnabled) {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            log("Aktifkan Bluetooth, lalu tekan Cari lagi.")
            return
        }
        if (scanning) return
        scanning = true
        statusText.text = "Status: mencari MOCHI..."
        log("Memindai perangkat BLE MOCHI...")
        try {
            val scanner = bt.bluetoothLeScanner
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(null, settings, scanCallback)
            statusText.postDelayed({
                if (scanning) {
                    stopScan()
                    statusText.text = "Status: MOCHI belum ditemukan"
                    log("Pemindaian selesai. Pastikan MOCHI menyala dan tidak sedang terhubung ke aplikasi lain.")
                }
            }, 12000)
        } catch (e: SecurityException) {
            scanning = false
            log("Izin scan ditolak: ${e.message}")
        } catch (e: Exception) {
            scanning = false
            log("Gagal scan: ${e.message}")
        }
    }

    private val scanCallback = object : ScanCallback() {
        private val seen = mutableSetOf<String>()

        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return

            try {
                val name = try { result.device.name } catch (_: SecurityException) { null }
                    ?: result.scanRecord?.deviceName
                    ?: "(tanpa nama)"
                val address = result.device.address
                val rssi = result.rssi
                val serviceMatch = result.scanRecord?.serviceUuids
                    ?.any { it.uuid == SERVICE_UUID } == true

                if (seen.add(address)) {
                    log("BLE: nama=$name | MAC=$address | RSSI=$rssi dBm")
                }

                val savedAddress = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString(KEY_DEVICE, null)

                val isMochi = name.equals(DEVICE_NAME, true) ||
                    serviceMatch ||
                    (!savedAddress.isNullOrBlank() && address.equals(savedAddress, true))

                if (!isMochi) return

                stopScan()
                getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit().putString(KEY_DEVICE, address).apply()

                log("MOCHI ditemukan: $name | $address")
                MochiBleService.connect(applicationContext, address)
            } catch (e: SecurityException) {
                log("Gagal membaca hasil BLE: ${e.message}")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            log("Scan BLE gagal: kode $errorCode")
        }
    }

    private fun stopScan() {
        if (!scanning) return
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
        scanning = false
    }

    private fun sendTime() {
        val address = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_DEVICE, null)
        if (address.isNullOrBlank()) {
            log("MOCHI belum ditemukan. Tekan Cari / Hubungkan MOCHI dahulu.")
            return
        }
        val epoch = System.currentTimeMillis() / 1000L
        MochiBleService.sendCommand(applicationContext, "TIME|$epoch")
        log("Meminta sinkronisasi waktu: $epoch")
    }

    private fun log(message: String) {
        runOnUiThread {
            logText.append("• $message\n")
            statusText.text = "Status: ${MochiBleService.connectionStatus}"
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel("mochi_status", "Mochi status", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }
}
