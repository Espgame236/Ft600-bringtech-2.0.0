package com.bringtech.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
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

// Relação RPM por km/h de cada marcha (aproximada). AJUSTE para o seu carro.
val GEAR_RATIOS = doubleArrayOf(120.0, 70.0, 48.0, 36.0, 29.0, 24.0)

fun estimateGear(rpm: Int, speed: Int): String {
    if (speed < 5 || rpm < 600) return "N"
    val r = ln(rpm.toDouble() / speed)
    var best = 0
    var diff = Double.MAX_VALUE
    GEAR_RATIOS.forEachIndexed { i, g ->
        val d = abs(r - ln(g))
        if (d < diff) { diff = d; best = i }
    }
    return (best + 1).toString()
}

class ObdClient {
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private val spp: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
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
