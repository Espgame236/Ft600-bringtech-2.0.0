package com.bringtech.obd

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Orange = Color(0xFFFF8A00)
private val Gray = Color(0xFF9A9A9A)
private val Dim = Color(0xFF2A2A2A)

@Composable
fun Dashboard(
    d: ObdData, gear: String, gx: Float, gy: Float,
    status: String, rpmMax: Int, onTap: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().background(Color.Black)
            .clickable(onClick = onTap).padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        ShiftLights(d.rpm, rpmMax)
        Spacer(Modifier.height(4.dp))
        RpmBlock(d.rpm, rpmMax)
        Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GForce(gx, gy, Modifier.weight(1f).fillMaxHeight().padding(8.dp))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Gear", color = Orange, fontSize = 13.sp)
                Text(gear, color = Color.White, fontSize = 90.sp, lineHeight = 92.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1.3f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Speed", color = Orange, fontSize = 13.sp)
                Text("${d.speed}", color = Color.White, fontSize = 90.sp, lineHeight = 92.sp,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text("km/h", color = Gray, fontSize = 13.sp)
            }
        }
        BottomRow(d)
        Text(status, color = Gray, fontSize = 11.sp)
    }
}

@Composable
fun ShiftLights(rpm: Int, max: Int) {
    val n = 10
    val frac = rpm.toFloat() / max
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        for (i in 0 until n) {
            val on = frac >= 0.45f + 0.5f * i / n
            val c = when {
                i < 3 -> Color(0xFF22DD22)
                i < 6 -> Color(0xFFFF2020)
                else -> Color(0xFF3060FF)
            }
            Box(Modifier.size(12.dp).clip(CircleShape).background(if (on) c else c.copy(alpha = 0.15f)))
        }
    }
}

@Composable
fun RpmBlock(rpm: Int, max: Int) {
    val frac = (rpm.toFloat() / max).coerceIn(0f, 1f)
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("$rpm", color = Color.White, fontSize = 40.sp, lineHeight = 42.sp,
                fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
            Spacer(Modifier.width(8.dp))
            Text("$max RPM", color = Gray, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
        Canvas(Modifier.fillMaxWidth().height(20.dp)) {
            val path = Path().apply {
                moveTo(0f, size.height * 0.85f)
                lineTo(size.width, 0f)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(path, Dim)
            clipRect(right = size.width * frac) {
                drawPath(
                    path,
                    Brush.horizontalGradient(
                        listOf(Color(0xFFFFD400), Color(0xFFFF7A00), Color(0xFFFF1A1A)),
                        startX = 0f, endX = size.width
                    )
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (i in 0..max / 1000) Text("$i", color = Gray, fontSize = 10.sp)
        }
    }
}

@Composable
fun GForce(gx: Float, gy: Float, modifier: Modifier) {
    Canvas(modifier) {
        val r = minOf(size.width, size.height) / 2f
        val c = center
        val ring = Color(0xFF888888)
        drawCircle(ring, r, c, style = Stroke(2f))
        drawCircle(ring, r * 0.5f, c, style = Stroke(1f))
        drawLine(ring, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1f)
        drawLine(ring, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1f)
        val p = Offset(
            c.x + (gx / 9.81f).coerceIn(-1f, 1f) * r,
            c.y - (gy / 9.81f).coerceIn(-1f, 1f) * r
        )
        drawCircle(Color.White, 9f, p)
    }
}

@Composable
private fun BottomRow(d: ObdData) {
    Row(
        Modifier.fillMaxWidth().height(60.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        ValueBox("MAP", "${d.map}")
        ValueBox("Bat", String.format("%.1f", d.volts))
        Column(Modifier.width(160.dp)) {
            Text("TPS", color = Orange, fontSize = 11.sp)
            Box(Modifier.fillMaxWidth().height(18.dp).background(Dim)) {
                Box(
                    Modifier.fillMaxHeight().fillMaxWidth((d.tps / 100f).coerceIn(0f, 1f))
                        .background(Color(0xFF22DD22))
                )
            }
        }
        val ect = when {
            d.coolant >= 105 -> Color(0xFFFF2020)
            d.coolant >= 95 -> Orange
            else -> Color(0xFF22DD22)
        }
        VBox("ECT", "${d.coolant}", d.coolant / 120f, ect)
        VBox("Load", "${d.load}", d.load / 100f, Orange)
    }
}

@Composable
private fun ValueBox(label: String, value: String) {
    Column {
        Text(label, color = Orange, fontSize = 11.sp)
        Text(value, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun VBox(label: String, text: String, frac: Float, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Orange, fontSize = 11.sp)
        Box(Modifier.width(56.dp).height(40.dp).background(Dim)) {
            Box(
                Modifier.fillMaxWidth().fillMaxHeight(frac.coerceIn(0f, 1f))
                    .align(Alignment.BottomCenter).background(color)
            )
            Text(text, color = Color.Black, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center))
        }
    }
}
