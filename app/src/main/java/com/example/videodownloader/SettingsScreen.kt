package com.example.videodownloader

import android.app.Activity
import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun SettingsTab(prefs: SharedPreferences) {
    val ctx = LocalContext.current
    var secure by remember { mutableStateOf(prefs.getBoolean("secure", true)) }
    var privDef by remember { mutableStateOf(prefs.getBoolean("priv_default", false)) }
    var folder by remember { mutableStateOf(prefs.getString("folder", "VideoDownloader") ?: "VideoDownloader") }
    var newPin by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("স্ক্রিনশট ব্লক ও Recent apps এ লুকানো", modifier = Modifier.weight(1f))
            Switch(checked = secure, onCheckedChange = { v ->
                secure = v
                prefs.edit().putBoolean("secure", v).apply()
                (ctx as? Activity)?.let { applySecure(it, v) }
            })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ডিফল্টভাবে Private folder", modifier = Modifier.weight(1f))
            Switch(checked = privDef, onCheckedChange = { v ->
                privDef = v
                prefs.edit().putBoolean("priv_default", v).apply()
            })
        }
        OutlinedTextField(
            value = folder,
            onValueChange = { v ->
                val f = v.filter { c -> c.isLetterOrDigit() || c == '_' || c == '-' || c == ' ' }
                folder = f
                prefs.edit().putString("folder", f).apply()
            },
            label = { Text("Downloads ফোল্ডারের নাম") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = newPin,
            onValueChange = { newPin = it.filter { c -> c.isDigit() }.take(8) },
            label = { Text("নতুন PIN") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = {
            if (newPin.length >= 4) {
                savePin(prefs, newPin)
                newPin = ""
                msg = "PIN বদলানো হয়েছে"
            } else {
                msg = "PIN কমপক্ষে ৪ সংখ্যা"
            }
        }) { Text("PIN বদলান") }
        if (msg.isNotEmpty()) Text(msg)
        Text(
            "গোপনীয়তা: PIN শুধু হ্যাশ করে ফোনে থাকে। অ্যাপ কোনো অ্যানালিটিক্সে লিংক বা ডেটা পাঠায় না। Private folder এর ফাইল গ্যালারিতে দেখায় না।",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
