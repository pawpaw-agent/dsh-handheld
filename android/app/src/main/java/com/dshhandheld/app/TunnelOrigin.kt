package com.dshhandheld.app

import com.dshhandheld.protocol.SshTunnel
import java.net.URI

/**
 * 「这个 URL 是不是我们自己的隧道页面」。
 *
 * 页面权限（`onPermissionRequest` 只放行音频采集）与导航拦截（`shouldOverrideUrlLoading`
 * 把非隧道链接交给系统浏览器）都靠它判定 —— 判错的后果是「麦克风被拒」或「页面被导航走」。
 *
 * 单独成对象的理由：这是纯字符串逻辑，但此前长在 `MainActivity` 的伴生对象里，
 * JVM 单测要连带加载整个 Activity 类。抽出来之后 `TunnelOriginTest` 直接跑。
 *
 * ⚠️ 别拿字符串直接比集合：`PermissionRequest.origin` 是**带尾斜杠**的
 * （`http://127.0.0.1:3080/`），而 [origins] 里没有 —— 真机第一版就是这么被自己的日志
 * 抓出来的（「拒绝（origin=http://127.0.0.1:3080/，resources=…AUDIO_CAPTURE）」）。
 * 解析成 scheme/host/port 来比，尾斜杠与大小写都不再是坑。
 */
object TunnelOrigin {

    /** 隧道页面可能的 origin（端口候选见 [SshTunnel.PORT_CANDIDATES]）。 */
    fun origins(): Set<String> = SshTunnel.PORT_CANDIDATES.map { "http://127.0.0.1:$it" }.toSet()

    /**
     * `url` 是不是隧道页面 —— 传 origin（带尾斜杠也行）或整条 URL 都可以。
     *
     * 用 `java.net.URI` 而不是 `android.net.Uri`：纯字符串逻辑，换掉之后 JVM 单测能直接跑
     * （`android.*` 在单测里是抛异常的桩）。行为差异只有一处：URI 对非法输入**抛异常**
     * （`Uri.parse` 宽松接受），这里 runCatching 后一律 false —— 而传入值来自 WebView，
     * 永远是良构的。
     */
    fun isTunnel(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("http", ignoreCase = true)) return false
        if (uri.host != "127.0.0.1") return false
        return uri.port in SshTunnel.PORT_CANDIDATES
    }
}
