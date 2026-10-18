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
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.ConcurrentHashMap

class MainActivity : Activity() {
    companion object {
        const val PREFS = "mochi_settings"
        const val KEY_DEVICE = "device_address"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_SERVICE_UUID = "service_uuid"
        const val KEY_RX_UUID = "rx_uuid"
        const val KEY_TX_UUID = "tx_uuid"
        private const val SCAN_MS = 15000L
    }
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private var adapter: BluetoothAdapter? = null
    @Volatile private var scanning = false
    private var connectingAddress: String? = null
    private val seen = ConcurrentHashMap.newKeySet<String>()
    private val attempts = ConcurrentHashMap.newKeySet<String>()
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        createNotificationChannel()
        buildUi()
        requestBlePermissions()
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        p.getString(KEY_DEVICE, null)?.let { log("MAC tersimpan: $it") }
    }
    override fun onDestroy() { stopScan(); handler.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun buildUi() {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20,24,20,16)
            setBackgroundColor(0xFFF5F7FA.toInt())
        }
        val title = TextView(this).apply { text="MOCHI"; textSize=28f; setTextColor(0xFF087F8C.toInt()); gravity=Gravity.CENTER }
        statusText = TextView(this).apply { text="Status: belum terhubung"; textSize=16f; setPadding(0,18,0,18) }
        val scan = Button(this).apply { text="Cari / Hubungkan MOCHI"; setOnClickListener { startScan() } }
        val time = Button(this).apply { text="Sinkronkan Jam"; setOnClickListener { sendTime() } }
        val notif = Button(this).apply { text="Aktifkan Akses Notifikasi"; setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } }
        val bt = Button(this).apply { text="Pengaturan Bluetooth"; setOnClickListener { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } }
        logText = TextView(this).apply { textSize=13f; setTextColor(0xFF202124.toInt()); movementMethod=ScrollingMovementMethod(); text="Log Mochi siap.\\n" }
        val scroll = ScrollView(this).apply { addView(logText) }
        listOf(title,statusText,scan,time,notif,bt).forEach { outer.addView(it, matchWrap()) }
        outer.addView(scroll, LinearLayout.LayoutParams(-1,0,1f))
        setContentView(outer)
    }
    private fun matchWrap() = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun hasPermissions() = if (Build.VERSION.SDK_INT >= 31) {
        checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED &&
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED
    } else if (Build.VERSION.SDK_INT >= 23) {
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
    } else true

    private fun requestBlePermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            val a=arrayOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT)
            val missing=a.filter { checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED }
            if(missing.isNotEmpty()) requestPermissions(missing.toTypedArray(),100)
        } else if(Build.VERSION.SDK_INT>=23 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),100)
        }
    }
    override fun onRequestPermissionsResult(requestCode:Int, permissions:Array<out String>, grantResults:IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==100) log(if(hasPermissions()) "Izin BLE tersedia." else "Izin BLE belum lengkap.")
    }

    private fun startScan() {
        if(!hasPermissions()) { requestBlePermissions(); log("Izin Bluetooth diperlukan."); return }
        val bt=adapter
        if(bt==null) { log("Adaptor Bluetooth tidak tersedia."); return }
        try {
            if(!bt.isEnabled) { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); log("Aktifkan Bluetooth lalu scan lagi."); return }
            stopScan()
            val scanner=bt.bluetoothLeScanner
            if(scanner==null) { log("BLE scanner tidak tersedia."); return }
            seen.clear(); attempts.clear(); connectingAddress=null; scanning=true
            statusText.text="Status: mencari perangkat BLE..."
            log("Memindai semua perangkat BLE selama 15 detik.")
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setReportDelay(0).build(), callback)
            handler.postDelayed({
                if(scanning) {
                    stopScan()
                    log("Scan selesai. Total perangkat ditemukan: ${seen.size}")
                    if(seen.isEmpty()) log("Android tidak menerima hasil BLE. Periksa izin, Bluetooth, dan advertising perangkat.")
                    else if(connectingAddress==null) log("Belum menemukan nama MOCHI atau MAC perangkat tersimpan.")
                }
            },SCAN_MS)
        } catch(e:SecurityException) { scanning=false; log("Izin BLE ditolak: ${e.message}") }
          catch(e:Exception) { scanning=false; log("Gagal scan: ${e.message}") }
    }

    private val callback=object:ScanCallback() {
        override fun onScanResult(callbackType:Int,result:ScanResult) { process(result) }
        override fun onBatchScanResults(results:MutableList<ScanResult>) { results.forEach { process(it) } }
        override fun onScanFailed(errorCode:Int) { scanning=false; log("Scan BLE gagal: kode $errorCode") }
    }
    private fun process(r:ScanResult) {
        if(!scanning) return
        try {
            val address=r.device.address
            val name=r.scanRecord?.deviceName?.takeIf { it.isNotBlank() }
                ?: try { r.device.name?.takeIf { it.isNotBlank() } } catch(_:SecurityException) { null }
                ?: "(tanpa nama)"
            val uuids=r.scanRecord?.serviceUuids.orEmpty().joinToString { it.uuid.toString() }
            if(seen.add(address)) log("BLE: nama=$name | MAC=$address | RSSI=${r.rssi} dBm | UUID iklan=${uuids.ifBlank { "(tidak diiklankan)" }}")
            val saved=getSharedPreferences(PREFS,MODE_PRIVATE).getString(KEY_DEVICE,null)
            // UUID tidak ditebak. Hanya auto-connect ke nama MOCHI atau MAC yang pernah tersimpan.
            if(!name.equals("MOCHI",true) && (saved.isNullOrBlank() || !address.equals(saved,true))) return
            if(!attempts.add(address)) return
            connectingAddress=address
            getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString(KEY_DEVICE,address).putString(KEY_DEVICE_NAME,name).apply()
            log("Perangkat target ditemukan: $name | $address")
            statusText.text="Status: menghubungkan..."
            MochiBleService.connect(applicationContext,address)
        } catch(e:Exception) { log("Gagal memproses hasil BLE: ${e.message}") }
    }
    private fun stopScan() {
        if(!scanning) return
        try {
            if(Build.VERSION.SDK_INT<31 || checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED)
                adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch(_:Exception) {} finally { scanning=false }
    }
    private fun sendTime() {
        if(!hasPermissions()) { requestBlePermissions(); return }
        val address=getSharedPreferences(PREFS,MODE_PRIVATE).getString(KEY_DEVICE,null)
        if(address.isNullOrBlank()) { log("Belum ada MAC tersimpan. Cari perangkat dahulu."); return }
        val epoch=System.currentTimeMillis()/1000L
        MochiBleService.sendCommand(applicationContext,"TIME|$epoch")
        log("Perintah sinkronisasi waktu dikirim: $epoch")
    }
    private fun log(s:String) { runOnUiThread { if(::logText.isInitialized) { logText.append("• $s\\n"); logText.post { val l=logText.layout; if(l!=null) logText.scrollTo(0,(l.getLineTop(logText.lineCount)-logText.height).coerceAtLeast(0)) } }; if(::statusText.isInitialized) statusText.text="Status: ${MochiBleService.connectionStatus}" } }
    private fun createNotificationChannel() {
        if(Build.VERSION.SDK_INT>=26) {
            val c=NotificationChannel("mochi_status","Mochi status",NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(c)
        }
    }
}
