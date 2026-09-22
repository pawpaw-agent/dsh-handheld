package com.dshhandheld.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.dshhandheld.diag.DiagLog

/**
 * 隧道保活用的前台服务。
 *
 * ## 为什么需要它（都有真机证据）
 *
 * 没有前台服务时，这个 App 就是一个 **cached 进程**：
 *
 * - 熄屏或退到后台后被 Android **冻结** —— 实测 `/proc/<pid>/cgroup` → `freezer:/frozen`，
 *   而且 **dbclient 子进程继承同一个 cgroup，一起被冻在 `connect()` 里**（解冻瞬间
 *   三个残留进程同时报 `Connect failed: Software caused connection abort`）；
 * - 内存压力下被低内存杀手**优先挑中** —— 实测 `ApplicationExitInfo`:
 *   `reason=3 (LOW_MEMORY)`，那一刻 `importance=400 (CACHED)`。
 *
 * 后果：后台断线不会自愈（看门狗线程根本没机会跑），用户切回来时隧道已经死了，
 * 严重时 App 连同会话视图一起没了。
 *
 * 有前台服务时进程处于 `FOREGROUND_SERVICE`，**不进 cached 队列**：既不冻结，
 * oom_adj 也低得多，低内存杀手会先去杀别的应用。
 *
 * ## 它不做什么
 *
 * 不持有隧道、不做网络、不轮询 —— 隧道所有权仍在 [DshApp]。这里只把进程"钉"在
 * 合适的 oom 级别上。
 *
 * ## 通知与类型
 *
 * 用 `specialUse`：端口转发不属于 Android 预置的任何一类（`dataSync` 在 Android 15+
 * 还有每日时长上限，不适合长会话），所以走 catch-all 并写明用途。
 *
 * 刻意**不申请** `POST_NOTIFICATIONS`：Android 13+ 上常驻通知因此不显示
 * （任务管理器里仍能看到这个前台服务），Android 12 及以下会显示一条最低重要性的
 * 静默通知（那里无法避免）。
 */
class TunnelService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ⚠️ 必须是**第一件事**。dropbox 里躺着一条历史崩溃记录：
        //   ForegroundServiceDidNotStartInTimeException:
        //     Context.startForegroundService() did not then call Service.startForeground()
        //     ServiceRecord{... com.dshmobile.app/.AgentMonitorService}
        startForeground(NOTIFICATION_ID, buildNotification(intent?.getStringExtra(EXTRA_TEXT)))
        DiagLog.i(TAG, "TunnelService: startForeground（隧道保活中）")
        // 隧道由 DshApp 持有；系统把服务拉起来时隧道并不在，所以不要粘性重建
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        DiagLog.i(TAG, "TunnelService: onDestroy")
        super.onDestroy()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "保持连接", NotificationManager.IMPORTANCE_MIN).apply {
                description = "隧道保活时的常驻通知（静默、不打扰）"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        )
    }

    private fun buildNotification(text: String?): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(text ?: "已连接到电脑")
            .setContentText("dsh 隧道保持中")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "TunnelService"
        private const val CHANNEL_ID = "dsh-tunnel"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_TEXT = "text"

        /**
         * 启动/更新保活服务。**绝不抛异常**：Android 12+ 在应用处于后台时禁止启动前台
         * 服务（`ForegroundServiceStartNotAllowedException`），那种情况只记日志。
         */
        fun start(context: Context, text: String? = null) {
            val i = Intent(context, TunnelService::class.java)
            if (text != null) i.putExtra(EXTRA_TEXT, text)
            try {
                context.startForegroundService(i)
            } catch (e: Exception) {
                DiagLog.w(TAG, "startForegroundService 被拒: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, TunnelService::class.java))
            } catch (e: Exception) {
                DiagLog.w(TAG, "stopService 失败: ${e.message}")
            }
        }
    }
}
