package id.skeypro.mochi

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.ArrayDeque
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
    @Volatile private var servicesReady = false
    private var writeInProgress = false

    private val handler = Handler(Looper.getMainLooper())
    private var reconnectAddress: String? = null
    private var reconnectContext: Context? = null
    private val pending = ArrayDeque<String>()

    @Synchronized
    fun connect(context: Context, address: String) {
        reconnectContext = context.applicationContext
        reconnectAddress = address

        if (connected && gatt != null && servicesReady) {
            Log.i(TAG, "Sudah terhubung ke $address")
            return
        }

        closeGatt()
        connectionStatus = "Menghubungkan ke MOCHI..."
        Log.i(TAG, "Memulai GATT ke $address")

        try {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val device = manager.adapter.getRemoteDevice(address)
            gatt = if (Build.VERSION.SDK_INT >= 23) {
                device.connectGatt(context.applicationContext, false, callback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context.applicationContext, false, callback)
            }
            if (gatt == null) {
                connectionStatus = "Gagal memulai koneksi BLE"
                scheduleReconnect()
            }
        } catch (e: SecurityException) {
            connectionStatus = "Izin Bluetooth ditolak"
            Log.e(TAG, "Izin koneksi BLE ditolak", e)
        } catch (e: Exception) {
            connectionStatus = "Gagal menghubungkan: ${e.message}"
            Log.e(TAG, "connectGatt gagal", e)
            scheduleReconnect()
        }
    }

    fun sendCommand(context: Context, command: String) {
        synchronized(pending) {
            if (pending.size >= 50) {
                Log.w(TAG, "Antrean penuh; perintah dibuang: $command")
                return
            }
            pending.addLast(command)
        }

        if (!connected || !servicesReady || rx == null || gatt == null) {
            val address = reconnectAddress
                ?: context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                    .getString(MainActivity.KEY_DEVICE, null)

            Log.i(TAG, "Perintah diantrekan; BLE belum siap: $command")

            if (!address.isNullOrBlank() && !connected) {
                connect(context.applicationContext, address)
            }
            return
        }

        writeNext()
    }

    @Synchronized
    private fun writeNext() {
        if (writeInProgress || !connected || !servicesReady) return

        val currentGatt = gatt ?: return
        val characteristic = rx ?: return
        val command = synchronized(pending) {
            if (pending.isEmpty()) null else pending.removeFirst()
        } ?: return

        try {
            val bytes = command.toByteArray(Charsets.UTF_8)
            writeInProgress = true

            val started = if (Build.VERSION.SDK_INT >= 33) {
                currentGatt.writeCharacteristic(
                    characteristic,
                    bytes,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ) == android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                characteristic.value = bytes
                @Suppress("DEPRECATION")
                currentGatt.writeCharacteristic(characteristic)
            }

            if (!started) {
                writeInProgress = false
                synchronized(pending) { pending.addFirst(command) }
                Log.e(TAG, "GATT menolak memulai write: $command")
            } else {
                Log.i(TAG, "Write dimulai: $command")
            }
        } catch (e: Exception) {
            writeInProgress = false
            synchronized(pending) { pending.addFirst(command) }
            Log.e(TAG, "Write gagal: $command", e)
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(
            g: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            Log.i(TAG, "Connection state status=$status state=$newState")

            if (g !== gatt) {
                try { g.close() } catch (_: Exception) {}
                return
            }

            if (status == BluetoothGatt.GATT_SUCCESS &&
                newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true
                servicesReady = false
                connectionStatus = "BLE tersambung, membaca service..."
                try {
                    if (!g.discoverServices()) {
                        connectionStatus = "Gagal memulai pembacaan service"
                        Log.e(TAG, "discoverServices mengembalikan false")
                        disconnectAndRetry(g)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "discoverServices gagal", e)
                    disconnectAndRetry(g)
                }
            } else {
                connected = false
                servicesReady = false
                writeInProgress = false
                rx = null
                tx = null
                connectionStatus = "BLE terputus (status=$status)"
                try { g.close() } catch (_: Exception) {}
                if (gatt === g) gatt = null
                scheduleReconnect()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== gatt) return

            if (status != BluetoothGatt.GATT_SUCCESS) {
                connectionStatus = "Gagal membaca service (status=$status)"
                Log.e(TAG, "Service discovery gagal: $status")
                disconnectAndRetry(g)
                return
            }

            val service = g.getService(MainActivity.SERVICE_UUID)
            if (service == null) {
                connectionStatus = "Service MOCHI tidak ditemukan"
                Log.e(TAG, "Service UUID tidak ditemukan: ${MainActivity.SERVICE_UUID}")
                disconnectAndRetry(g)
                return
            }

            val writeChar = service.getCharacteristic(MainActivity.RX_UUID)
            val notifyChar = service.getCharacteristic(MainActivity.TX_UUID)

            if (writeChar == null) {
                connectionStatus = "Karakteristik RX MOCHI tidak ditemukan"
                Log.e(TAG, "RX UUID tidak ditemukan")
                disconnectAndRetry(g)
                return
            }

            rx = writeChar
            tx = notifyChar
            servicesReady = true
            connectionStatus = "Terhubung ke MOCHI"
            Log.i(TAG, "Service dan RX ditemukan; TX=${notifyChar != null}")

            if (notifyChar != null) enableNotifications(g, notifyChar)

            sendCommand(
                reconnectContext ?: return,
                "TIME|${System.currentTimeMillis() / 1000L}"
            )
            writeNext()
        }

        private fun enableNotifications(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            try {
                val enabled = g.setCharacteristicNotification(characteristic, true)
                Log.i(TAG, "Set notification=$enabled")

                val descriptor = characteristic.getDescriptor(cccd)
                if (descriptor == null) {
                    Log.w(TAG, "CCCD tidak tersedia pada TX")
                    return
                }

                if (Build.VERSION.SDK_INT >= 33) {
                    val result = g.writeDescriptor(
                        descriptor,
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    )
                    Log.i(TAG, "Write CCCD result=$result")
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    val started = g.writeDescriptor(descriptor)
                    Log.i(TAG, "Write CCCD started=$started")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Gagal mengaktifkan notifikasi TX", e)
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (g !== gatt) return

            synchronized(this@MochiBleService) {
                writeInProgress = false
            }

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Write callback gagal: status=$status")
            } else {
                Log.i(TAG, "Write callback sukses: ${characteristic.uuid}")
            }
            writeNext()
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == MainActivity.TX_UUID) {
                handleResponse(value.toString(Charsets.UTF_8))
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == MainActivity.TX_UUID) {
                handleResponse(characteristic.value?.toString(Charsets.UTF_8).orEmpty())
            }
        }

        private fun handleResponse(text: String) {
            Log.i(TAG, "MOCHI TX: $text")
            if (text.contains("TIME_OK")) {
                connectionStatus = "Terhubung • Jam tersinkron"
            } else if (text.contains("TIME_ERROR")) {
                connectionStatus = "Terhubung • Sinkronisasi jam gagal"
            }
        }
    }

    private fun disconnectAndRetry(g: BluetoothGatt) {
        connected = false
        servicesReady = false
        writeInProgress = false
        rx = null
        tx = null
        try { g.disconnect() } catch (_: Exception) {}
        try { g.close() } catch (_: Exception) {}
        if (gatt === g) gatt = null
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        val address = reconnectAddress ?: return
        val context = reconnectContext ?: return
        handler.postDelayed({
            if (!connected && gatt == null) connect(context, address)
        }, 3000)
    }

    @Synchronized
    private fun closeGatt() {
        val old = gatt
        gatt = null
        connected = false
        servicesReady = false
        writeInProgress = false
        rx = null
        tx = null
        try { old?.disconnect() } catch (_: Exception) {}
        try { old?.close() } catch (_: Exception) {}
    }
}
