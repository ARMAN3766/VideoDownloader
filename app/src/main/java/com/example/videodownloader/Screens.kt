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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DownloadTab(prefs: SharedPreferences) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    var priv by remember { mutableStateOf(prefs.getBoolean("priv_default", false)) }
    var mode by remember { mutableStateOf(0) } // 0=ভিডিও, 1=ছবি
    var imgUrl by remember { mutableStateOf("") }
    var imgBusy by remember { mutableStateOf(false) }
    var imgMsg by remember { mutableStateOf("") }

    // ভাসমান বাটন থেকে আসা লিংক অটো ডাউনলোড
    LaunchedEffect(Unit) {
        Auto.url?.let { u ->
            Dl.url = u
            Auto.url = null
            if (!Dl.busy && Dl.url.isNotBlank()) startDownload(ctx.applicationContext, prefs, priv)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Video Downloader", style = MaterialTheme.typography.headlineSmall)

        // মোড সিলেক্ট: ভিডিও / ছবি
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == 0,
                onClick = { mode = 0 },
                label = { Text("ভিডিও") }
            )
            FilterChip(
                selected = mode == 1,
                onClick = { mode = 1 },
                label = { Text("ছবি") }
            )
        }

        if (mode == 1) {
            // ---------- ছবি ডাউনলোড মোড ----------
            OutlinedTextField(
                value = imgUrl,
                onValueChange = { imgUrl = it.trim() },
                label = { Text("ছবির লিংক Paste করো") },
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    clip.getText()?.text?.let { imgUrl = it.trim() }
                }) { Text("Paste") }
                OutlinedButton(onClick = {
                    imgUrl = ""
                    imgMsg = ""
                }) { Text("Clear") }
            }
            Button(
                enabled = !imgBusy && imgUrl.isNotBlank(),
                onClick = {
                    imgBusy = true
                    imgMsg = "ছবি ডাউনলোড হচ্ছে..."
                    appScope.launch {
                        imgMsg = withContext(Dispatchers.IO) {
                            try {
                                downloadImage(ctx.applicationContext, imgUrl)
                            } catch (e: Exception) {
                                "ব্যর্থ: " + (e.message ?: "unknown").take(200)
                            }
                        }
                        imgBusy = false
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("ছবি ডাউনলোড") }
            if (imgMsg.isNotEmpty()) Text(imgMsg)
            Text(
                "ছবি গ্যালারির Pictures/VideoDownloader ফোল্ডারে সেভ হবে।",
                style = MaterialTheme.typography.bodySmall
            )
        } else {

        // ---------- ভিডিও ডাউনলোড মোড (আগের মতোই) ----------
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
}

@Composable
fun HistoryTab(prefs: SharedPreferences) {
    val ctx = LocalContext.current
    var play by remember { mutableStateOf<HItem?>(null) }
    var del by remember { mutableStateOf<HItem?>(null) }
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
            // FIX: শুধু লিস্ট মুছবে, ফোনের ফাইল টিকবে
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
                            TextButton(onClick = { del = h }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
    play?.let { h -> PlayerDialog(h) { play = null } }

    // প্রতিটি আইটেমের ডিলিট কনফার্মেশন
    del?.let { h ->
        AlertDialog(
            onDismissRequest = { del = null },
            title = { Text("মুছে ফেলবে?") },
            text = { Text(h.title) },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        // শুধু হিস্টরি থেকে মুছুন — ফোনের ফাইল থাকবে
                        Dl.history = Dl.history.filter { it != h }
                        saveHistory(prefs, Dl.history)
                        del = null
                    }) { Text("শুধু এন্ট্রি") }
                    TextButton(onClick = {
                        // ফাইলসহ মুছুন
                        deleteItemFile(ctx, h)
                        Dl.history = Dl.history.filter { it != h }
                        saveHistory(prefs, Dl.history)
                        del = null
                    }) { Text("ফাইলসহ মুছুন", color = MaterialTheme.colorScheme.error) }
                }
            },
            dismissButton = {
                TextButton(onClick = { del = null }) { Text("বাতিল") }
            }
        )
    }
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
