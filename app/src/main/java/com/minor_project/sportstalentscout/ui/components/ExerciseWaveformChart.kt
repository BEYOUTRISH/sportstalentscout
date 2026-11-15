package com.minor_project.sportstalentscout.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.minor_project.sportstalentscout.data.models.AngleSample

@Composable
fun ExerciseWaveformChart(
    samples: List<AngleSample>,
    exerciseType: String = ""
) {

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {

        Column(
            modifier = Modifier.padding(16.dp)
        ) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Exercise Motion",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(Color(0xFF00E676), CircleShape)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "Motion",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .width(12.dp)
                                .height(2.dp)
                                .background(Color.White.copy(alpha = 0.6f))
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "Rep Target",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
            ) {

                if (samples.isEmpty()) return@Canvas

                // ---------- SMOOTH THE DATA (5-point moving average) ----------
                val smoothedAngles = samples
                    .map { it.angle }
                    .windowed(size = 5, step = 1, partialWindows = true) { window ->
                        window.average()
                    }

                val maxAngle = smoothedAngles.maxOrNull() ?: 0.0
                val minAngle = smoothedAngles.minOrNull() ?: 0.0
                val range = (maxAngle - minAngle).coerceAtLeast(1.0)

                val width = size.width
                val height = size.height

                val paddingTop = 24.dp.toPx()
                val paddingBottom = 20.dp.toPx()
                val availableHeight = (height - paddingTop - paddingBottom).coerceAtLeast(1f)

                // For Bicep Curls, Push-ups, and Squats, contraction (active rep) is at MIN angle (flexion).
                // For Shoulder Press, overhead lockout is at MAX angle (extension).
                // Either way, curling up / pushing rep peaks UPWARD on the chart.
                val isPeakAtMaxAngle = (exerciseType == "Shoulder Press")

                // ---------- DRAW THRESHOLD LINE ----------
                val thresholdAngle = when (exerciseType) {
                    "Bicep Curls"    -> 65.0
                    "Push-ups"       -> 95.0
                    "Squats"         -> 100.0
                    "Shoulder Press" -> 145.0
                    else             -> null
                }

                val thresholdNorm = if (thresholdAngle != null && range > 15.0) {
                    if (isPeakAtMaxAngle) {
                        ((thresholdAngle - minAngle) / range).coerceIn(0.55, 0.92)
                    } else {
                        ((maxAngle - thresholdAngle) / range).coerceIn(0.55, 0.92)
                    }
                } else {
                    0.80
                }

                val thresholdY = height - paddingBottom - (thresholdNorm.toFloat() * availableHeight)

                drawLine(
                    color = Color.White.copy(alpha = 0.40f),
                    start = Offset(0f, thresholdY),
                    end = Offset(width, thresholdY),
                    strokeWidth = 1.5f.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f)
                )

                // ---------- BUILD WAVEFORM PATH (PEAKING UPWARD) ----------
                val strokePath = Path()
                val fillPath = Path()

                smoothedAngles.forEachIndexed { i, angle ->
                    val x = (i.toFloat() / (smoothedAngles.size - 1).coerceAtLeast(1)) * width

                    val normalized = if (isPeakAtMaxAngle) {
                        ((angle - minAngle) / range).coerceIn(0.0, 1.0)
                    } else {
                        ((maxAngle - angle) / range).coerceIn(0.0, 1.0)
                    }

                    val y = height - paddingBottom - (normalized.toFloat() * availableHeight)

                    if (i == 0) {
                        strokePath.moveTo(x, y)
                        fillPath.moveTo(x, height - paddingBottom)
                        fillPath.lineTo(x, y)
                    } else {
                        strokePath.lineTo(x, y)
                        fillPath.lineTo(x, y)
                    }
                }

                fillPath.lineTo(width, height - paddingBottom)
                fillPath.close()

                // Subtle gradient fill under motion curve
                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF00E676).copy(alpha = 0.25f),
                            Color(0xFF00E676).copy(alpha = 0.02f)
                        ),
                        startY = paddingTop,
                        endY = height - paddingBottom
                    )
                )

                // Motion line stroke
                drawPath(
                    path = strokePath,
                    color = Color(0xFF00E676),
                    style = Stroke(
                        width = 3.5f.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                )
            }
        }
    }
}
