package com.example.videodownloader

import android.content.SharedPreferences
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DownloadTab(prefs: SharedPreferences) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    var priv by remember { mutableStateOf(prefs.getBoolean("priv_default", false)) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Video Downloader", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = Dl.url,
            onValueChange = { Dl.url = it.trim() },
            label = { Text("Paste link") },
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                clip.getText()?.text?.let { Dl.url = it.trim() }
            }) { Text("Paste") }
            OutlinedButton(
                enabled = !Dl.busy && Dl.url.isNotBlank(),
                onClick = { fetchInfo() }
            ) { Text("Preview") }
            OutlinedButton(onClick = {
                Dl.url = ""
                Dl.title = ""
                Dl.thumb = null
                Dl.status = ""
            }) { Text("Clear") }
        }
        if (Dl.thumb != null) {
            AsyncImage(
                model = Dl.thumb,
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (Dl.title.isNotEmpty()) Text(Dl.title, style = MaterialTheme.typography.titleMedium)

        Text("Quality")
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(360, 480, 720, 1080, 0).forEach { q ->
                FilterChip(
                    selected = Dl.quality == q,
                    onClick = { Dl.quality = q },
                    label = { Text(if (q == 0) "Original" else "${q}p") }
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Audio only (MP3)", modifier = Modifier.weight(1f))
            Switch(checked = Dl.audio, onCheckedChange = { Dl.audio = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Private folder", modifier = Modifier.weight(1f))
            Switch(checked = priv, onCheckedChange = { priv = it })
        }
        Button(
            enabled = !Dl.busy && Dl.url.isNotBlank(),
            onClick = { startDownload(ctx.applicationContext, prefs, priv) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Download") }
        if (Dl.status.isNotEmpty()) Text(Dl.status)
        Text(
            "DRM বা পেওয়ালের ভেতরের ভিডিও ডাউনলোড হবে না। ডাউনলোড চলার সময় অ্যাপ খোলা রাখুন।",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
fun HistoryTab(prefs: SharedPreferences) {
    val ctx = LocalContext.current
    var play by remember { mutableStateOf<HItem?>(null) }
    val fmt = remember { SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp)
    ) {
        Text("History", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                Dl.history = emptyList()
                saveHistory(prefs, emptyList())
            }) { Text("Clear history") }
            OutlinedButton(onClick = {
                Dl.history.forEach { deleteItemFile(ctx, it) }
                Dl.history = emptyList()
                saveHistory(prefs, emptyList())
            }) { Text("Clear + delete files") }
        }
        Spacer(Modifier.height(8.dp))
        if (Dl.history.isEmpty()) Text("কিছু নেই")
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(Dl.history) { h ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(h.title, maxLines = 2)
                        Text(
                            fmt.format(Date(h.time)) + " • " + (if (h.priv) "Private" else "Downloads") +
                                (if (h.audio) " • Audio" else ""),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(h.path, style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { play = h }) { Text("Play") }
                            TextButton(onClick = {
                                deleteItemFile(ctx, h)
                                Dl.history = Dl.history.filter { it != h }
                                saveHistory(prefs, Dl.history)
                            }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
    play?.let { h -> PlayerDialog(h) { play = null } }
}

@Composable
fun PlayerDialog(h: HItem, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)
    ) {
        Surface(shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(8.dp)) {
                AndroidView(
                    factory = { c ->
                        VideoView(c).apply {
                            val mc = MediaController(c)
                            mc.setAnchorView(this)
                            setMediaController(mc)
                            setVideoURI(Uri.parse(h.uri))
                            start()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(280.dp)
                )
                TextButton(onClick = onClose) { Text("Close") }
            }
        }
    }
}
