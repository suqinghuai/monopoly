package com.monopoly.lan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder

/**
 * 前台服务：把 ServerHub 挂在后台跑，屏幕关了、切出去了也还能开局。
 *
 * 只有一种模式：局域网房间。起内置 HTTP 服务，并往局域网广播房间信息，
 * 同一 WiFi 的人打开浏览器 / 应用内打开就能进。
 */
class ServerService : Service() {

    companion object {
        const val ACTION_STOP = "com.monopoly.lan.STOP"
        const val ACTION_STATE = "com.monopoly.lan.STATE"
        private const val CHANNEL_ID = "monopoly"
        private const val NOTIFY_ID = 1001

        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        var port: Int = 0
            private set

        @Volatile
        var lastError: String = ""
            private set

        /** 同网段地址：给别人用的那个。 */
        fun url(): String {
            val ip = NetInfo.ipAddress()
            return if (ip.isEmpty() || port == 0) "" else "http://$ip:$port/"
        }

        /** 本机自用地址。 */
        fun localUrl(): String = if (port == 0) "" else "http://127.0.0.1:$port/"

        fun start(context: Context) {
            val intent = Intent(context, ServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ServerService::class.java).setAction(ACTION_STOP))
        }
    }

    private var hub: ServerHub? = null
    private var advertiser: NetInfo.Advertiser? = null
    private var brain: AiBrain? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) {
            startForeground(NOTIFY_ID, buildNotification())
            return START_STICKY
        }

        // 先把通知挂上（8.0 起 startForegroundService 必须 5 秒内 startForeground）
        startForeground(NOTIFY_ID, buildNotification())

        Store.init(this)
        try {
            stopHub()
            val b = AiBrain(this)
            brain = b
            val h = ServerHub(assets, b)
            val p = h.start(ServerHub.DEFAULT_PORT)
            hub = h
            port = p
            running = true
            lastError = ""

            val adv = NetInfo.Advertiser(this, "${Build.MODEL} 的房间", p)
            adv.start()
            advertiser = adv

            notifyChanged()
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            running = false
            port = 0
            notifyChanged()
        }
        return START_STICKY
    }

    private fun stopHub() {
        try {
            advertiser?.stop()
        } catch (e: Exception) {
            // ignore
        }
        advertiser = null
        try {
            hub?.stop()
        } catch (e: Exception) {
            // ignore
        }
        hub = null
    }

    private fun shutdown() {
        stopHub()
        running = false
        port = 0
        notifyChanged()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun notifyChanged() {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFY_ID, buildNotification())
        } catch (e: Exception) {
            // 通知栏刷不了无所谓
        }
        // 广播给页面：主界面据此刷新状态
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName))
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "大富翁服务", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "局域网房间运行中" }
            manager.createNotificationChannel(channel)
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (running) "大富翁：局域网房间开着" else "大富翁：服务已停止"
        val text = if (!running) lastError.ifEmpty { "点开应用重新开一个房间" } else "同网段 ${url()}"
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, R.mipmap.ic_launcher),
                    "停止服务",
                    stop,
                ).build()
            )
            .setOngoing(running)
            .build()
    }
}
