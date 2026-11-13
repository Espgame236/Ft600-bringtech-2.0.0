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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity(), SensorEventListener {
    private val client = ObdClient()
    private var job: Job? = null
    private var data by mutableStateOf(ObdData())
    private var status by mutableStateOf("Toque na tela para conectar")
    private var showPicker by mutableStateOf(false)
    private var gx by mutableStateOf(0f)
    private var gy by mutableStateOf(0f)

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
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Dashboard(data, estimateGear(data.rpm, data.speed), gx, gy, status, 8000, ::onTap)
                if (showPicker) {
                    val list = bonded()
                    AlertDialog(
                        onDismissRequest = { showPicker = false },
                        confirmButton = { TextButton(onClick = { showPicker = false }) { Text("Cancelar") } },
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
