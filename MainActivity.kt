package com.bringtech.obd

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

data class Found(val dev: BluetoothDevice, val ble: Boolean)

@SuppressLint("MissingPermission")
class MainActivity : ComponentActivity(), SensorEventListener {
    private val client = ObdClient()
    private var job: Job? = null
    private var scanJob: Job? = null
    private var data by mutableStateOf(ObdData())
    private var status by mutableStateOf("Toque na tela para conectar")
    private var showPicker by mutableStateOf(false)
    private var showSettings by mutableStateOf(false)
    private var scanning by mutableStateOf(false)
    private var gx by mutableStateOf(0f)
    private var gy by mutableStateOf(0f)
    private var rpmMax by mutableStateOf(8000)
    private val ratios = mutableStateListOf<Double>()
    private val found = mutableStateListOf<Found>()
    private val prefs by lazy { getSharedPreferences("cfg", Context.MODE_PRIVATE) }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasPerms()) {
                showPicker = true
                startScan()
            }
        }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == BluetoothDevice.ACTION_FOUND) {
                deviceFrom(i)?.let { add(it, false) }
            }
        }
    }

    private val bleCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            add(result.device, true)
        }
    }

    @Suppress("DEPRECATION")
    private fun deviceFrom(i: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33)
            i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    private fun add(d: BluetoothDevice, ble: Boolean) {
        if (found.none { it.dev.address == d.address && it.ble == ble }) found.add(Found(d, ble))
    }

    private fun neededPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasPerms(): Boolean = neededPerms().all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let {
            it.hide(WindowInsetsCompat.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        rpmMax = prefs.getInt("rpmMax", 8000)
        val saved = prefs.getString("ratios", null)?.split(",")?.mapNotNull { it.toDoubleOrNull() }
        ratios.clear()
        ratios.addAll(if (saved != null && saved.size == GEAR_RATIOS.size) saved else GEAR_RATIOS)

        ContextCompat.registerReceiver(
            this, receiver, IntentFilter(BluetoothDevice.ACTION_FOUND), ContextCompat.RECEIVER_EXPORTED
        )
        if (!hasPerms()) permLauncher.launch(neededPerms())

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Dashboard(
                    data, estimateGear(data.rpm, data.speed, ratios),
                    gx, gy, status, rpmMax, ::onTap
                )
                if (showPicker) {
                    val paired = bonded()
                    val others = found.filter { f ->
                        f.dev.name != null &&
                            !(!f.ble && paired.any { it.address == f.dev.address })
                    }
                    AlertDialog(
                        onDismissRequest = { showPicker = false; stopScan() },
                        confirmButton = {
                            Row {
                                TextButton(onClick = { startScan() }) { Text("Procurar") }
                                TextButton(onClick = {
                                    showPicker = false; stopScan(); showSettings = true
                                }) { Text("Config.") }
                                TextButton(onClick = { showPicker = false; stopScan() }) { Text("Cancelar") }
                            }
                        },
                        title = { Text(if (scanning) "Escolha o scanner (procurando...)" else "Escolha o scanner") },
                        text = {
                            Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                                if (paired.isEmpty() && others.isEmpty()) {
                                    Text(
                                        if (scanning) "Procurando aparelhos..."
                                        else "Nada encontrado. Plugue o scanner, ligue a ignição e toque em Procurar."
                                    )
                                }
                                paired.forEach { d ->
                                    DeviceRow(d.name ?: d.address, "Pareado") { connect(d, false) }
                                }
                                others.forEach { f ->
                                    DeviceRow(
                                        f.dev.name ?: f.dev.address,
                                        if (f.ble) "BLE" else "Bluetooth"
                                    ) { connect(f.dev, f.ble) }
                                }
                            }
                        }
                    )
                }
                if (showSettings) {
                    Dialog(
                        onDismissRequest = { showSettings = false },
                        properties = DialogProperties(usePlatformDefaultWidth = false)
                    ) {
                        SettingsScreen(
                            rpmMax = rpmMax,
                            ratios = ratios.toList(),
                            liveRpm = data.rpm,
                            liveSpeed = data.speed,
                            onSave = { max, list ->
                                rpmMax = max
                                ratios.clear()
                                ratios.addAll(list)
                                prefs.edit()
                                    .putInt("rpmMax", max)
                                    .putString("ratios", list.joinToString(","))
                                    .apply()
                                showSettings = false
                            },
                            onClose = { showSettings = false }
                        )
                    }
                }
            }
        }
    }

    private fun onTap() {
        if (!hasPerms()) {
            permLauncher.launch(neededPerms())
            return
        }
        showPicker = true
        startScan()
    }

    private fun bonded(): List<BluetoothDevice> = try {
        BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.toList() ?: emptyList()
    } catch (e: SecurityException) {
        emptyList()
    }

    private fun startScan() {
        val ad = BluetoothAdapter.getDefaultAdapter() ?: return
        if (!ad.isEnabled) {
            status = "Ligue o Bluetooth do celular"
            return
        }
        found.clear()
        scanning = true
        try {
            if (ad.isDiscovering) ad.cancelDiscovery()
            ad.startDiscovery()
        } catch (e: SecurityException) {}
        try { ad.bluetoothLeScanner?.startScan(bleCb) } catch (e: Exception) {}
        scanJob?.cancel()
        scanJob = lifecycleScope.launch {
            delay(12000)
            stopScan()
        }
    }

    private fun stopScan() {
        scanJob?.cancel()
        val ad = BluetoothAdapter.getDefaultAdapter()
        try { ad?.cancelDiscovery() } catch (e: Exception) {}
        try { ad?.bluetoothLeScanner?.stopScan(bleCb) } catch (e: Exception) {}
        scanning = false
    }

    private fun connect(dev: BluetoothDevice, ble: Boolean) {
        showPicker = false
        stopScan()
        job?.cancel()
        job = lifecycleScope.launch(Dispatchers.IO) {
            try {
                status = "Conectando..."
                val useBle = ble || dev.type == BluetoothDevice.DEVICE_TYPE_LE
                client.connect(dev, useBle, applicationContext)
                client.init()
                status = "Conectado: ${dev.name ?: dev.address}"
                var i = 0
                while (isActive) {
                    data = client.poll(data, i++)
                }
            } catch (e: Exception) {
                status = "Erro: ${e.message} — toque para reconectar"
            } finally {
                client.close()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        super.onPause()
        (getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScan()
        try { unregisterReceiver(receiver) } catch (e: Exception) {}
    }

    // Celular deitado de lado, tela virada pro motorista:
    // lateral = eixo X, frente/trás = -Z.
    override fun onSensorChanged(e: SensorEvent) {
        gx = gx * 0.85f + e.values[0] * 0.15f
        gy = gy * 0.85f + (-e.values[2]) * 0.15f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

@Composable
private fun DeviceRow(title: String, tag: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)) {
        Text(title, fontSize = 16.sp)
        Text(tag, fontSize = 11.sp)
    }
}

private fun fmt(v: Double): String = String.format(Locale.US, "%.1f", v)

@Composable
fun SettingsScreen(
    rpmMax: Int,
    ratios: List<Double>,
    liveRpm: Int,
    liveSpeed: Int,
    onSave: (Int, List<Double>) -> Unit,
    onClose: () -> Unit
) {
    var max by remember { mutableStateOf(rpmMax) }
    val texts = remember {
        mutableStateListOf<String>().also { l -> ratios.forEach { l.add(fmt(it)) } }
    }
    val live: Double? = if (liveSpeed >= 5 && liveRpm >= 600) liveRpm.toDouble() / liveSpeed else null

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("Configurações", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))

            Text("RPM máximo do painel")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { max = (max - 500).coerceAtLeast(3000) }) { Text("−") }
                Text("$max", Modifier.padding(horizontal = 20.dp), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Button(onClick = { max = (max + 500).coerceAtMost(12000) }) { Text("+") }
            }

            Spacer(Modifier.height(16.dp))
            Text("Marchas (RPM por km/h)", fontWeight = FontWeight.Bold)
            Text(
                "Ande com o carro engatado numa marcha, com RPM estável, e toque em Capturar. " +
                    "Coloque 0 para desativar uma marcha.",
                fontSize = 12.sp
            )
            Text(
                "Agora: $liveRpm rpm · $liveSpeed km/h" + (live?.let { " → ${fmt(it)}" } ?: ""),
                fontSize = 12.sp
            )
            Spacer(Modifier.height(4.dp))

            texts.forEachIndexed { i, t ->
                Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}ª", Modifier.width(36.dp), fontSize = 18.sp)
                    OutlinedTextField(
                        value = t,
                        onValueChange = { texts[i] = it },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.width(140.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        enabled = live != null,
                        onClick = { live?.let { texts[i] = fmt(it) } }
                    ) { Text("Capturar") }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row {
                TextButton(onClick = onClose) { Text("Cancelar") }
                Spacer(Modifier.width(12.dp))
                Button(onClick = {
                    val list = texts.map { it.replace(',', '.').toDoubleOrNull() ?: 0.0 }
                    onSave(max, list)
                }) { Text("Salvar") }
            }
        }
    }
}
