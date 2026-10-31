package io.github.sherazi5110.cloudstream.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.lagradost.cloudstream3.SearchResponse
import io.github.sherazi5110.cloudstream.App
import io.github.sherazi5110.cloudstream.R

/**
 * Click, long-press and remote focus in one: on Android TV the focused item gets a white ring
 * and grows a little (the Linux app's look); on phones a long press opens the item's menu.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.card(
    shape: Shape = RoundedCornerShape(10.dp),
    focusScale: Float = 1.06f,
    onFocus: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = this.then(Modifier.cardImpl(shape, focusScale, onFocus, onLongClick, onClick))

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.cardImpl(
    shape: Shape, focusScale: Float, onFocus: (() -> Unit)?, onLongClick: (() -> Unit)?, onClick: () -> Unit,
): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) focusScale else 1f, label = "focus")
    this
        .scale(scale)
        .onFocusChanged {
            focused = it.isFocused
            if (it.isFocused) onFocus?.invoke()
        }
        .then(if (focused) Modifier.border(3.dp, Color.White, shape) else Modifier)
        .clip(shape)
        .combinedClickable(onLongClick = onLongClick, onClick = onClick)
}

/** A network image with the extension's headers (some sites refuse posters without a Referer). */
@Composable
fun NetImage(
    url: String?,
    modifier: Modifier = Modifier,
    headers: Map<String, String>? = null,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
    /** A grey box behind the image (posters); off for logos drawn over artwork. */
    placeholder: Boolean = true,
) {
    val context = LocalContext.current
    if (url.isNullOrBlank()) {
        if (placeholder) Box(modifier.background(Palette.surfaceHigh))
        return
    }
    val request = remember(url, headers) {
        ImageRequest.Builder(context).data(url).crossfade(true).apply {
            if (!headers.isNullOrEmpty()) httpHeaders(NetworkHeaders.Builder().apply { headers.forEach { (k, v) -> set(k, v) } }.build())
        }.build()
    }
    AsyncImage(model = request, contentDescription = null, contentScale = contentScale, alignment = alignment,
        modifier = if (placeholder) modifier.background(Palette.surfaceHigh) else modifier)
}

@Composable
fun Logo(size: Dp = 36.dp) {
    Image(painterResource(R.drawable.logo), contentDescription = "CloudStream", modifier = Modifier.size(size))
}

/** "★ 7.9" in a dark chip, for posters. */
@Composable
fun ScoreBadge(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Star, null, tint = Color.White, modifier = Modifier.size(10.dp))
        Spacer(Modifier.width(3.dp))
        Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

fun SearchResponse.scoreText(): String? = score?.toString(10)?.takeIf { it != "0" && it != "0.0" }

/** A poster in a catalog row or a grid: image with its score, the title under it. */
@Composable
fun PosterCard(
    item: SearchResponse,
    width: Dp,
    modifier: Modifier = Modifier,
    onFocus: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Column(modifier.width(width)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                .card(onFocus = onFocus, onLongClick = onLongClick, onClick = onClick)
        ) {
            NetImage(item.posterUrl, Modifier.fillMaxSize(), item.posterHeaders)
            item.scoreText()?.let { ScoreBadge(it, Modifier.align(Alignment.TopStart).padding(6.dp)) }
        }
        Text(
            item.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
    }
}

/** A Continue Watching card: wide artwork, title, what's left, and a progress bar. */
@Composable
fun WideCard(
    title: String,
    subtitle: String,
    imageUrl: String?,
    progress: Float,
    width: Dp,
    modifier: Modifier = Modifier,
    onFocus: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier.width(width).aspectRatio(16f / 9f)
            .card(onFocus = onFocus, onLongClick = onLongClick, onClick = onClick)
    ) {
        NetImage(imageUrl, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.9f))))
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f), maxLines = 1,
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp))
            ProgressLine(progress)
        }
    }
}

@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.25f))) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).background(Palette.accent))
    }
}

/** A rounded chip: "PG-13", "TMDB 8.2", a season… */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, label: String? = null, labelColor: Color = Palette.muted, outlined: Boolean = false) {
    Row(
        modifier
            .then(if (outlined) Modifier.border(1.5.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(6.dp)) else Modifier.background(Palette.surfaceHigh, RoundedCornerShape(6.dp)))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        label?.let { Text(it, color = labelColor, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
        Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
    }
}

/** The big rounded buttons ("Play", "More Info", "Resume S1 E2"). */
@Composable
fun PillButton(
    text: String,
    icon: ImageVector?,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .card(shape = shape, focusScale = 1.04f, onClick = onClick)
            .background(if (primary) Palette.accent else Color.White.copy(alpha = 0.16f), shape)
            .padding(horizontal = if (compact) 18.dp else 22.dp, vertical = if (compact) 9.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        icon?.let { Icon(it, null, tint = Color.White, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A round icon with a caption under it (My list · Watched · Trailer · Sources). */
@Composable
fun CircleAction(icon: ImageVector, label: String, modifier: Modifier = Modifier, tint: Color = Color.White, onClick: () -> Unit) {
    Column(modifier.width(76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(44.dp).card(shape = CircleShape, onClick = onClick).background(Palette.surfaceHigh, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, tint = tint, modifier = Modifier.size(22.dp)) }
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp), maxLines = 1)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (action != null && onAction != null) {
            Text(action, style = MaterialTheme.typography.labelMedium, modifier = Modifier.card(RoundedCornerShape(6.dp), onClick = onAction).padding(6.dp))
        }
    }
}

@Composable
fun Spinner(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Palette.accent, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
    }
}

/** A friendly empty state with an optional button. */
@Composable
fun EmptyState(title: String, text: String, modifier: Modifier = Modifier, button: String? = null, onButton: (() -> Unit)? = null) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Logo(64.dp)
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(text, style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (button != null && onButton != null) PillButton(button, null, Modifier.padding(top = 8.dp), onClick = onButton)
    }
}

/** Phone or TV, for layouts that differ. */
val isTv: Boolean get() = App.isTv

/** Minutes as "2h 4m". */
fun formatMinutes(minutes: Int): String = if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
