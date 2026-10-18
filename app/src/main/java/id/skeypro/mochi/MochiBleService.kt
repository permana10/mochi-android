package id.skeypro.mochi

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

object MochiBleService {
    @Volatile var connectionStatus: String = "Belum terhubung"
        private set

    private const val TAG = "MochiBLE"
    private val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var rx: BluetoothGattCharacteristic? = null
    @Volatile private var tx: BluetoothGattCharacteristic? = null
    @Volatile private var connected = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var reconnectAddress: String? = null
    private var reconnectContext: Context? = null
    private val pending = ArrayDeque<String>()

    fun connect(context: Context, address: String) {
        reconnectContext = context.applicationContext
        reconnectAddress = address
        try {
            val device = (context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager)
                .adapter.getRemoteDevice(address)
            connectionStatus = "Menghubungkan..."
            closeGatt()
            gatt = if (Build.VERSION.SDK_INT >= 23) {
                device.connectGatt(context.applicationContext, false, callback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context.applicationContext, false, callback)
            }
        } catch (e: SecurityException) {
            connectionStatus = "Izin Bluetooth ditolak"
            Log.e(TAG, "connect permission", e)
        } catch (e: Exception) {
            connectionStatus = "Gagal menghubungkan: ${e.message}"
            scheduleReconnect()
        }
    }

    fun sendCommand(context: Context, command: String) {
        synchronized(pending) {
            if (!connected || gatt == null || rx == null) {
                pending.addLast(command)
                val address = reconnectAddress
                    ?: context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                        .getString(MainActivity.KEY_DEVICE, null)
                if (address != null && !connected) connect(context, address)
                return
            }
            pending.addLast(command)
        }
        writeNext()
    }

    private fun writeNext() {
        val currentGatt = gatt ?: return
        val characteristic = rx ?: return
        val command = synchronized(pending) { if (pending.isEmpty()) null else pending.removeFirst() } ?: return
        try {
            val bytes = command.toByteArray(Charsets.UTF_8)
            val started = if (Build.VERSION.SDK_INT >= 33) {
                currentGatt.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                characteristic.value = bytes
                @Suppress("DEPRECATION")
                currentGatt.writeCharacteristic(characteristic)
            }
            if (!started) {
                synchronized(pending) { pending.addFirst(command) }
                Log.w(TAG, "BLE write not started")
            }
        } catch (e: Exception) {
            Log.e(TAG, "write failed", e)
            synchronized(pending) { pending.addFirst(command) }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true
                connectionStatus = "Terhubung"
                gatt = g
                try { g.discoverServices() } catch (e: Exception) { Log.e(TAG, "discover", e) }
            } else {
                connected = false
                connectionStatus = "Terputus"
                rx = null
                tx = null
                try { g.close() } catch (_: Exception) {}
                if (gatt === g) gatt = null
                scheduleReconnect()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connectionStatus = "Gagal membaca service"
                return
            }
            val service = g.getService(MainActivity.SERVICE_UUID)
            rx = service?.getCharacteristic(MainActivity.RX_UUID)
            tx = service?.getCharacteristic(MainActivity.TX_UUID)
            if (rx == null) {
                connectionStatus = "Service/karakteristik Mochi tidak ditemukan"
                return
            }
            connectionStatus = "Terhubung ke MOCHI"
            if (tx != null) enableNotifications(g, tx!!)
            sendCommand(reconnectContext ?: return, "TIME|${System.currentTimeMillis() / 1000L}")
            writeNext()
        }

        private fun enableNotifications(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            try {
                g.setCharacteristicNotification(characteristic, true)
                val descriptor = characteristic.getDescriptor(cccd) ?: return
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(descriptor)
                }
            } catch (e: Exception) {
                Log.w(TAG, "enable notifications failed", e)
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == MainActivity.TX_UUID) {
                val text = value.toString(Charsets.UTF_8)
                Log.d(TAG, "MOCHI TX: $text")
                if (text == "TIME_OK") connectionStatus = "Terhubung • Jam tersinkron"
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == MainActivity.TX_UUID) {
                val text = characteristic.value?.toString(Charsets.UTF_8).orEmpty()
                Log.d(TAG, "MOCHI TX: $text")
                if (text == "TIME_OK") connectionStatus = "Terhubung • Jam tersinkron"
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "write status=$status")
            writeNext()
        }
    }

    private fun scheduleReconnect() {
        val address = reconnectAddress ?: return
        val context = reconnectContext ?: return
        mainHandler.postDelayed({
            if (!connected) connect(context, address)
        }, 3000)
    }

    private fun closeGatt() {
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        rx = null
        tx = null
        connected = false
    }
}
