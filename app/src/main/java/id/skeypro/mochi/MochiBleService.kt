package id.skeypro.mochi

import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.ArrayDeque
import java.util.UUID

object MochiBleService {
    @Volatile var connectionStatus:String="Belum terhubung"
        private set
    private const val TAG="MochiBLE"
    private val serviceUuid = UUID.fromString("7b8a0001-6d8f-4f3a-9c2e-1b4a5d6e7f80")
    private val rxUuid = UUID.fromString("7b8a0002-6d8f-4f3a-9c2e-1b4a5d6e7f80")
    private val txUuid = UUID.fromString("7b8a0003-6d8f-4f3a-9c2e-1b4a5d6e7f80")
    private val cccd=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    @Volatile private var gatt:BluetoothGatt?=null
    @Volatile private var rx:BluetoothGattCharacteristic?=null
    @Volatile private var tx:BluetoothGattCharacteristic?=null
    @Volatile private var connected=false
    @Volatile private var ready=false
    @Volatile private var writing=false
    @Volatile private var pingVerified=false
    private var handshakeTimeout: Runnable?=null
    private var heartbeatRunnable: Runnable?=null
    @Volatile private var lastPongElapsedMs:Long=0L
    private val heartbeatIntervalMs=2500L
    private val heartbeatTimeoutMs=7000L
    private val handler=Handler(Looper.getMainLooper())
    @Volatile private var addressToReconnect:String?=null
    @Volatile private var appContext:Context?=null
    private val queue=ArrayDeque<String>()

    @Synchronized fun connect(context:Context,address:String) {
        appContext=context.applicationContext
        addressToReconnect=address
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        if (manager.adapter?.isEnabled != true) {
            connectionStatus = "Bluetooth HP mati • aktifkan Bluetooth untuk menghubungkan Mochi"
            return
        }
        if(connected && ready && pingVerified && gatt?.device?.address.equals(address,true)) return
        closeGatt()
        connectionStatus="Menghubungkan BLE..."
        try {
            val manager=context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val device=manager.adapter.getRemoteDevice(address)
            gatt=if(Build.VERSION.SDK_INT>=23)
                device.connectGatt(context.applicationContext,false,callback,BluetoothDevice.TRANSPORT_LE)
            else device.connectGatt(context.applicationContext,false,callback)
            if(gatt==null) retry()
        } catch(e:Exception) {
            connectionStatus="Gagal menghubungkan: ${e.message}"
            Log.e(TAG,"connectGatt",e); retry()
        }
    }

    /** Dipanggil saat status Bluetooth sistem berubah. Jangan pertahankan status lama ketika radio mati. */
    @Synchronized fun onBluetoothStateChanged(context: Context, enabled: Boolean) {
        appContext = context.applicationContext
        if (!enabled) {
            handler.removeCallbacks(reconnectRunnable)
            stopHeartbeat()
            handshakeTimeout?.let { handler.removeCallbacks(it) }
            val old = gatt
            gatt = null
            connected = false
            ready = false
            writing = false
            pingVerified = false
            rx = null
            tx = null
            synchronized(queue) { queue.clear() }
            try { old?.disconnect() } catch (_: Exception) { }
            try { old?.close() } catch (_: Exception) { }
            connectionStatus = "Bluetooth HP mati • koneksi Mochi dihentikan"
            Log.i(TAG, "Bluetooth sistem mati; GATT dan reconnect dihentikan")
        } else {
            connectionStatus = "Bluetooth HP aktif • siap menghubungkan Mochi"
            val address = addressToReconnect ?: context.getSharedPreferences(MainActivity.PREFS, 0)
                .getString(MainActivity.KEY_DEVICE, null)
            if (!address.isNullOrBlank()) {
                addressToReconnect = address
                handler.removeCallbacks(reconnectRunnable)
                handler.postDelayed(reconnectRunnable, 800L)
            }
        }
    }

    fun isConnected(): Boolean = connected && ready && pingVerified && gatt != null && rx != null

    fun sendCommand(context:Context,command:String) {
        synchronized(queue) { if(queue.size>=50) { Log.w(TAG,"Queue penuh"); return }; queue.addLast(command) }
        if(!connected || !ready || rx==null || gatt==null) {
            val a=addressToReconnect ?: context.getSharedPreferences(MainActivity.PREFS,0).getString(MainActivity.KEY_DEVICE,null)
            if(!a.isNullOrBlank() && !connected) connect(context.applicationContext,a)
            Log.i(TAG,"Perintah diantrekan: $command"); return
        }
        writeNext()
    }

    @Synchronized private fun writeNext() {
        if(writing || !connected || !ready) return
        val g=gatt ?: return
        val c=rx ?: return
        val command=synchronized(queue) {
            if(queue.isEmpty()) null
            else if (!pingVerified && queue.first != "PING") null
            else queue.removeFirst()
        } ?: return
        try {
            writing=true
            val bytes=command.toByteArray(Charsets.UTF_8)
            val started=if(Build.VERSION.SDK_INT>=33) {
                g.writeCharacteristic(c,bytes,BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)==BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION") c.writeType=BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION") c.value=bytes
                @Suppress("DEPRECATION") g.writeCharacteristic(c)
            }
            if(!started) { writing=false; synchronized(queue) { queue.addFirst(command) }; Log.e(TAG,"Write tidak dimulai") }
        } catch(e:Exception) {
            writing=false; synchronized(queue) { queue.addFirst(command) }; Log.e(TAG,"Write gagal",e)
        }
    }

    private val callback=object:BluetoothGattCallback() {
        override fun onConnectionStateChange(g:BluetoothGatt,status:Int,newState:Int) {
            if(g!==gatt) { try { g.close() } catch(_:Exception) {}; return }
            if(status==BluetoothGatt.GATT_SUCCESS && newState==BluetoothProfile.STATE_CONNECTED) {
                stopHeartbeat()
                connected=true; ready=false; writing=false; pingVerified=false; lastPongElapsedMs=0L
                connectionStatus="BLE GATT tersambung, membaca layanan..."
                if(!g.discoverServices()) { connectionStatus="Gagal membaca layanan"; disconnectRetry(g) }
            } else {
                stopHeartbeat()
                connected=false; ready=false; writing=false; pingVerified=false; lastPongElapsedMs=0L; rx=null; tx=null
                handshakeTimeout?.let { handler.removeCallbacks(it) }
                connectionStatus="BLE terputus (status=$status)"
                try { g.close() } catch(_:Exception) {}
                if(gatt===g) gatt=null
                retry()
            }
        }

        override fun onServicesDiscovered(g:BluetoothGatt,status:Int) {
            if(g!==gatt) return
            if(status!=BluetoothGatt.GATT_SUCCESS) { connectionStatus="Gagal membaca layanan ($status)"; disconnectRetry(g); return }
            // Pilih service/characteristic berdasarkan UUID protokol MOCHI,
            // bukan characteristic pertama yang kebetulan bisa ditulis.
            val mochiService = g.getService(serviceUuid)
            if (mochiService == null) {
                connectionStatus = "Perangkat terhubung, tetapi layanan MOCHI tidak ditemukan"
                Log.e(TAG, "Service MOCHI $serviceUuid tidak ditemukan")
                disconnectRetry(g)
                return
            }
            val writeChar = mochiService.getCharacteristic(rxUuid)
            val notifyChar = mochiService.getCharacteristic(txUuid)
            if (writeChar == null ||
                ((writeChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) == 0 &&
                 (writeChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) == 0)) {
                connectionStatus = "Characteristic RX MOCHI tidak valid"
                disconnectRetry(g)
                return
            }
            if (notifyChar == null ||
                ((notifyChar.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0 &&
                 (notifyChar.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) == 0)) {
                connectionStatus = "Characteristic TX MOCHI tidak valid"
                disconnectRetry(g)
                return
            }
            rx = writeChar
            tx = notifyChar
            ready = false
            val context=appContext
            if(context!=null) {
                val actualName=try { g.device.name } catch(_:Exception) { null }
                context.getSharedPreferences(MainActivity.PREFS,Context.MODE_PRIVATE).edit()
                    .putString(MainActivity.KEY_DEVICE,g.device.address) // hanya alamat sesi terakhir, bukan filter identitas
                    .putString(MainActivity.KEY_DEVICE_NAME,actualName ?: "MOCHI")
                    .putString(MainActivity.KEY_SERVICE_UUID,serviceUuid.toString())
                    .putString(MainActivity.KEY_RX_UUID,rxUuid.toString())
                    .putString(MainActivity.KEY_TX_UUID,txUuid.toString())
                    .apply()
            }
            Log.i(TAG,"GATT MOCHI valid: service=$serviceUuid RX=$rxUuid TX=$txUuid")
            enableNotifications(g, notifyChar)
        }

        private fun enableNotifications(g:BluetoothGatt,c:BluetoothGattCharacteristic) {
            try {
                val localEnabled = g.setCharacteristicNotification(c,true)
                if (!localEnabled) {
                    connectionStatus = "Gagal mengaktifkan notifikasi BLE"
                    disconnectRetry(g)
                    return
                }
                val d=c.getDescriptor(cccd) ?: run {
                    connectionStatus = "Descriptor notifikasi TX tidak ditemukan"
                    disconnectRetry(g)
                    return
                }
                val value=if((c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE)!=0 &&
                    (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY)==0)
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if(Build.VERSION.SDK_INT>=33) g.writeDescriptor(d,value)
                else {
                    @Suppress("DEPRECATION") d.value=value
                    @Suppress("DEPRECATION") g.writeDescriptor(d)
                }
            } catch(e:Exception) {
                Log.e(TAG,"Notifikasi gagal",e)
                connectionStatus = "Gagal mengaktifkan notifikasi BLE"
                disconnectRetry(g)
            }
        }

        override fun onDescriptorWrite(g:BluetoothGatt, descriptor:BluetoothGattDescriptor, status:Int) {
            if (g !== gatt || descriptor.uuid != cccd) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connectionStatus = "Aktivasi notifikasi gagal ($status)"
                disconnectRetry(g)
                return
            }
            ready = true
            pingVerified = false
            connectionStatus = "GATT siap; verifikasi PING/PONG..."
            synchronized(queue) {
                queue.addFirst("PING")
            }
            writeNext()
            handshakeTimeout?.let { handler.removeCallbacks(it) }
            val timeout = Runnable {
                if (gatt === g && connected && ready && !pingVerified) {
                    connectionStatus = "GATT tersambung tetapi PONG tidak diterima; menyambungkan ulang"
                    Log.e(TAG, "Handshake PING/PONG timeout; koneksi tidak dianggap siap")
                    disconnectRetry(g)
                }
            }
            handshakeTimeout = timeout
            handler.postDelayed(timeout, 5000L)
        }

        override fun onCharacteristicWrite(g:BluetoothGatt,c:BluetoothGattCharacteristic,status:Int) {
            if(g!==gatt) return
            writing=false
            Log.i(TAG,"Write callback status=$status uuid=${c.uuid}")
            if (status != BluetoothGatt.GATT_SUCCESS) connectionStatus = "Pengiriman BLE gagal ($status)"
            writeNext()
        }
        override fun onCharacteristicChanged(g:BluetoothGatt,c:BluetoothGattCharacteristic,value:ByteArray) {
            handleResponse(c,value)
        }
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g:BluetoothGatt,c:BluetoothGattCharacteristic) {
            handleResponse(c,c.value ?: byteArrayOf())
        }
        private fun handleResponse(c:BluetoothGattCharacteristic,value:ByteArray) {
            if(c.uuid!=tx?.uuid) return
            val text=value.toString(Charsets.UTF_8).trim()
            Log.i(TAG,"BLE response: $text")
            if (text == "PONG") {
                lastPongElapsedMs = SystemClock.elapsedRealtime()
                val firstVerification = !pingVerified
                pingVerified = true
                handshakeTimeout?.let { handler.removeCallbacks(it) }
                if (firstVerification) {
                    connectionStatus = "MOCHI terhubung • komunikasi terverifikasi"
                    // Sinkronkan jam hanya sekali saat handshake awal, bukan setiap PONG.
                    synchronized(queue) {
                        if (queue.none { it.startsWith("TIME|") }) {
                            queue.addLast("TIME|${System.currentTimeMillis()/1000L}")
                        }
                    }
                    scheduleHeartbeat(gatt ?: return)
                    writeNext()
                }
                return
            }
            if(text.contains("TIME_OK")) connectionStatus="MOCHI terhubung • jam tersinkron"
            if(text.contains("TIME_ERROR")) connectionStatus="MOCHI terhubung • sinkronisasi jam gagal"
            if(text.contains("UNKNOWN_COMMAND")) connectionStatus="BLE tersambung, tetapi firmware menolak perintah"
        }
    }


    /**
     * PONG awal hanya membuktikan koneksi pada satu waktu.
     * Heartbeat berkala memastikan status UI tidak terus menyatakan terhubung
     * setelah ESP32 mati atau berhenti merespons.
     */
    @Synchronized private fun scheduleHeartbeat(g:BluetoothGatt) {
        heartbeatRunnable?.let { handler.removeCallbacks(it) }
        val task = object:Runnable {
            override fun run() {
                if (gatt !== g || !connected || !ready || !pingVerified) return
                val age = SystemClock.elapsedRealtime() - lastPongElapsedMs
                if (lastPongElapsedMs == 0L || age >= heartbeatTimeoutMs) {
                    connectionStatus = "MOCHI tidak merespons • koneksi hilang"
                    Log.e(TAG, "Heartbeat timeout: tidak menerima PONG selama ${age}ms")
                    disconnectRetry(g)
                    return
                }
                synchronized(queue) {
                    if (!queue.contains("PING")) queue.addLast("PING")
                }
                writeNext()
                handler.postDelayed(this, heartbeatIntervalMs)
            }
        }
        heartbeatRunnable = task
        handler.postDelayed(task, heartbeatIntervalMs)
    }

    @Synchronized private fun stopHeartbeat() {
        heartbeatRunnable?.let { handler.removeCallbacks(it) }
        heartbeatRunnable = null
        lastPongElapsedMs = 0L
    }

    private fun disconnectRetry(g:BluetoothGatt) {
        stopHeartbeat()
        connected=false; ready=false; writing=false; pingVerified=false; rx=null; tx=null
        handshakeTimeout?.let { handler.removeCallbacks(it) }
        try { g.disconnect() } catch(_:Exception) {}
        try { g.close() } catch(_:Exception) {}
        if(gatt===g) gatt=null
        retry()
    }
    private fun retry() {
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable,3000L)
    }
    private val reconnectRunnable=Runnable {
        val a=addressToReconnect; val c=appContext
        val enabled = try {
            val manager = c?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            manager?.adapter?.isEnabled == true
        } catch (_: Exception) { false }
        if(enabled && !a.isNullOrBlank() && c!=null && !connected && gatt==null) connect(c,a)
    }
    @Synchronized private fun closeGatt() {
        stopHeartbeat()
        val old=gatt; gatt=null; connected=false; ready=false; writing=false; pingVerified=false; rx=null; tx=null
        handshakeTimeout?.let { handler.removeCallbacks(it) }
        try { old?.disconnect() } catch(_:Exception) {}
        try { old?.close() } catch(_:Exception) {}
    }
}
