package com.dshhandheld.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dshhandheld.diag.DiagLog

/**
 * 开机 / APK 覆盖安装后，把上一次的隧道自动接回来。
 *
 * ## 为什么需要它（2026-10-30 实测）
 *
 * 手机重启之后，App 没在跑，于是**没有任何人去连电脑** —— 用户看到的现象就是
 * 「怎么都连不上」，而且完全没有任何线索（PC 侧 sshd 连一条认证记录都没有）。
 * 当时 PC 侧的证据链是：`ping 通` + `ARP 有正常 MAC`（说明手机在 Wi-Fi 上）+
 * `sshd 近 15 分钟零 Accepted`（说明没有人在连）。缺的就是这个广播接收者。
 *
 * ## 边界
 *
 * - **只在用户上次确实连着时**才恢复：`PREF_WAS_CONNECTED` 由 DshApp 在隧道发布成功时
 *   置 true、在用户手动「断开连接」时清掉。没连过就开机的人不该被拉起一个前台服务。
 * - 只拉起隧道（协议层），不启动界面：Android 12+ 限制后台启动 Activity，
 *   而且打开界面本来也不是必需的 —— 用户点开 App 时隧道已经就绪，直接就是快的。
 * - 系统把服务拉起来后，隧道仍由 [DshApp] 持有（服务只负责让进程保持前台）。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> Unit
            else -> return
        }

        val app = context.applicationContext as? DshApp ?: return
        if (!app.wasConnectedBefore()) {
            DiagLog.i(TAG, "开机广播：上次没有连接记录，跳过自动恢复")
            return
        }

        DiagLog.i(TAG, "开机广播（${intent.action}）：尝试恢复隧道")
        // 先把进程抬成前台，否则后台建连很容易在 Wi-Fi 省电/冻结里被打断
        TunnelService.start(context, "正在恢复连接…")
        app.restoreTunnelInBackground()
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
