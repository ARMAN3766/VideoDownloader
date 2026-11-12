package com.example.videodownloader

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
