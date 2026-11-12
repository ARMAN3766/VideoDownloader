package com.example.videodownloader

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

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
