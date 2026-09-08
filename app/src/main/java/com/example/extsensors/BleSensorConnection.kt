package com.example.extsensors

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.content.Context
import android.content.pm.PackageManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.abs

class BleSensorConnection(
    private val context: Context,
    private val kind: Kind,
    private val device: BluetoothDevice,
    private var initialRateHz: Double,
    private val onValue: (String, Double, String) -> Unit,
    private val onRate: (Double) -> Unit,
    private val onState: (String) -> Unit
) {
    enum class Kind { LINESCALE, IMU }

    private var gatt: BluetoothGatt? = null
    private var buffer = ByteArray(0)
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var sampleCount = 0
    private var windowStartMs = System.currentTimeMillis()

    fun connect() {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return onState("Bluetooth permission denied")
        gatt = device.connectGatt(context, false, callback)
    }

    fun close() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    /** Only the WT901 IMU supports a remote sampling-rate command; LineScale's rate is a hardware switch. */
    fun setSampleRate(hz: Double) {
        initialRateHz = hz
        if (kind != Kind.IMU) return
        val write = writeCharacteristic ?: return
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return
        write.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        write.value = imuRateCommand(hz)
        gatt?.writeCharacteristic(write)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                onState("Connected to ${device.name ?: device.address}")
                gatt.discoverServices()
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) onState("Disconnected")
        }

        // Search every discovered service for the target UUID: the notify and write
        // characteristics are not guaranteed to live under a service with a matching UUID.
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val notifyUuid = UUID.fromString(if (kind == Kind.IMU) IMU_NOTIFY else SCALE_NOTIFY)
            val writeUuid = UUID.fromString(if (kind == Kind.IMU) IMU_WRITE else SCALE_WRITE)
            val notify = gatt.services.asSequence().mapNotNull { it.getCharacteristic(notifyUuid) }.firstOrNull()
            writeCharacteristic = gatt.services.asSequence().mapNotNull { it.getCharacteristic(writeUuid) }.firstOrNull()
            if (notify == null) return onState("Sensor characteristics not found")
            if (!gatt.setCharacteristicNotification(notify, true)) return onState("Could not enable notifications")
            val descriptor = notify.getDescriptor(CCCD)
            if (descriptor == null) return onState("Notification descriptor not found")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }

        // The start command must wait for the CCCD write to finish; GATT allows only one
        // outstanding operation at a time, so issuing both immediately silently drops the second.
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val write = writeCharacteristic
            if (write == null) {
                onState("Streaming")
                return
            }
            val command = if (kind == Kind.IMU) imuRateCommand(initialRateHz) else "A\r\nX".toByteArray()
            write.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            write.value = command
            if (!gatt.writeCharacteristic(write)) onState("Failed to send start command")
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            onState(if (status == BluetoothGatt.GATT_SUCCESS) "Streaming" else "Start command failed ($status)")
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            parse(characteristic.value)
        }
    }

    private fun imuRateCommand(hz: Double) =
        byteArrayOf(0xff.toByte(), 0xaa.toByte(), 0x03, rateCodeFor(hz).toByte(), 0x00)

    private fun rateCodeFor(hz: Double): Int {
        RATES_HZ.forEachIndexed { index, rate -> if (abs(hz - rate) < 0.001) return index + 1 }
        return 6 // default to 10 Hz
    }

    private fun parse(bytes: ByteArray) {
        buffer += bytes
        if (kind == Kind.LINESCALE) parseScale() else parseImu()
    }

    private fun tickRate() {
        sampleCount++
        val now = System.currentTimeMillis()
        val elapsed = now - windowStartMs
        if (elapsed >= 1000) {
            onRate(sampleCount * 1000.0 / elapsed)
            sampleCount = 0
            windowStartMs = now
        }
    }

    private fun parseScale() {
        while (true) {
            val end = buffer.indexOf(13)
            if (end < 0) return
            val frame = buffer.copyOfRange(0, end + 1)
            buffer = buffer.copyOfRange(end + 1, buffer.size)
            if (frame.size != 20 || (frame[0].toInt().toChar() !in charArrayOf('R', 'O', 'C'))) continue
            var sum = 0
            for (index in 0 until 17) sum += frame[index].toInt() and 0xff
            val check = sum % 100
            if (frame[17].toInt() - 48 != check / 10 || frame[18].toInt() - 48 != check % 10) continue
            val force = frame.copyOfRange(1, 7).toString(Charsets.US_ASCII).trim().toDoubleOrNull() ?: continue
            val reference = frame.copyOfRange(8, 14).toString(Charsets.US_ASCII).trim().toDoubleOrNull() ?: continue
            // Matches the documented byte-16 unit code (N=kN, G=kgf, B=lbf); anything else is unknown, not a silent guess.
            val unit = when (frame[15].toInt().toChar()) { 'N' -> "kN"; 'G' -> "kgf"; 'B' -> "lbf"; else -> "?" }
            tickRate()
            onValue("force", force, unit)
            onValue("reference", reference, unit)
        }
    }

    private fun parseImu() {
        while (buffer.size >= 20) {
            val header = buffer.indexOf(0x55.toByte())
            if (header < 0) { buffer = ByteArray(0); return }
            buffer = buffer.copyOfRange(header, buffer.size)
            if (buffer.size < 20) return
            val type = buffer[1].toInt() and 0xff
            if (type == 0x61) {
                tickRate()
                val values = (0 until 3).map { readShort(buffer, 2 + it * 2) * 16.0 / 32768.0 }
                values.forEachIndexed { index, value -> onValue("accel_${AXES[index]}", value, "g") }
                val gyro = (0 until 3).map { readShort(buffer, 8 + it * 2) * 2000.0 / 32768.0 }
                gyro.forEachIndexed { index, value -> onValue("gyro_${AXES[index]}", value, "deg/s") }
                val angle = (0 until 3).map { readShort(buffer, 14 + it * 2) * 180.0 / 32768.0 }
                angle.forEachIndexed { index, value -> onValue("angle_${AXES[index]}", value, "deg") }
            } else if (type == 0x71 && buffer[2].toInt() == 0x3a && buffer[3].toInt() == 0) {
                val mag = (0 until 3).map { readShort(buffer, 4 + it * 2).toDouble() }
                mag.forEachIndexed { index, value -> onValue("mag_${AXES[index]}", value, "mG") }
            }
            buffer = buffer.copyOfRange(20, buffer.size)
        }
    }

    private fun readShort(data: ByteArray, offset: Int): Short =
        ByteBuffer.wrap(data, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short

    private fun hasPermission(permission: String) =
        android.os.Build.VERSION.SDK_INT < 31 || context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val SCALE_NOTIFY = "00001002-0000-1000-8000-00805f9b34fb"
        private const val SCALE_WRITE = "00001001-0000-1000-8000-00805f9b34fb"
        private const val IMU_NOTIFY = "0000ffe4-0000-1000-8000-00805f9a34fb"
        private const val IMU_WRITE = "0000ffe9-0000-1000-8000-00805f9a34fb"
        private val AXES = arrayOf("x", "y", "z")
        val RATES_HZ = doubleArrayOf(0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0)
    }
}

private fun ByteArray.indexOf(value: Byte): Int {
    for (index in indices) if (this[index] == value) return index
    return -1
}
