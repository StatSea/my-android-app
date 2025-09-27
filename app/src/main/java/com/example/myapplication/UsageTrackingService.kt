package com.example.myapplication

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import androidx.core.app.NotificationCompat

class UsageTrackingService : Service() {

    private val handler = Handler()
    private val updateInterval = 1000L // 1초마다 체크
    private var currentApp: String? = null
    private var startTime: Long = 0

    override fun onCreate() {
        super.onCreate()
        startForeground(1, createNotification("실시간 감시 중..."))
        startUsageCheck()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startUsageCheck() {
        handler.post(object : Runnable {
            override fun run() {
                val topApp = getForegroundApp(this@UsageTrackingService)
                if (topApp != null) {
                    if (topApp != currentApp) {
                        // 앱이 새로 바뀐 경우
                        currentApp = topApp
                        startTime = System.currentTimeMillis()
                    }

                    // 실행 시간 계산
                    val elapsed = System.currentTimeMillis() - startTime
                    val formattedTime = formatTime(elapsed)

                    val notification =
                        createNotification("현재 실행 중: $topApp ($formattedTime)")
                    val manager =
                        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(1, notification)
                }
                handler.postDelayed(this, updateInterval)
            }
        })
    }

    private fun createNotification(content: String): Notification {
        val channelId = "usage_tracking_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "앱 사용 추적",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("📱 실시간 앱 추적")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()
    }

    // ✅ 현재 실행 중인 앱 (마지막 단어만 표시)
    private fun getForegroundApp(context: Context): String? {
        val usageStatsManager =
            context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val begin = end - 1000 * 10 // 최근 10초 동안 이벤트 확인
        val events = usageStatsManager.queryEvents(begin, end)

        var lastApp: String? = null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                val fullName = event.packageName
                lastApp = fullName.substringAfterLast('.') // 맨 마지막 단어만
            }
        }
        return lastApp
    }

    // ✅ ms → 분:초 변환
    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }
}
