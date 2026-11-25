package pl.aatrip.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Połączenie BLE z adapterem ELM327. Metody są blokujące,
 * więc wolno je wywoływać tylko z wątku roboczego.
 * Uprawnienia Bluetooth sprawdza ekran przed utworzeniem klienta.
 */
@SuppressLint("MissingPermission")
class ElmBleClient(
    private val context: Context,
    private val device: BluetoothDevice,
    private val log: (String) -> Unit,
) {
    /** Para charakterystyk: z jednej odbieramy, do drugiej piszemy. */
    class Channel(
        val notify: BluetoothGattCharacteristic,
        val write: BluetoothGattCharacteristic,
    ) {
        override fun toString() = "notify=${short(notify.uuid)} write=${short(write.uuid)}"
    }

    @Volatile var isConnected = false
        private set

    private var gatt: BluetoothGatt? = null
    @Volatile private var active: Channel? = null

    private var servicesReady = CountDownLatch(1)
    @Volatile private var descriptorWritten = CountDownLatch(1)
    @Volatile private var chunkWritten = CountDownLatch(1)

    private val buffer = StringBuilder()
    private val responses = LinkedBlockingQueue<String>()

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                isConnected = true
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false
                log("Rozłączono (status $status)")
                servicesReady.countDown()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            servicesReady.countDown()
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            descriptorWritten.countDown()
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            chunkWritten.countDown()
        }

        // Android 13 i nowsze
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            receive(characteristic, value)
        }

        // Starsze wersje
        @Deprecated("Zastąpione w Androidzie 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                receive(characteristic, characteristic.value ?: return)
            }
        }
    }

    /** Łączy się i zwraca możliwe kanały komunikacji, najbardziej prawdopodobne na początku. */
    fun connect(): List<Channel> {
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        if (!servicesReady.await(20, TimeUnit.SECONDS) || !isConnected) return emptyList()
        val services = gatt?.services.orEmpty()

        for (service in services) {
            log("Usługa ${short(service.uuid)}")
            for (c in service.characteristics) {
                log("  charakterystyka ${short(c.uuid)} właściwości=0x${Integer.toHexString(c.properties)}")
            }
        }

        val channels = mutableListOf<Channel>()
        for ((serviceId, notifyId, writeId) in KNOWN) {
            val service = services.firstOrNull { it.uuid == serviceId } ?: continue
            val notify = service.getCharacteristic(notifyId) ?: continue
            val write = service.getCharacteristic(writeId) ?: continue
            channels.add(Channel(notify, write))
        }
        // Nieznany adapter: dowolna usługa z odbiorem i zapisem.
        for (service in services) {
            if (short(service.uuid) in listOf("1800", "1801", "180a")) continue
            val notify = service.characteristics.firstOrNull { it.properties and NOTIFY_MASK != 0 } ?: continue
            val write = service.characteristics.firstOrNull { it.properties and WRITE_MASK != 0 } ?: continue
            if (channels.none { it.notify.uuid == notify.uuid }) channels.add(Channel(notify, write))
        }
        return channels
    }

    /** Włącza powiadomienia na wybranym kanale. */
    fun open(channel: Channel): Boolean {
        val g = gatt ?: return false
        active = channel
        if (!g.setCharacteristicNotification(channel.notify, true)) return false
        val descriptor = channel.notify.getDescriptor(CCCD) ?: return false
        val value = if (channel.notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        descriptorWritten = CountDownLatch(1)
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(descriptor, value) == 0 // 0 = powodzenie
        } else {
            @Suppress("DEPRECATION")
            run {
                descriptor.value = value
                g.writeDescriptor(descriptor)
            }
        }
        return started && descriptorWritten.await(5, TimeUnit.SECONDS)
    }

    /** Wysyła polecenie i czeka na znak zachęty ">". Zwraca null przy braku odpowiedzi. */
    fun send(command: String, timeoutMs: Long = 3000): String? {
        val g = gatt ?: return null
        val channel = active ?: return null
        if (!isConnected) return null
        synchronized(buffer) {
            buffer.setLength(0)
            responses.clear()
        }
        val bytes = (command + "\r").toByteArray(Charsets.US_ASCII)
        for (start in bytes.indices step CHUNK) {
            val chunk = bytes.copyOfRange(start, minOf(start + CHUNK, bytes.size))
            if (!writeChunk(g, channel.write, chunk)) return null
        }
        return responses.poll(timeoutMs, TimeUnit.MILLISECONDS)
    }

    fun close() {
        isConnected = false
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    private fun writeChunk(g: BluetoothGatt, target: BluetoothGattCharacteristic, data: ByteArray): Boolean {
        val noResponse = target.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val type = if (noResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        chunkWritten = CountDownLatch(1)
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(target, data, type) == 0 // 0 = powodzenie
        } else {
            @Suppress("DEPRECATION")
            run {
                target.writeType = type
                target.value = data
                g.writeCharacteristic(target)
            }
        }
        return started && chunkWritten.await(2, TimeUnit.SECONDS)
    }

    private fun receive(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (characteristic.uuid != active?.notify?.uuid) return
        synchronized(buffer) {
            buffer.append(String(value, Charsets.US_ASCII).replace("\u0000", ""))
            val end = buffer.indexOf(">")
            if (end >= 0) {
                responses.offer(buffer.substring(0, end).trim())
                buffer.delete(0, end + 1)
            }
        }
    }

    private companion object {
        const val CHUNK = 20
        const val NOTIFY_MASK = BluetoothGattCharacteristic.PROPERTY_NOTIFY or
            BluetoothGattCharacteristic.PROPERTY_INDICATE
        const val WRITE_MASK = BluetoothGattCharacteristic.PROPERTY_WRITE or
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE

        val CCCD: UUID = uuid("2902")

        /** Typowe układy usług w adapterach ELM327 BLE: usługa, odbiór, zapis. */
        val KNOWN: List<Triple<UUID, UUID, UUID>> = listOf(
            Triple(
                UUID.fromString("e7810a71-73ae-499d-8c15-faa9aef0c3f2"),
                UUID.fromString("bef8d6c9-9c21-4c9e-b632-bd58c1009f9f"),
                UUID.fromString("bef8d6c9-9c21-4c9e-b632-bd58c1009f9f"),
            ),
            Triple(uuid("18f0"), uuid("2af0"), uuid("2af1")),
            Triple(uuid("fff0"), uuid("fff1"), uuid("fff2")),
            Triple(uuid("ffe0"), uuid("ffe1"), uuid("ffe1")),
        )

        fun uuid(short: String): UUID = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")

        fun short(id: UUID): String {
            val text = id.toString()
            return if (text.endsWith("-0000-1000-8000-00805f9b34fb")) text.substring(4, 8) else text
        }
    }
}
