package com.dshhandheld.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.dshhandheld.diag.DiagLog

/**
 * 应用内通知。
 *
 * ## 为什么要有它
 *
 * 在此之前，「dsh 干完活了」这件事是**服务端经微信推送**给用户的（见 [DshApp] 类注释里的
 * 历史记录）—— 想在手机上知道结果，得先让电脑把消息发到微信，再让微信叫你。
 * 那条路要服务端配合，而且消息绕出 App 又绕回来。2026-09-14 起改由 App 自己发：
 * 页面里我们自己的适配插件报告「这一轮生成结束了」，App 落一条通知。
 *
 * ## 两条通知、两个渠道
 *
 * 重要性不同，用户可以分别关：
 *
 * | 渠道 | 名称 | 重要性 | 用途 |
 * |---|---|---|---|
 * | `dsh-tunnel` | 保持连接 | `IMPORTANCE_MIN` | 前台服务的凭据（见 [TunnelService]），静默 |
 * | `dsh-turn` | 任务完成 | `IMPORTANCE_DEFAULT` | 「你等的那个结果好了」，响铃/震动按用户设置 |
 *
 * 注意一条系统规则：Android 13+ 的 `POST_NOTIFICATIONS` 是**一次性**授权，用户为了
 * 任务完成提醒批准之后，隧道那条常驻通知也会跟着出现（不能只批一半）。它是静默的，
 * 且任务管理器里本来就能看到这个前台服务 —— 不算多暴露了什么。
 *
 * ## 什么时候不该发
 *
 * 前台不发（用户正看着屏幕，响一声只会烦人）、开关关着不发、没授权不发 —— 三条判断都在
 * [DshApp.onPageMessage] 里，这里只管「怎么发」。
 */
object Notifier {

    private const val TAG = "DshNotifier"

    /** 任务完成渠道（[ensureChannels] 里创建）。 */
    const val CHANNEL_TURN = "dsh-turn"

    /**
     * 用固定 id：连着干完几轮只留最新一条，而不是在通知栏里堆一排。
     * （[TunnelService] 用 1，这里避开。）
     */
    private const val ID_TURN = 2

    /** 建渠道。可重复调用（已存在就跳过）。 */
    fun ensureChannels(context: Context, channelId: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(channelId) != null) return
        nm.createNotificationChannel(
            NotificationChannel(channelId, "任务完成", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "dsh 生成结束时提醒你（App 在后台时才发）"
                setShowBadge(true)
            }
        )
    }

    /**
     * 现在能不能发通知：系统开关 + Android 13+ 的运行时权限。
     *
     * `areNotificationsEnabled()` 在 API 24+ 都有；权限那一项只在 33+ 存在。
     */
    fun allowed(context: Context): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!nm.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    /** 撤回「任务完成」通知（用户关掉开关时调用，别留一条撤不掉的历史）。 */
    fun cancelTurn(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(ID_TURN)
    }

    /**
     * 发一条「这一轮结束了」。
     *
     * @param title 会话标题（页面给的），空则用一句兜底文案 —— 通知里最该出现的是
     *   「哪个会话」，其次是「它做完了」，所以标题优先放会话名。
     */
    fun turnDone(context: Context, title: String?) {
        if (!allowed(context)) {
            DiagLog.w(TAG, "通知不可用（开关或权限），丢弃这条任务完成通知")
            return
        }
        ensureChannels(context, CHANNEL_TURN)
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = title?.takeIf { it.isNotBlank() } ?: "回到 App 看结果"
        val n = Notification.Builder(context, CHANNEL_TURN)
            .setContentTitle("dsh 做完了")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.notify(ID_TURN, n)
        }.onFailure { DiagLog.w(TAG, "notify 失败：${it.javaClass.simpleName}: ${it.message}") }
        DiagLog.i(TAG, "已发任务完成通知：$text")
    }
}
