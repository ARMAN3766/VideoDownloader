package com.example.videodownloader

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageButton
import android.widget.Toast

class VideoDetectService : AccessibilityService() {

    private var wm: WindowManager? = null
    private var bubble: ImageButton? = null

    private val videoKeywords = listOf(
        "videoview", "playerview", "exoplayer", "surfaceview",
        "textureview", "player", "video", "youtube", "webview"
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (!Settings.canDrawOverlays(this)) { hideBubble(); return }
                val root = rootInActiveWindow ?: run { hideBubble(); return }
                if (findVideoNode(root)) showBubble() else hideBubble()
            }
        }
    }

    private fun findVideoNode(node: android.view.accessibility.AccessibilityNodeInfo): Boolean {
        val cls = node.className?.toString()?.lowercase() ?: ""
        if (videoKeywords.any { cls.contains(it) }) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findVideoNode(child)) return true
        }
        return false
    }

    private fun showBubble() {
        if (bubble != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        this.wm = wm
        val btn = ImageButton(this).apply {
            setImageResource(android.R.drawable.stat_sys_download_done)
            setBackgroundColor(Color.parseColor("#8B5CF6"))
            setOnClickListener { grabFromClipboard() }
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            170, 170, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = 24
        }
        runCatching { wm.addView(btn, lp) }
        bubble = btn
    }

    private fun hideBubble() {
        bubble?.let { runCatching { wm?.removeViewImmediate(it) } }
        bubble = null
    }

    private fun grabFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip: ClipData? = cm.primaryClip
        val url = if (clip != null && clip.itemCount > 0)
            clip.getItemAt(0).text?.toString() else null

        if (url.isNullOrBlank() || !url.startsWith("http")) {
            Toast.makeText(this, "আগে ভিডিওর লিংক কপি করো, তারপর বাটনে ট্যাপ করো", Toast.LENGTH_LONG).show()
            return
        }
        val i = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            putExtra("auto_url", url)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        i?.let { startActivity(it) }
        Toast.makeText(this, "লিংক পেয়েছি — ডাউনলোড শুরু হচ্ছে", Toast.LENGTH_SHORT).show()
    }

    override fun onInterrupt() { hideBubble() }

    override fun onDestroy() {
        super.onDestroy()
        hideBubble()
    }
}
