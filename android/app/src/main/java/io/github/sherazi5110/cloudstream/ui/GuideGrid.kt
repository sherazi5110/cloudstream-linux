package io.github.sherazi5110.cloudstream.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.sherazi5110.cloudstream.LiveTv
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val HALF_HOUR = 1800_000L
private const val HOURS = 12

/**
 * The TV guide: channels down the side, time across the top, one block per programme.
 * Every row and the time ruler share one horizontal scroll, so they move together; the channel
 * names stay put. On a TV the remote moves through the programmes. Pressing any of them plays the channel.
 */
@Composable
fun GuideGrid(channels: List<LiveTv.Channel>, now: Long, guideVersion: Int, wide: Boolean, onPlay: (LiveTv.Channel) -> Unit, modifier: Modifier = Modifier) {
    val start = remember(guideVersion) { (System.currentTimeMillis() / HALF_HOUR) * HALF_HOUR - HALF_HOUR }
    val end = start + HOURS * 3600_000L
    val perMin = if (wide) 4f else 3f
    val nameW = if (wide) 200.dp else 112.dp
    val rowH = if (wide) 64.dp else 58.dp
    fun x(t: Long): Dp = ((t - start) / 60_000f * perMin).dp
    val total = x(end)
    val h = rememberScrollState()
    val clock = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val nowX = x(now)

    Column(modifier.background(Palette.surface, RoundedCornerShape(14.dp)).border(1.dp, Palette.outline.copy(alpha = 0.5f), RoundedCornerShape(14.dp))) {
        // Time ruler.
        Row(Modifier.fillMaxWidth().height(34.dp).background(Palette.surfaceHigh, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))) {
            Box(Modifier.width(nameW).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                Text("Today", style = MaterialTheme.typography.labelMedium, color = Palette.muted, modifier = Modifier.padding(start = 12.dp))
            }
            Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(h)) {
                Box(Modifier.width(total).fillMaxHeight()) {
                    var t = start
                    while (t < end) {
                        val hour = t % 3600_000L == 0L
                        Text(clock.format(Date(t)), style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (hour) FontWeight.Bold else FontWeight.Normal,
                            color = if (hour) Color.White else Palette.muted,
                            modifier = Modifier.offset(x = x(t) + 6.dp).align(Alignment.CenterStart))
                        t += HALF_HOUR
                    }
                    Box(Modifier.offset(x = nowX).width(2.dp).fillMaxHeight().background(Palette.accent))
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(channels, key = { it.key }) { c ->
                val programmes = remember(c, guideVersion, start) { LiveTv.programmes(c, start, end) }
                Row(Modifier.fillMaxWidth().height(rowH)) {
                    Row(Modifier.width(nameW).fillMaxHeight().card(RoundedCornerShape(8.dp), focusScale = 1.02f) { onPlay(c) }
                        .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (wide) ChannelLogo(c, 34.dp)
                        Text(c.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(h)) {
                        Box(Modifier.width(total).fillMaxHeight()) {
                            if (programmes.isEmpty()) {
                                Box(Modifier.padding(3.dp).width(total - 6.dp).fillMaxHeight()
                                    .border(1.dp, Palette.outline, RoundedCornerShape(8.dp)), contentAlignment = Alignment.CenterStart) {
                                    Text("No guide information", style = MaterialTheme.typography.bodySmall, color = Palette.muted, modifier = Modifier.padding(start = 10.dp))
                                }
                            }
                            programmes.forEach { p ->
                                val left = x(p.start.coerceAtLeast(start)); val right = x(p.stop.coerceAtMost(end))
                                val live = now in p.start until p.stop
                                val past = p.stop <= now
                                Column(Modifier.offset(x = left + 2.dp).padding(vertical = 3.dp).width((right - left - 4.dp).coerceAtLeast(2.dp)).fillMaxHeight()
                                    .card(RoundedCornerShape(8.dp), focusScale = 1.03f) { onPlay(c) }
                                    .background(if (live) Palette.accent.copy(alpha = 0.22f) else Palette.surfaceHigh, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                                    verticalArrangement = Arrangement.Center) {
                                    Text(p.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        color = if (past) Palette.muted else Color.White)
                                    Text("${clock.format(Date(p.start))} – ${clock.format(Date(p.stop))}", style = MaterialTheme.typography.labelSmall,
                                        color = Palette.muted, maxLines = 1)
                                }
                            }
                            Box(Modifier.offset(x = nowX).width(2.dp).fillMaxHeight().background(Palette.accent))
                        }
                    }
                }
            }
        }
    }
}
