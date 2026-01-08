package com.example.lxb_ignition.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager

/**
 * LXB 前台服务保活
 * 提供三层保活机制：WakeLock + Foreground Service + Watchdog
 */
class LXBKeepaliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private val CHANNEL_ID = "lxb_keepalive"
    private val NOTIFICATION_ID = 1001

    override fun onCreate() {
        super.onCreate()

        // 创建通知渠道
        createNotificationChannel()

        // 启动前台服务
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        // 获取 WakeLock
        acquireWakeLock()

        // TODO: 启动 Watchdog 监控
        // TODO: 启动 lxb-core.jar 服务端进程
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY 确保服务被杀死后自动重启
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()

        // 释放 WakeLock
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
    }

    /**
     * 获取 WakeLock 保持 CPU 唤醒
     */
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "LXB::KeepaliveWakeLock"
        )
        wakeLock?.acquire()
    }

    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "LXB 保活服务",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    /**
     * 构建前台通知
     */
    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("LXB Server")
            .setContentText("服务运行中...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }
}
