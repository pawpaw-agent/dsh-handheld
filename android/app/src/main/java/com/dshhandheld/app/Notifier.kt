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
 * | `dsh-turn-hi` | 任务完成 | `IMPORTANCE_HIGH` | 「你等的那个结果好了」，**浮到屏幕上** |
 *
 * ⚠️ `dsh-turn-hi` 的 `-hi` 是**渠道迁移**留下的：0.1.11 用的是 `dsh-turn`
 * （`IMPORTANCE_DEFAULT`）。Android 的渠道一旦创建，**重要性只有用户能改**，
 * App 改不动 —— 所以「任务完成要弹横幅」只能换一个新 id 重建，同时删掉旧渠道
 * （见 [ensureChannels]）。真机实测：0.1.11 那条只在通知栏里，不弹横幅。
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

    /**
     * 任务完成渠道（[ensureChannels] 里创建）。
     *
     * 换过 id（见类注释）：旧的是 `dsh-turn`（DEFAULT，不弹横幅）。
     */
    const val CHANNEL_TURN = "dsh-turn-hi"

    /** 旧的完成渠道：只在 [ensureChannels] 里删掉它，不再往里发东西。 */
    private const val CHANNEL_TURN_LEGACY = "dsh-turn"

    /** 「在等你选择」渠道：比完成提醒更急（那一轮**卡住**了），走 heads-up。 */
    const val CHANNEL_ASK = "dsh-ask"

    /**
     * 用固定 id：连着干完几轮只留最新一条，而不是在通知栏里堆一排。
     * （[TunnelService] 用 1，这里避开。）
     */
    private const val ID_TURN = 2

    /** 「等你选择」用另一个 id：它和「做完了」可能前后脚出现，不该互相顶掉。 */
    private const val ID_ASK = 3

    /** 建渠道。可重复调用（已存在就跳过）。 */
    fun ensureChannels(context: Context, channelId: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        // 渠道迁移：旧的 `dsh-turn`（DEFAULT，不弹横幅）删掉。留着只会让设置页多一条
        // 永远不会响的「任务完成」。删除对用户是可见的（那条会从设置里消失），
        // 但它本来就只是一条从未被用户调过的渠道 —— 0.1.11 到 0.1.12 之间才存在。
        if (channelId == CHANNEL_TURN && nm.getNotificationChannel(CHANNEL_TURN_LEGACY) != null) {
            nm.deleteNotificationChannel(CHANNEL_TURN_LEGACY)
            DiagLog.i(TAG, "已删除旧渠道 $CHANNEL_TURN_LEGACY（换成 $CHANNEL_TURN，任务完成要弹横幅）")
        }
        if (nm.getNotificationChannel(channelId) != null) return
        when (channelId) {
            CHANNEL_ASK -> nm.createNotificationChannel(
                NotificationChannel(channelId, "在等你选择", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "dsh 停下来等你批准或回答（App 在后台时才发）"
                    setShowBadge(true)
                }
            )
            // HIGH 而不是 DEFAULT：只有 HIGH 及以上才允许**浮到屏幕上**（heads-up）。
            // 0.1.11 用 DEFAULT，真机上只在通知栏里躺着 —— 用户的原话是
            // 「有通知但不是弹出横幅通知」。渠道重要性创建后 App 改不动，所以换了 id。
            else -> nm.createNotificationChannel(
                NotificationChannel(channelId, "任务完成", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "dsh 生成结束时提醒你（App 在后台时才发），会浮到屏幕上"
                    setShowBadge(true)
                }
            )
        }
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
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.cancel(ID_TURN)
        nm.cancel(ID_ASK)
    }

    /**
     * 发一条「dsh 停下来等你了」。
     *
     * 比 [turnDone] 急：那一轮**没有**结束 —— 它在等一次批准或一个回答，而用户往往正在
     * 等一个不会自己来的结果。所以走 `dsh-ask` 渠道（`IMPORTANCE_HIGH`，会浮到屏幕上）。
     *
     * @param title 会话标题（页面给的）。
     */
    fun needsInput(context: Context, title: String?) {
        if (!allowed(context)) {
            DiagLog.w(TAG, "通知不可用（开关或权限），丢弃这条「等你选择」通知")
            return
        }
        ensureChannels(context, CHANNEL_ASK)
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = title?.takeIf { it.isNotBlank() } ?: "回到 App 点一下"
        val n = Notification.Builder(context, CHANNEL_ASK)
            .setContentTitle("dsh 在等你选择")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .build()
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.notify(ID_ASK, n)
        }.onFailure { DiagLog.w(TAG, "notify 失败：${it.javaClass.simpleName}: ${it.message}") }
        DiagLog.i(TAG, "已发「等你选择」通知：$text")
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
        // 弹不弹横幅由**渠道重要性**决定（CATEGORY 只是一句语义标注）：HIGH + 有提示音
        // 才会浮到屏幕上。所以这里保持 CATEGORY_STATUS 的语义，不去蹭 MESSAGE/CALL。
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
