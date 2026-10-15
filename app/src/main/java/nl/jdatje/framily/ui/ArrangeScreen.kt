package nl.jdatje.framily.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * A big preview of the wallpaper where the clock, the current weather and the forecast can be
 * dragged to any spot. Changes apply right away (also to the real wallpaper).
 */
@Composable
fun ArrangeScreen(vm: MainViewModel, onDone: () -> Unit) {
    BackHandler(onBack = onDone)
    val s by vm.state.collectAsStateWithLifecycle()
    val (pw, ph) = vm.previewSize
    var box by remember { mutableStateOf(IntSize.Zero) }
    var dragging by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Drag the clock, the weather and the caption to where you want them.",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
        )
        Box(
            Modifier
                .weight(1f)
                .aspectRatio(pw.toFloat() / ph)
                .clip(RoundedCornerShape(18.dp))
                .onSizeChanged { box = it }
                .pointerInput(Unit) {
                    var total = Offset.Zero
                    detectDragGestures(
                        onDragStart = { start ->
                            if (box.width == 0) return@detectDragGestures
                            val el = vm.elementAt(start.x * pw / box.width, start.y * ph / box.height)
                            dragging = el
                            total = Offset.Zero
                            if (el != null) vm.startDrag(el)
                        },
                        onDrag = { change, amount ->
                            val el = dragging ?: return@detectDragGestures
                            change.consume()
                            total += amount
                            vm.drag(el, total.x / box.width, total.y / box.height)
                        },
                        onDragEnd = { dragging = null; vm.endDrag() },
                        onDragCancel = { dragging = null; vm.endDrag() },
                    )
                },
        ) {
            s.preview?.let { Image(bitmap = it, contentDescription = "Wallpaper preview", modifier = Modifier.fillMaxSize()) }
            // Dashed outlines show what can be moved; a center line helps lining things up.
            Canvas(Modifier.fillMaxSize()) {
                val sx = size.width / pw
                val sy = size.height / ph
                val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
                val placed = s.placed ?: return@Canvas
                listOf("clock" to placed.clock, "current" to placed.current, "forecast" to placed.forecast, "caption" to placed.caption).forEach { (name, r) ->
                    if (r == null) return@forEach
                    val pad = 6.dp.toPx()
                    drawRoundRect(
                        color = if (dragging == name) Color.White else Color.White.copy(alpha = 0.55f),
                        topLeft = Offset(r.left * sx - pad, r.top * sy - pad),
                        size = Size(r.width() * sx + pad * 2, r.height() * sy + pad * 2),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()),
                        style = Stroke(width = 2.dp.toPx(), pathEffect = dash),
                    )
                }
                if (dragging != null) {
                    drawLine(Color.White.copy(alpha = 0.35f), Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), strokeWidth = 1.dp.toPx())
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { vm.resetLayout() }) { Text("Reset", color = Color.White) }
            Text(
                "Dragging the clock turns off \"behind heads\". Reset brings it back.",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onDone) { Text("Done") }
        }
    }
}
