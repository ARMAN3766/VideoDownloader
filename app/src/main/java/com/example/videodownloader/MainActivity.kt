package com.example.videodownloader

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.WindowManager
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import coil.compose.AsyncImage
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

// ---------- Data & global state ----------

data class HItem(
    val title: String,
    val url: String,
    val time: Long,
    val uri: String,
    val path: String,
    val priv: Boolean,
    val audio: Boolean
)

object Dl {
    var url by mutableStateOf("")
    var title by mutableStateOf("")
    var thumb by mutableStateOf<String?>(null)
    var quality by mutableStateOf(720)
    var audio by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var status by mutableStateOf("")
    var history by mutableStateOf(listOf<HItem>())
}

val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

// ---------- Storage helpers ----------

fun loadHistory(p: SharedPreferences): List<HItem> {
    return try {
        val arr = JSONArray(p.getString("history", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            HItem(
                o.optString("title"), o.optString("url"), o.optLong("time"),
                o.optString("uri"), o.optString("path"),
                o.optBoolean("priv"), o.optBoolean("audio")
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}

fun saveHistory(p: SharedPreferences, list: List<HItem>) {
    val arr = JSONArray()
    list.forEach { h ->
        val o = JSONObject()
        o.put("title", h.title)
        o.put("url", h.url)
        o.put("time", h.time)
        o.put("uri", h.uri)
        o.put("path", h.path)
        o.put("priv", h.priv)
        o.put("audio", h.audio)
        arr.put(o)
    }
    p.edit().putString("history", arr.toString()).apply()
}

fun hashPin(pin: String, salt: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest((salt + pin).toByteArray())
        .joinToString("") { "%02x".format(it) }

fun savePin(p: SharedPreferences, pin: String) {
    val salt = UUID.randomUUID().toString()
    p.edit().putString("pin_salt", salt).putString("pin_hash", hashPin(pin, salt)).apply()
}

fun checkPin(p: SharedPreferences, pin: String): Boolean =
    hashPin(pin, p.getString("pin_salt", "") ?: "") == p.getString("pin_hash", "")

fun applySecure(a: Activity, on: Boolean) {
    if (on) a.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    else a.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
}

// ---------- Download logic ----------

fun exportToDownloads(ctx: Context, f: File, folder: String): Pair<String, String> {
    val mime = when (f.extension.lowercase()) {
        "mp4" -> "video/mp4"
        "mp3" -> "audio/mpeg"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "m4a" -> "audio/mp4"
        else -> "application/octet-stream"
    }
    if (Build.VERSION.SDK_INT >= 29) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + folder)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw Exception("ফাইল সেভ করা যায়নি")
        ctx.contentResolver.openOutputStream(uri)!!.use { out ->
            f.inputStream().use { it.copyTo(out) }
        }
        return Pair("Downloads/$folder/${f.name}", uri.toString())
    } else {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), folder)
        dir.mkdirs()
        val dest = File(dir, f.name)
        f.copyTo(dest, true)
        return Pair(dest.path, Uri.fromFile(dest).toString())
    }
}

fun doDownload(
    ctx: Context, url: String, q: Int, audio: Boolean, priv: Boolean, folder: String
): HItem {
    val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
    val work = File(base, "work_" + System.currentTimeMillis())
    work.mkdirs()
    try {
        val req = YoutubeDLRequest(url)
        req.addOption("--no-playlist")
        req.addOption("-o", work.absolutePath + "/%(title).100s.%(ext)s")
        if (audio) {
            req.addOption("-x")
            req.addOption("--audio-format", "mp3")
        } else {
            val sel = if (q == 0) "bv*+ba/b" else "bv*[height<=$q]+ba/b[height<=$q]/b"
            req.addOption("-f", sel)
            req.addOption("--merge-output-format", "mp4")
        }
        YoutubeDL.getInstance().execute(req, null) { p, _, _ ->
            Dl.status = "ডাউনলোড: ${p.toInt()}%"
        }
        val out = work.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") && !it.name.endsWith(".temp") }
            ?.maxByOrNull { it.length() }
            ?: throw Exception("ফাইল তৈরি হয়নি")
        val result: Pair<String, String> = if (priv) {
            val dir = File(base, "private")
            dir.mkdirs()
            File(dir, ".nomedia").createNewFile()
            val dest = File(dir, out.name)
            out.copyTo(dest, true)
            Pair("Private/" + dest.name, Uri.fromFile(dest).toString())
        } else {
            exportToDownloads(ctx, out, folder)
        }
        return HItem(out.nameWithoutExtension, url, System.currentTimeMillis(), result.second, result.first, priv, audio)
    } finally {
        work.deleteRecursively()
    }
}

fun startDownload(ctx: Context, prefs: SharedPreferences, priv: Boolean) {
    val url = Dl.url
    val q = Dl.quality
    val audio = Dl.audio
    val folder = (prefs.getString("folder", "VideoDownloader") ?: "VideoDownloader").ifBlank { "VideoDownloader" }
    Dl.busy = true
    Dl.status = "ডাউনলোড শুরু হচ্ছে..."
    appScope.launch {
        try {
            val item = withContext(Dispatchers.IO) { doDownload(ctx, url, q, audio, priv, folder) }
            Dl.history = listOf(item) + Dl.history
            saveHistory(prefs, Dl.history)
            Dl.status = "সম্পন্ন: ${item.path}"
        } catch (e: Exception) {
            Dl.status = "এরর: " + (e.message ?: "unknown").take(300)
        }
        Dl.busy = false
    }
}

fun fetchInfo() {
    val url = Dl.url
    Dl.busy = true
    Dl.status = "তথ্য আনা হচ্ছে..."
    appScope.launch {
        try {
            val r = withContext(Dispatchers.IO) {
                val req = YoutubeDLRequest(url)
                req.addOption("--no-playlist")
                YoutubeDL.getInstance().getInfo(req)
            }
            Dl.title = r.title ?: ""
            Dl.thumb = r.thumbnail
            Dl.status = ""
        } catch (e: Exception) {
            Dl.status = "Preview পাওয়া যায়নি। তবুও Download চেপে দেখুন।\n" + (e.message ?: "").take(200)
        }
        Dl.busy = false
    }
}

fun deleteItemFile(ctx: Context, h: HItem) {
    try {
        if (h.priv) {
            Uri.parse(h.uri).path?.let { File(it).delete() }
        } else {
            ctx.contentResolver.delete(Uri.parse(h.uri), null, null)
        }
    } catch (e: Exception) {
    }
}

// ---------- Activity ----------

class MainActivity : ComponentActivity() {
    private val unlocked = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("vd", Context.MODE_PRIVATE)
        applySecure(this, prefs.getBoolean("secure", true))
        Dl.history = loadHistory(prefs)
        setContent { AppTheme { Root(prefs, unlocked) } }
    }

    override fun onStop() {
        super.onStop()
        unlocked.value = false
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            content = content
        )
    }
}

@Composable
fun Root(prefs: SharedPreferences, unlocked: MutableState<Boolean>) {
    var hasPin by remember { mutableStateOf(prefs.contains("pin_hash")) }
    if (!hasPin) {
        SetupScreen(prefs) {
            hasPin = true
            unlocked.value = true
        }
    } else if (!unlocked.value) {
        LockScreen(prefs) { unlocked.value = true }
    } else {
        MainScreen(prefs)
    }
}

// ---------- 18+ setup & PIN lock ----------

@Composable
fun SetupScreen(prefs: SharedPreferences, onDone: () -> Unit) {
    var ageOk by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Welcome", style = MaterialTheme.typography.headlineMedium)
        Text("১৮+ নিশ্চিতকরণ ও PIN সেট করুন")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = ageOk, onCheckedChange = { ageOk = it })
            Text(
                "আমার বয়স ১৮ বা তার বেশি। আমি শুধু বৈধ ও সম্মতিপূর্ণ কনটেন্ট ডাউনলোড করব। অপ্রাপ্তবয়স্কদের কোনো কনটেন্ট নয়।",
                modifier = Modifier.weight(1f)
            )
        }
        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it.filter { c -> c.isDigit() }.take(8) },
            label = { Text("PIN (৪-৮ সংখ্যা)") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = pin2,
            onValueChange = { pin2 = it.filter { c -> c.isDigit() }.take(8) },
            label = { Text("PIN আবার লিখুন") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error)
        Button(
            onClick = {
                if (!ageOk) err = "১৮+ নিশ্চিত করুন"
                else if (pin.length < 4) err = "PIN কমপক্ষে ৪ সংখ্যা হতে হবে"
                else if (pin != pin2) err = "দুটি PIN মেলেনি"
                else {
                    savePin(prefs, pin)
                    onDone()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Continue") }
        Text("PIN ভুলে গেলে অ্যাপ আনইনস্টল করে আবার ইনস্টল করতে হবে।", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun LockScreen(prefs: SharedPreferences, onOk: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var fails by remember { mutableStateOf(0) }
    var lockUntil by remember { mutableStateOf(0L) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Locked", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it.filter { c -> c.isDigit() }.take(8) },
            label = { Text("PIN") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (err.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(err, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                val now = System.currentTimeMillis()
                if (now < lockUntil) {
                    err = "অপেক্ষা করুন: ${(lockUntil - now) / 1000 + 1} সেকেন্ড"
                } else if (checkPin(prefs, pin)) {
                    onOk()
                } else {
                    fails++
                    pin = ""
                    if (fails >= 5) {
                        lockUntil = now + 30000L
                        fails = 0
                        err = "৫ বার ভুল। ৩০ সেকেন্ড অপেক্ষা করুন"
                    } else {
                        err = "ভুল PIN"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Unlock") }
    }
}

// ---------- Main screen with tabs ----------

@Composable
fun MainScreen(prefs: SharedPreferences) {
    var tab by remember { mutableStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.Download, contentDescription = null) },
                    label = { Text("Download") }
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.History, contentDescription = null) },
                    label = { Text("History") }
                )
                NavigationBarItem(
                    selected = tab == 2, onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Settings") }
                )
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> DownloadTab(prefs)
                1 -> HistoryTab(prefs)
                else -> SettingsTab(prefs)
            }
        }
    }
}

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
    
