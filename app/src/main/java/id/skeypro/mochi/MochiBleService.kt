package id.skeypro.mochi

import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
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
    private val handler=Handler(Looper.getMainLooper())
    @Volatile private var addressToReconnect:String?=null
    @Volatile private var appContext:Context?=null
    private val queue=ArrayDeque<String>()

    @Synchronized fun connect(context:Context,address:String) {
        appContext=context.applicationContext
        addressToReconnect=address
        if(connected && ready && gatt?.device?.address.equals(address,true)) return
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

    fun isConnected(): Boolean = connected && ready && gatt != null && rx != null

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
                connected=true; ready=false; pingVerified=false; connectionStatus="BLE GATT tersambung, membaca layanan..."
                if(!g.discoverServices()) { connectionStatus="Gagal membaca layanan"; disconnectRetry(g) }
            } else {
                connected=false; ready=false; writing=false; pingVerified=false; rx=null; tx=null
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
                val started = if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION") d.value = value
                    @Suppress("DEPRECATION") g.writeDescriptor(d)
                }
                if (!started) {
                    connectionStatus = "Penulisan CCCD gagal dimulai"
                    Log.e(TAG, "writeDescriptor(CCCD) gagal dimulai")
                    disconnectRetry(g)
                } else {
                    connectionStatus = "Mengaktifkan notifikasi TX..."
                    Log.i(TAG, "CCCD write dimulai: ${value.joinToString { "%02X".format(it) }}")
                }
            } catch(e:Exception) {
                Log.e(TAG,"Notifikasi gagal",e)
                connectionStatus = "Gagal mengaktifkan notifikasi BLE"
                disconnectRetry(g)
            }
        }

        override fun onDescriptorWrite(g:BluetoothGatt, descriptor:BluetoothGattDescriptor, status:Int) {
            if (g !== gatt || descriptor.uuid != cccd || descriptor.characteristic.uuid != tx?.uuid) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connectionStatus = "Aktivasi notifikasi gagal ($status)"
                disconnectRetry(g)
                return
            }
            ready = true
            pingVerified = false
            connectionStatus = "Notifikasi TX aktif; verifikasi PING/PONG..."
            Log.i(TAG, "CCCD berhasil ditulis; notifikasi TX aktif")
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
            if (g !== gatt) return
            handleResponse(c,value)
        }
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g:BluetoothGatt,c:BluetoothGattCharacteristic) {
            if (g !== gatt) return
            handleResponse(c,c.value ?: byteArrayOf())
        }
        private fun handleResponse(c:BluetoothGattCharacteristic,value:ByteArray) {
            if(c.uuid!=tx?.uuid) return
            val text=value.toString(Charsets.UTF_8)
            Log.i(TAG,"BLE response: $text")
            if (text.trim() == "PONG") {
                pingVerified = true
                handshakeTimeout?.let { handler.removeCallbacks(it) }
                connectionStatus = "MOCHI terhubung • komunikasi terverifikasi"
                synchronized(queue) {
                    if (queue.none { it.startsWith("TIME|") }) {
                        queue.addLast("TIME|${System.currentTimeMillis()/1000L}")
                    }
                }
                writeNext()
                return
            }
            if(text.contains("TIME_OK")) connectionStatus="MOCHI terhubung • jam tersinkron"
            if(text.contains("TIME_ERROR")) connectionStatus="MOCHI terhubung • sinkronisasi jam gagal"
            if(text.contains("UNKNOWN_COMMAND")) connectionStatus="BLE tersambung, tetapi firmware menolak perintah"
        }
    }

    private fun disconnectRetry(g:BluetoothGatt) {
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
        if(!a.isNullOrBlank() && c!=null && !connected && gatt==null) connect(c,a)
    }
    @Synchronized private fun closeGatt() {
        val old=gatt; gatt=null; connected=false; ready=false; writing=false; pingVerified=false; rx=null; tx=null
        handshakeTimeout?.let { handler.removeCallbacks(it) }
        try { old?.disconnect() } catch(_:Exception) {}
        try { old?.close() } catch(_:Exception) {}
    }
}
