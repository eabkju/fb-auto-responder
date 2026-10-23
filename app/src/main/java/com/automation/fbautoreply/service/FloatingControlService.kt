package com.automation.fbautoreply.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.automation.fbautoreply.MainActivity
import com.automation.fbautoreply.R
import com.automation.fbautoreply.data.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

class FloatingControlService : Service() {

    companion object {
        const val CHANNEL_ID = "floating_controller_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP_SERVICE = "com.automation.fbautoreply.STOP_FLOATING_SERVICE"

        var isRunning = false
            private set
    }

    private var windowManager: WindowManager? = null
    private var floatingView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForegroundServiceWithNotification()

        if (Settings.canDrawOverlays(this)) {
            initFloatingOverlay()
            observeState()
        } else {
            Toast.makeText(this, "Overlay permission not granted", Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        serviceScope.cancel()
        removeFloatingView()
        super.onDestroy()
    }

    private fun startForegroundServiceWithNotification() {
        val channelName = getString(R.string.floating_service_notification_channel)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Channel for AutoReply floating overlay controller"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.floating_service_notification_title))
            .setContentText(getString(R.string.floating_service_notification_text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initFloatingOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val density = resources.displayMetrics.density
        val widthPx = (110 * density).toInt()
        val heightPx = (46 * density).toInt()

        layoutParams = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (resources.displayMetrics.widthPixels - widthPx - (16 * density).toInt())
            y = (resources.displayMetrics.heightPixels * 0.35f).toInt()
        }

        val pillView = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            elevation = 12f * density
        }

        setupTouchListener(pillView)
        floatingView = pillView
        updateWidgetAppearance(PreferenceManager.isBotActive(this))

        try {
            windowManager?.addView(floatingView, layoutParams)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to display overlay: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeState() {
        serviceScope.launch {
            PreferenceManager.botActiveFlow.collect { isActive ->
                updateWidgetAppearance(isActive)
            }
        }
    }

    private fun updateWidgetAppearance(isActive: Boolean) {
        val pill = floatingView ?: return
        val density = resources.displayMetrics.density

        val backgroundDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f * density
            if (isActive) {
                setColor(Color.parseColor("#2E7D32")) // Material Forest Green
                setStroke((2 * density).toInt(), Color.parseColor("#81C784"))
            } else {
                setColor(Color.parseColor("#C62828")) // Material Dark Red
                setStroke((2 * density).toInt(), Color.parseColor("#EF9A9A"))
            }
        }

        pill.background = backgroundDrawable
        pill.setTextColor(Color.WHITE)
        pill.text = if (isActive) "● BOT: ON" else "■ BOT: OFF"
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener(view: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        var isLongPressHandled = false

        val longPressRunnable = Runnable {
            if (!isDragging) {
                isLongPressHandled = true
                PreferenceManager.clearCache()
                Toast.makeText(this, "Comment signature cache cleared!", Toast.LENGTH_SHORT).show()
            }
        }

        view.setOnTouchListener { _, motionEvent ->
            val params = layoutParams ?: return@setOnTouchListener false

            when (motionEvent.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = motionEvent.rawX
                    initialTouchY = motionEvent.rawY
                    isDragging = false
                    isLongPressHandled = false
                    mainHandler.postDelayed(longPressRunnable, 700L)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val deltaX = motionEvent.rawX - initialTouchX
                    val deltaY = motionEvent.rawY - initialTouchY

                    if (abs(deltaX) > 15 || abs(deltaY) > 15) {
                        isDragging = true
                        mainHandler.removeCallbacks(longPressRunnable)
                    }

                    if (isDragging) {
                        params.x = (initialX + deltaX).toInt()
                        params.y = (initialY + deltaY).toInt()
                        windowManager?.updateViewLayout(view, params)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (!isDragging && !isLongPressHandled) {
                        // Toggle bot state
                        val currentState = PreferenceManager.isBotActive(this)
                        val newState = !currentState
                        PreferenceManager.setBotActive(this, newState)

                        val statusMsg = if (newState) "Auto-Reply Active" else "Auto-Reply Paused"
                        Toast.makeText(this, statusMsg, Toast.LENGTH_SHORT).show()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    false
                }

                else -> false
            }
        }
    }

    private fun removeFloatingView() {
        if (floatingView != null && windowManager != null) {
            try {
                windowManager?.removeView(floatingView)
            } catch (e: Exception) {
                // View may have already been detached
            }
            floatingView = null
        }
    }
}
