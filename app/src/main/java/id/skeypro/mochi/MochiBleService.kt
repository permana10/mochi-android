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
    private val cccd=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    @Volatile private var gatt:BluetoothGatt?=null
    @Volatile private var rx:BluetoothGattCharacteristic?=null
    @Volatile private var tx:BluetoothGattCharacteristic?=null
    @Volatile private var connected=false
    @Volatile private var ready=false
    @Volatile private var writing=false
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
        val command=synchronized(queue) { if(queue.isEmpty()) null else queue.removeFirst() } ?: return
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
                connected=true; ready=false; connectionStatus="BLE tersambung, membaca layanan..."
                if(!g.discoverServices()) { connectionStatus="Gagal membaca layanan"; disconnectRetry(g) }
            } else {
                connected=false; ready=false; writing=false; rx=null; tx=null
                connectionStatus="BLE terputus (status=$status)"
                try { g.close() } catch(_:Exception) {}
                if(gatt===g) gatt=null
                retry()
            }
        }

        override fun onServicesDiscovered(g:BluetoothGatt,status:Int) {
            if(g!==gatt) return
            if(status!=BluetoothGatt.GATT_SUCCESS) { connectionStatus="Gagal membaca layanan ($status)"; disconnectRetry(g); return }
            // UUID harus sama dengan firmware MOCHI_ESP32C3.ino.
            val serviceUuid = UUID.fromString(MainActivity.MOCHI_SERVICE_UUID)
            val rxUuid = UUID.fromString("7b8a0002-6d8f-4f3a-9c2e-1b4a5d6e7f80")
            val txUuid = UUID.fromString("7b8a0003-6d8f-4f3a-9c2e-1b4a5d6e7f80")
            for (s in g.services.orEmpty()) {
                for (c in s.characteristics.orEmpty()) {
                    Log.i(TAG, "GATT service=${s.uuid} characteristic=${c.uuid} properties=0x${c.properties.toString(16)}")
                }
            }
            val service = g.getService(serviceUuid)
            if (service == null) {
                connectionStatus = "GATT tersambung, service MOCHI tidak ditemukan"
                Log.e(TAG, "Service target $serviceUuid tidak ditemukan")
                disconnectRetry(g)
                return
            }
            val writeChar = service.getCharacteristic(rxUuid)
            val notifyChar = service.getCharacteristic(txUuid)
            if (writeChar == null || (writeChar.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) == 0) {
                connectionStatus = "Service MOCHI ditemukan, RX tidak valid"
                Log.e(TAG, "RX $rxUuid tidak ditemukan/tidak mendukung WRITE")
                disconnectRetry(g)
                return
            }
            rx = writeChar
            tx = notifyChar
            ready = true
            connectionStatus = "GATT MOCHI siap; mengaktifkan notifikasi"
            val context = appContext
            if (context != null) {
                val actualName = try { g.device.name } catch (_: Exception) { null }
                context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit()
                    .putString(MainActivity.KEY_DEVICE, g.device.address)
                    .putString(MainActivity.KEY_DEVICE_NAME, actualName ?: "MOCHI")
                    .putString(MainActivity.KEY_SERVICE_UUID, serviceUuid.toString())
                    .putString(MainActivity.KEY_RX_UUID, rxUuid.toString())
                    .putString(MainActivity.KEY_TX_UUID, txUuid.toString())
                    .apply()
            }
            Log.i(TAG, "GATT MOCHI cocok: service=$serviceUuid RX=$rxUuid TX=$txUuid")
            tx?.let { enableNotifications(g,it) }
            synchronized(queue) {
                if (queue.none { it.startsWith("TIME|") }) queue.addLast("TIME|${System.currentTimeMillis()/1000L}")
                if (queue.none { it == "PING" }) queue.addLast("PING")
            }
            writeNext()
        }

        private fun enableNotifications(g:BluetoothGatt,c:BluetoothGattCharacteristic) {
            try {
                g.setCharacteristicNotification(c,true)
                val d=c.getDescriptor(cccd) ?: run { Log.w(TAG,"CCCD tidak ditemukan"); return }
                val value=if((c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE)!=0 &&
                    (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY)==0)
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if(Build.VERSION.SDK_INT>=33) g.writeDescriptor(d,value)
                else {
                    @Suppress("DEPRECATION") d.value=value
                    @Suppress("DEPRECATION") g.writeDescriptor(d)
                }
            } catch(e:Exception) { Log.e(TAG,"Notifikasi gagal",e) }
        }

        override fun onCharacteristicWrite(g:BluetoothGatt,c:BluetoothGattCharacteristic,status:Int) {
            if(g!==gatt) return
            writing=false
            Log.i(TAG,"Write callback status=$status uuid=${c.uuid}")
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
            val text=value.toString(Charsets.UTF_8)
            Log.i(TAG,"BLE response: $text")
            if(text.contains("TIME_OK")) connectionStatus="Terhubung • Jam tersinkron"
            if(text.contains("TIME_ERROR")) connectionStatus="Terhubung • Sinkronisasi jam gagal"
            if(text.contains("PONG")) connectionStatus="Terhubung • PING/PONG berhasil"
        }
    }

    private fun disconnectRetry(g:BluetoothGatt) {
        connected=false; ready=false; writing=false; rx=null; tx=null
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
        val old=gatt; gatt=null; connected=false; ready=false; writing=false; rx=null; tx=null
        try { old?.disconnect() } catch(_:Exception) {}
        try { old?.close() } catch(_:Exception) {}
    }
}
