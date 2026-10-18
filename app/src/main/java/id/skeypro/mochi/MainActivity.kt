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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MainActivity : Activity() {

    companion object {
        const val DEVICE_NAME = "MOCHI"

        val SERVICE_UUID: UUID =
            UUID.fromString("7b8a0001-6d8f-4f3a-9c2e-1b4a5d6e7f80")

        val RX_UUID: UUID =
            UUID.fromString("7b8a0002-6d8f-4f3a-9c2e-1b4a5d6e7f80")

        val TX_UUID: UUID =
            UUID.fromString("7b8a0003-6d8f-4f3a-9c2e-1b4a5d6e7f80")

        const val PREFS = "mochi_settings"
        const val KEY_DEVICE = "device_address"
        private const val SCAN_DURATION_MS = 15000L
    }

    private lateinit var statusText: TextView
    private lateinit var logText: TextView

    private var adapter: BluetoothAdapter? = null
    private var scanning = false
    private var connectingAddress: String? = null

    private val seenDevices = ConcurrentHashMap.newKeySet<String>()
    private val connectionAttempts = ConcurrentHashMap.newKeySet<String>()

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val manager =
            getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

        adapter = manager.adapter

        createNotificationChannel()
        buildUi()
        requestBlePermissions()
    }

    override fun onResume() {
        super.onResume()
        updateConnectionStatus()
    }

    override fun onDestroy() {
        stopScan()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
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
            setOnClickListener {
                startScan()
            }
        }

        val timeButton = Button(this).apply {
            text = "Sinkronkan Jam"
            setOnClickListener {
                sendTime()
            }
        }

        val permissionButton = Button(this).apply {
            text = "Aktifkan Akses Notifikasi"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                )
            }
        }

        val connectSettingsButton = Button(this).apply {
            text = "Pengaturan Bluetooth"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                )
            }
        }

        logText = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF202124.toInt())
            movementMethod = ScrollingMovementMethod()
            text = "Log Mochi siap.\n"
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(
                logText,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        outer.addView(title, matchWrap())
        outer.addView(statusText, matchWrap())
        outer.addView(scanButton, matchWrap())
        outer.addView(timeButton, matchWrap())
        outer.addView(permissionButton, matchWrap())
        outer.addView(connectSettingsButton, matchWrap())

        outer.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(outer)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun hasBlePermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }

        return true
    }

    private fun requestBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permissions = arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )

            val missing = permissions.filter {
                checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }

            if (missing.isNotEmpty()) {
                requestPermissions(missing.toTypedArray(), 100)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    100
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 100) {
            if (hasBlePermissions()) {
                log("Izin BLE diberikan. Tekan Cari / Hubungkan MOCHI.")
            } else {
                log("Izin BLE belum lengkap. Izinkan akses perangkat terdekat.")
            }
        }
    }

    private fun startScan() {
        if (!hasBlePermissions()) {
            log("Izin BLE belum lengkap. Meminta izin...")
            requestBlePermissions()
            return
        }

        val bt = adapter

        if (bt == null) {
            log("Adaptor Bluetooth tidak tersedia pada perangkat ini.")
            return
        }

        try {
            if (!bt.isEnabled) {
                log("Bluetooth belum aktif. Aktifkan Bluetooth lalu coba lagi.")
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                return
            }

            if (scanning) {
                stopScan()
            }

            val scanner = bt.bluetoothLeScanner

            if (scanner == null) {
                log("BLE scanner tidak tersedia. Periksa Bluetooth perangkat.")
                return
            }

            seenDevices.clear()
            connectionAttempts.clear()
            connectingAddress = null
            scanning = true

            statusText.text = "Status: mencari perangkat BLE..."
            log("Memulai pemindaian BLE selama 15 detik.")
            log("MOCHI dicari berdasarkan nama, service UUID, dan alamat tersimpan.")

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0L)
                .build()

            scanner.startScan(null, settings, scanCallback)

            mainHandler.postDelayed({
                if (scanning) {
                    stopScan()

                    log("Pemindaian selesai. Perangkat ditemukan: ${seenDevices.size}")

                    if (seenDevices.isEmpty()) {
                        log("Tidak ada hasil BLE dari Android.")
                        log("Periksa izin Nearby devices, Bluetooth, dan iklan BLE ESP32.")
                    } else if (connectingAddress == null) {
                        log("MOCHI belum cocok dengan nama, UUID, atau alamat tersimpan.")
                        log("Periksa daftar nama dan MAC perangkat pada log di atas.")
                    }
                }
            }, SCAN_DURATION_MS)

        } catch (e: SecurityException) {
            scanning = false
            log("Akses Bluetooth ditolak: ${e.message}")
        } catch (e: Exception) {
            scanning = false
            log("Gagal memulai pemindaian BLE: ${e.message}")
        }
    }

    private val scanCallback = object : ScanCallback() {

        override fun onScanResult(
            callbackType: Int,
            result: ScanResult
        ) {
            processScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            for (result in results) {
                processScanResult(result)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false

            mainHandler.post {
                log("Pemindaian BLE gagal. Kode error: $errorCode")
                updateConnectionStatus()
            }
        }
    }

    private fun processScanResult(result: ScanResult) {
        if (!scanning) return

        try {
            val address = result.device.address ?: return

            val advertisedName = result.scanRecord?.deviceName

            val deviceName = try {
                result.device.name
            } catch (_: SecurityException) {
                null
            }

            val name = when {
                !advertisedName.isNullOrBlank() -> advertisedName
                !deviceName.isNullOrBlank() -> deviceName
                else -> "(tanpa nama)"
            }

            val rssi = result.rssi

            if (seenDevices.add(address)) {
                log("BLE ditemukan: nama=$name | MAC=$address | RSSI=$rssi dBm")
            }

            val advertisedUuids = result.scanRecord?.serviceUuids.orEmpty()

            val serviceMatch = advertisedUuids.any {
                it.uuid == SERVICE_UUID
            }

            val savedAddress = getSharedPreferences(
                PREFS,
                MODE_PRIVATE
            ).getString(KEY_DEVICE, null)

            val nameMatch = name.equals(
                DEVICE_NAME,
                ignoreCase = true
            )

            val addressMatch = !savedAddress.isNullOrBlank() &&
                address.equals(savedAddress, ignoreCase = true)

            if (!nameMatch && !serviceMatch && !addressMatch) {
                return
            }

            if (!connectionAttempts.add(address)) {
                return
            }

            if (connectingAddress == address) {
                return
            }

            connectingAddress = address

            getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(KEY_DEVICE, address)
                .apply()

            log("MOCHI cocok: nama=$name | MAC=$address")
            log("Service UUID cocok: $serviceMatch")

            runOnUiThread {
                statusText.text = "Status: menghubungkan ke MOCHI..."
            }

            /*
             * Jangan menghentikan scan di sini.
             * Scan tetap berjalan sampai batas waktu agar log perangkat
             * lain tetap dapat terlihat jika koneksi pertama gagal.
             */
            MochiBleService.connect(applicationContext, address)

        } catch (e: SecurityException) {
            log("Tidak dapat membaca hasil BLE: ${e.message}")
        } catch (e: Exception) {
            log("Gagal memproses hasil BLE: ${e.message}")
        }
    }

    private fun stopScan() {
        if (!scanning) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                scanning = false
                return
            }

            adapter?.bluetoothLeScanner?.stopScan(scanCallback)

        } catch (e: Exception) {
            log("Peringatan saat menghentikan scan: ${e.message}")
        } finally {
            scanning = false
        }
    }

    private fun sendTime() {
        if (!hasBlePermissions()) {
            log("Izin BLE diperlukan sebelum mengirim waktu.")
            requestBlePermissions()
            return
        }

        val address = getSharedPreferences(
            PREFS,
            MODE_PRIVATE
        ).getString(KEY_DEVICE, null)

        if (address.isNullOrBlank()) {
            log("Alamat MOCHI belum tersimpan. Cari / Hubungkan MOCHI dahulu.")
            return
        }

        val epoch = System.currentTimeMillis() / 1000L

        MochiBleService.sendCommand(
            applicationContext,
            "TIME|$epoch"
        )

        log("Perintah sinkronisasi waktu dikirim: $epoch")
    }

    private fun updateConnectionStatus() {
        if (!::statusText.isInitialized) return

        statusText.text =
            "Status: ${MochiBleService.connectionStatus}"
    }

    private fun log(message: String) {
        runOnUiThread {
            if (::logText.isInitialized) {
                logText.append("• $message\n")

                val layout = logText.layout
                if (layout != null) {
                    val scrollAmount =
                        layout.getLineTop(logText.lineCount) - logText.height

                    if (scrollAmount > 0) {
                        logText.scrollTo(0, scrollAmount)
                    } else {
                        logText.scrollTo(0, 0)
                    }
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "mochi_status",
                "Mochi status",
                NotificationManager.IMPORTANCE_LOW
            )

            val manager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            manager.createNotificationChannel(channel)
        }
    }
}
