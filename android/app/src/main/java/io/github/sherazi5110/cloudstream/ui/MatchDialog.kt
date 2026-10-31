package io.github.sherazi5110.cloudstream.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.LoadResponse
import io.github.sherazi5110.cloudstream.Metadata

/** "Wrong match?": search TMDB and pin the right film or series for this title (like the Linux app). */
@Composable
fun MatchDialog(r: LoadResponse, onClose: () -> Unit, onChanged: () -> Unit) {
    val (cleanTitle, cleanYear) = remember(r) { Metadata.cleanTitle(r.name) }
    var query by remember { mutableStateOf(listOfNotNull(cleanTitle, (r.year ?: cleanYear)?.toString()).joinToString(" ")) }
    var searchRun by remember { mutableIntStateOf(0) }
    var results by remember { mutableStateOf<List<Metadata.Candidate>?>(null) }
    LaunchedEffect(searchRun) {
        results = null
        results = runCatching { Metadata.searchCandidates(query) }.getOrDefault(emptyList())
    }
    val chosen = remember { Metadata.chosenMatch(r.apiName, r.url) }
    fun done(action: () -> Unit) { action(); onClose(); onChanged() }

    SimpleDialog("Choose the right title", onClose, extra = {
        PillButton("Automatic", null, primary = false, compact = true) {
            if (chosen != null) done { Metadata.chooseMatch(r.apiName, r.url, null, automatic = true) }
        }
        PillButton("No match", null, primary = false, compact = true) { done { Metadata.chooseMatch(r.apiName, r.url, null) } }
    }) {
        Text("Ratings, artwork, cast and similar titles for “${r.name}” come from the one you pick.", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { searchRun++ }))
            PillButton("Search", Icons.Filled.Search, compact = true) { searchRun++ }
        }
        val list = results
        when {
            list == null -> Spinner(Modifier.fillMaxWidth().padding(24.dp))
            list.isEmpty() -> Text("Nothing found. Try another spelling, or the original title.", style = MaterialTheme.typography.bodyMedium)
            else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                list.take(20).forEach { c ->
                    val selected = chosen?.first == c.mediaType && chosen.second == c.id
                    Row(Modifier.fillMaxWidth().card(RoundedCornerShape(10.dp), focusScale = 1.02f) { done { Metadata.chooseMatch(r.apiName, r.url, c) } }
                        .padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        NetImage(c.poster, Modifier.width(46.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)))
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(c.title + if (selected) "  ✓" else "", style = MaterialTheme.typography.titleSmall, color = if (selected) Palette.accent else androidx.compose.ui.graphics.Color.White)
                            Text(listOfNotNull(c.year?.toString(), if (c.mediaType == "tv") "Series" else "Film").joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                            c.overview?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
            }
        }
    }
}
