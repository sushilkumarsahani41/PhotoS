package app.photos

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

object TimelineIndex {
    fun groupStarts(counts: List<Int>): List<Int> {
        var index = 0
        return counts.map { count -> require(count >= 0); index.also { index += count } }
    }
    fun atFraction(fraction: Float, count: Int): Int = if (count <= 1) 0 else (fraction.coerceIn(0f, 1f) * (count - 1)).roundToInt()
}

@Composable
fun TimelineRail(dates: List<LocalDate>, current: Int, active: Boolean, modifier: Modifier = Modifier, jump: (Int) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var selected by remember(dates) { mutableIntStateOf(0) }
    val latestJump by rememberUpdatedState(jump)
    // Visible while scrolling or dragging, then fades out so it never covers photos at rest.
    var lingering by remember { mutableStateOf(false) }
    LaunchedEffect(active, dragging) { if (active || dragging) lingering = true else { kotlinx.coroutines.delay(1200); lingering = false } }
    val alpha by animateFloatAsState(if (lingering || dragging) 1f else 0f, label = "rail")
    BoxWithConstraints(modifier.width(44.dp).padding(vertical = 12.dp).graphicsLayer { this.alpha = alpha }) {
        val travelDp = (maxHeight - 44.dp).coerceAtLeast(0.dp)
        val travel = with(LocalDensity.current) { (maxHeight - 44.dp).coerceAtLeast(1.dp).toPx() }
        val inset = with(LocalDensity.current) { 22.dp.toPx() }
        val index = (if (dragging) selected else current).coerceIn(0, dates.lastIndex)
        val fraction = if (dates.size < 2) 0f else index.toFloat() / dates.lastIndex
        fun move(y: Float) { selected = TimelineIndex.atFraction((y - inset) / travel, dates.size); latestJump(selected) }
        Box(Modifier.fillMaxSize().semantics {
            contentDescription = "Photo timeline, drag to jump to a date"
            stateDescription = dates[index].toString()
            progressBarRangeInfo = ProgressBarRangeInfo(index.toFloat(), 0f..dates.lastIndex.toFloat())
            setProgress { value -> latestJump(value.roundToInt().coerceIn(0, dates.lastIndex)); true }
        }.then(if (alpha > 0.05f) Modifier.pointerInput(dates, travel) {
            detectDragGestures(onDragStart = { dragging = true; move(it.y) }, onDragEnd = { dragging = false }, onDragCancel = { dragging = false }) { change, _ -> change.consume(); move(change.position.y) }
        } else Modifier)) {
            Box(Modifier.align(Alignment.TopEnd).padding(end = 6.dp).offset(y = travelDp * fraction).size(width = 28.dp, height = 44.dp).shadow(6.dp, RoundedCornerShape(14.dp)).background(Color(0xFFEDEDED), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                Text("⇅", color = Color.Black, fontWeight = FontWeight.Bold)
            }
            if (dragging) Surface(color = Color(0xFF2A2A2A), shape = RoundedCornerShape(12.dp), shadowElevation = 6.dp, modifier = Modifier.align(Alignment.TopEnd).offset(x = (-44).dp, y = travelDp * fraction + 4.dp).wrapContentWidth(Alignment.End, unbounded = true)) {
                Text(dates[index].format(DateTimeFormatter.ofPattern("MMM yyyy")), modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.titleSmall, color = Color.White)
            }
        }
    }
}
