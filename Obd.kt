package com.bringtech.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ln

data class ObdData(
    val rpm: Int = 0,
    val speed: Int = 0,
    val coolant: Int = 0,
    val load: Int = 0,
    val tps: Int = 0,
    val map: Int = 0,
    val volts: Float = 0f
)

// Relação RPM por km/h padrão de cada marcha (aproximada). Dá pra ajustar dentro do app.
val GEAR_RATIOS = listOf(120.0, 70.0, 48.0, 36.0, 29.0, 24.0)

fun estimateGear(rpm: Int, speed: Int, ratios: List<Double>): String {
    if (speed < 5 || rpm < 600) return "N"
    val r = ln(rpm.toDouble() / speed)
    var best = -1
    var diff = Double.MAX_VALUE
    ratios.forEachIndexed { i, g ->
        if (g > 0) {
            val d = abs(r - ln(g))
            if (d < diff) { diff = d; best = i }
        }
    }
    return if (best < 0) "-" else (best + 1).toString()
}

// ---------- BLE: transforma o canal serial BLE em InputStream/OutputStream ----------

class QueueInputStream : InputStream() {
    private val q = ConcurrentLinkedQueue<Int>()
    fun push(b: ByteArray?) {
        if (b != null) for (x in b) q.add(x.toInt() and 0xFF)
    }
    override fun available(): Int = q.size
    override fun read(): Int = q.poll() ?: -1
}

class BleOutputStream(private val link: BleLink) : OutputStream() {
    override fun write(b: Int) { link.write(byteArrayOf(b.toByte())) }
    override fun write(b: ByteArray, off: Int, len: Int) { link.write(b.copyOfRange(off, off + len)) }
}

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class BleLink(private val ctx: Context) {
    val inStream = QueueInputStream()
    private var gatt: BluetoothGatt? = null
    private var wChar: BluetoothGattCharacteristic? = null
    @Volatile private var error: String? = null
    private val ready = CountDownLatch(1)
    private val writeSem = Semaphore(0)
    private val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else {
                if (error == null) error = "BLE desconectou"
                ready.countDown()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            var n: BluetoothGattCharacteristic? = null
            var w: BluetoothGattCharacteristic? = null
            for (s in g.services) {
                val su = s.uuid.toString().lowercase()
                if (su.startsWith("00001800") || su.startsWith("00001801")) continue
                var sn: BluetoothGattCharacteristic? = null
                var sw: BluetoothGattCharacteristic? = null
                for (c in s.characteristics) {
                    val p = c.properties
                    if (sn == null && (p and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                                BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) sn = c
                    if (sw == null && (p and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0) sw = c
                }
                if (sn != null && sw != null) { n = sn; w = sw; break }
            }
            if (n == null || w == null) {
                error = "BLE: não achei o canal serial deste aparelho"
                ready.countDown()
                return
            }
            wChar = w
            g.setCharacteristicNotification(n, true)
            val d = n.getDescriptor(cccd)
            if (d == null) {
                ready.countDown()
                return
            }
            d.value = if ((n.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0)
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            g.writeDescriptor(d)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            ready.countDown()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            inStream.push(c.value)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeSem.release()
        }
    }

    fun connect(dev: BluetoothDevice) {
        gatt = dev.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
        if (!ready.await(15, TimeUnit.SECONDS)) throw IOException("BLE: tempo esgotado")
        error?.let { throw IOException(it) }
    }

    fun write(data: ByteArray) {
        val g = gatt ?: throw IOException("desconectado")
        val c = wChar ?: throw IOException("desconectado")
        var off = 0
        while (off < data.size) {
            val chunk = data.copyOfRange(off, minOf(off + 20, data.size))
            off += 20
            c.value = chunk
            c.writeType = if ((c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            writeSem.drainPermits()
            if (!g.writeCharacteristic(c)) throw IOException("falha ao enviar (BLE)")
            writeSem.tryAcquire(1000, TimeUnit.MILLISECONDS)
        }
    }

    fun close() {
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
    }
}

// ---------- Cliente OBD (ELM327) ----------

class ObdClient {
    private var socket: BluetoothSocket? = null
    private var bleLink: BleLink? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private val spp: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice, ble: Boolean, ctx: Context) {
        BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
        if (ble) {
            val l = BleLink(ctx)
            l.connect(device)
            bleLink = l
            input = l.inStream
            output = BleOutputStream(l)
            return
        }
        var s = device.createRfcommSocketToServiceRecord(spp)
        try {
            s.connect()
        } catch (e: IOException) {
            try { s.close() } catch (_: IOException) {}
            s = device.createInsecureRfcommSocketToServiceRecord(spp)
            s.connect()
        }
        socket = s
        input = s.inputStream
        output = s.outputStream
    }

    fun init() {
        send("ATZ", 3000)
        send("ATE0"); send("ATL0"); send("ATS0"); send("ATH0"); send("ATSP0")
        val r = send("0100", 10000).uppercase()
        if (r.contains("UNABLE") || r.contains("ERROR") || r.contains("NO DATA")) {
            throw IOException("Scanner não achou a ECU (ligue a ignição)")
        }
    }

    fun close() {
        try { socket?.close() } catch (_: IOException) {}
        socket = null
        bleLink?.close()
        bleLink = null
    }

    private fun send(cmd: String, timeoutMs: Long = 1500): String {
        val o = output ?: throw IOException("desconectado")
        val i = input ?: throw IOException("desconectado")
        while (i.available() > 0) i.read()
        o.write((cmd + "\r").toByteArray())
        o.flush()
        val sb = StringBuilder()
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (i.available() > 0) {
                val c = i.read()
                if (c < 0) throw IOException("desconectado")
                if (c == '>'.code) break
                sb.append(c.toChar())
            } else {
                Thread.sleep(5)
            }
        }
        return sb.toString().replace("\r", " ").trim()
    }

    private fun readPid(pid: String, n: Int): IntArray? {
        val r = send("01$pid").replace(Regex("\\s"), "").uppercase()
        val k = r.indexOf("41$pid")
        if (k < 0) return null
        val d = r.substring(k + 4)
        if (d.length < n * 2) return null
        return try {
            IntArray(n) { d.substring(it * 2, it * 2 + 2).toInt(16) }
        } catch (e: NumberFormatException) { null }
    }

    private fun volts(): Float? {
        val m = Regex("([0-9]+\\.?[0-9]*)\\s*V").find(send("ATRV")) ?: return null
        return m.groupValues[1].toFloatOrNull()
    }

    // RPM, velocidade e TPS toda volta; os demais em rodízio.
    fun poll(p: ObdData, step: Int): ObdData {
        var d = p
        readPid("0C", 2)?.let { d = d.copy(rpm = (it[0] * 256 + it[1]) / 4) }
        readPid("0D", 1)?.let { d = d.copy(speed = it[0]) }
        readPid("11", 1)?.let { d = d.copy(tps = it[0] * 100 / 255) }
        when (step % 4) {
            0 -> readPid("05", 1)?.let { d = d.copy(coolant = it[0] - 40) }
            1 -> readPid("04", 1)?.let { d = d.copy(load = it[0] * 100 / 255) }
            2 -> readPid("0B", 1)?.let { d = d.copy(map = it[0]) }
            3 -> volts()?.let { d = d.copy(volts = it) }
        }
        return d
    }
}
