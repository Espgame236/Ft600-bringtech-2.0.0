package com.bringtech.obd

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity(), SensorEventListener {
    private val client = ObdClient()
    private var job: Job? = null
    private var data by mutableStateOf(ObdData())
    private var status by mutableStateOf("Toque na tela para conectar")
    private var showPicker by mutableStateOf(false)
    private var showSettings by mutableStateOf(false)
    private var gx by mutableStateOf(0f)
    private var gy by mutableStateOf(0f)
    private var rpmMax by mutableStateOf(8000)
    private val ratios = mutableStateListOf<Double>()
    private val prefs by lazy { getSharedPreferences("cfg", Context.MODE_PRIVATE) }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            showPicker = true
        }

    @SuppressLint("MissingPermission")
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

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Dashboard(
                    data, estimateGear(data.rpm, data.speed, ratios),
                    gx, gy, status, rpmMax, ::onTap
                )
                if (showPicker) {
                    val list = bonded()
                    AlertDialog(
                        onDismissRequest = { showPicker = false },
                        confirmButton = { TextButton(onClick = { showPicker = false }) { Text("Cancelar") } },
                        dismissButton = {
                            TextButton(onClick = { showPicker = false; showSettings = true }) {
                                Text("Configurações")
                            }
                        },
                        title = { Text("Escolha o scanner OBD2") },
                        text = {
                            if (list.isEmpty()) {
                                Text("Nenhum aparelho pareado. Pareie o scanner nas configurações de Bluetooth (senha 1234 ou 0000).")
                            } else {
                                Column {
                                    list.forEach { dev ->
                                        Text(
                                            dev.name ?: dev.address,
                                            Modifier.fillMaxWidth()
                                                .clickable { showPicker = false; connect(dev) }
                                                .padding(12.dp)
                                        )
                                    }
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
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            permLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
        } else {
            showPicker = true
        }
    }

    @SuppressLint("MissingPermission")
    private fun bonded(): List<BluetoothDevice> = try {
        BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.toList() ?: emptyList()
    } catch (e: SecurityException) {
        emptyList()
    }

    @SuppressLint("MissingPermission")
    private fun connect(dev: BluetoothDevice) {
        job?.cancel()
        job = lifecycleScope.launch(Dispatchers.IO) {
            try {
                status = "Conectando..."
                client.connect(dev)
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

    // Celular deitado de lado, tela virada pro motorista:
    // lateral = eixo X, frente/trás = -Z.
    override fun onSensorChanged(e: SensorEvent) {
        gx = gx * 0.85f + e.values[0] * 0.15f
        gy = gy * 0.85f + (-e.values[2]) * 0.15f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
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
