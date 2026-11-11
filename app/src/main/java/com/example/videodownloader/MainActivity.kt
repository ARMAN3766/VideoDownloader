package com.example.videodownloader

import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// এখানে অনুমোদিত সাইটের ডোমেইন যোগ করুন। যেমন: "example.com"
val ALLOWED_HOSTS = setOf<String>(
)

data class FormatItem(val id: String, val label: String)
data class VideoInfo(
    val title: String,
    val thumbnail: String?,
    val formats: List<FormatItem>
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { DownloaderScreen() } }
    }
}

fun isAllowed(url: String): Boolean {
    val host = Uri.parse(url).host?.removePrefix("www.") ?: return false
    return host.isNotBlank()
}

@Composable
fun DownloaderScreen() {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var info by remember { mutableStateOf<VideoInfo?>(null) }
    var selected by remember { mutableStateOf<FormatItem?>(null) }
    var audioOnly by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

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
            value = url,
            onValueChange = { url = it.trim() },
            label = { Text("Paste link") },
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            enabled = !busy && url.isNotBlank(),
            onClick = {
                if (!isAllowed(url)) {
                    status = "এই সাইট অনুমোদিত তালিকায় নেই"
                    return@Button
                }
                busy = true
                status = "তথ্য আনা হচ্ছে..."
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            val req = YoutubeDLRequest(url)
                            YoutubeDL.getInstance().getInfo(req)
                        }
                        val fmts = (result.formats ?: emptyList())
                            .filter { it.height > 0 && it.formatId != null }
                            .sortedBy { it.height }
                            .map { FormatItem(it.formatId!!, "${it.height}p (${it.ext})") }
                        info = VideoInfo(result.title ?: "Video", result.thumbnail, fmts)
                        selected = fmts.lastOrNull()
                        status = ""
                    } catch (e: Exception) {
                        status = "এরর: ${e.message}"
                    }
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Fetch") }

        info?.let { v ->
            if (v.thumbnail != null) {
                AsyncImage(
                    model = v.thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Text(v.title, style = MaterialTheme.typography.titleMedium)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Audio only")
                Spacer(Modifier.width(8.dp))
                Switch(checked = audioOnly, onCheckedChange = { audioOnly = it })
            }

            if (!audioOnly) {
                Text("Quality")
                v.formats.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = selected?.id == f.id,
                            onClick = { selected = f }
                        )
                        Text(f.label)
                    }
                }
            }

            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    status = "ডাউনলোড চলছে..."
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                val dir = File(
                                    Environment.getExternalStoragePublicDirectory(
                                        Environment.DIRECTORY_DOWNLOADS
                                    ), "VideoDownloader"
                                )
                                dir.mkdirs()
                                val req = YoutubeDLRequest(url)
                                req.addOption("-o", dir.absolutePath + "/%(title)s.%(ext)s")
                                if (audioOnly) {
                                    req.addOption("-x")
                                    req.addOption("--audio-format", "mp3")
                                } else {
                                    selected?.let {
                                        req.addOption("-f", "${it.id}+bestaudio/best")
                                    }
                                }
                                YoutubeDL.getInstance().execute(req, null) { p, _, _ ->
                                    status = "ডাউনলোড: ${p.toInt()}%"
                                }
                            }
                            status = "ডাউনলোড সম্পন্ন (Downloads/VideoDownloader)"
                        } catch (e: Exception) {
                            status = "এরর: ${e.message}"
                        }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Download") }
        }

        if (status.isNotEmpty()) Text(status)
    }
}
