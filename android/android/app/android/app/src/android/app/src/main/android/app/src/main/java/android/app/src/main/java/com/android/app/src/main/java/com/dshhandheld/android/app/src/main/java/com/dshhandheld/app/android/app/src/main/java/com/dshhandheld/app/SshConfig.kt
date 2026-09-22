package com.dshhandheld.app

import android.content.SharedPreferences
import com.dshhandheld.protocol.SshTunnel
import org.json.JSONObject
import java.io.File

/**
 * `ssh_json` 这一份配置的**唯一定义**。
 *
 * 抽出来的原因：这 9 个字段名此前在三个文件里各写一遍 ——
 * `MainActivity`（读写与预填）、`DshApp`（建隧道 + 复用指纹）、`TuiActivity`
 * （起 dbclient）。加一个字段要改三处，漏一处就是「保存了但没生效」这类静默故障；
 * 解析写法也有三种（`runCatching` / `try-catch` / 完全不校验），对「配置是否完整」
 * 的判断还不一致。
 *
 * 注意**有意不合并**的一点：私钥的处理两条路径不同。建隧道（[toAuth]）要求
 * `keyPath` 非空，而终端模式在缺私钥时会回退到 `dropbearkey` 现生成一对
 * （见 `TuiActivity.resolveKeyPath`）。所以这里只提供字段与 [toAuth]，
 * 终端模式的回退逻辑仍留在原处。
 *
 * 可见性是 public（默认）：`DshApp.ensureTunnel` 是 public 且以它作参数，
 * 而 Kotlin 不允许 public 函数暴露 internal 类型。
 */
data class SshConfig(
    val host: String = "",
    val port: Int = DEFAULT_SSH_PORT,
    val user: String = "",
    val remoteHost: String = DEFAULT_REMOTE_HOST,
    val remotePort: Int = DEFAULT_REMOTE_PORT,
    /** `password` 或 `key`。 */
    val authType: String = AUTH_PASSWORD,
    val password: String = "",
    val keyPath: String = "",
    val keyPass: String = "",
) {

    /** 够不够用来连：地址与账号都在。与旧代码里两处 `isNotBlank` 校验等价。 */
    val isComplete: Boolean get() = host.isNotBlank() && user.isNotBlank()

    val usesKey: Boolean get() = authType == AUTH_KEY

    /**
     * 建隧道用的认证信息。
     *
     * 私钥方式但没给路径时返回 null（调用方据此判定配置不可用），与
     * `DshApp.build` 原来的行为一致。
     */
    fun toAuth(): SshTunnel.Auth? = if (usesKey) {
        if (keyPath.isBlank()) null
        else SshTunnel.Auth.KeyPair(File(keyPath), keyPass.ifEmpty { null })
    } else {
        SshTunnel.Auth.Password(password)
    }

    fun toJson(): JSONObject = JSONObject()
        .put(KEY_HOST, host)
        .put(KEY_PORT, port)
        .put(KEY_USER, user)
        .put(KEY_REMOTE_HOST, remoteHost)
        .put(KEY_REMOTE_PORT, remotePort)
        .apply {
            if (usesKey) {
                put(KEY_AUTH_TYPE, AUTH_KEY)
                    .put(KEY_KEY_PATH, keyPath)
                    .put(KEY_KEY_PASS, keyPass)
            } else {
                put(KEY_AUTH_TYPE, AUTH_PASSWORD)
                    .put(KEY_PASSWORD, password)
            }
        }

    /** 复用指纹的输入：全部字段，任一处不同就不复用同一条隧道。 */
    fun fingerprint(): String = listOf(
        host, port.toString(), user, remoteHost, remotePort.toString(),
        authType, password, keyPath, keyPass
    ).joinToString("\u0000")

    companion object {
        const val PREF_KEY = "ssh_json"
        const val AUTH_PASSWORD = "password"
        const val AUTH_KEY = "key"
        const val DEFAULT_SSH_PORT = 22
        const val DEFAULT_REMOTE_HOST = "127.0.0.1"
        const val DEFAULT_REMOTE_PORT = 3080

        private const val KEY_HOST = "sshHost"
        private const val KEY_PORT = "sshPort"
        private const val KEY_USER = "sshUser"
        private const val KEY_REMOTE_HOST = "remoteHost"
        private const val KEY_REMOTE_PORT = "remotePort"
        private const val KEY_AUTH_TYPE = "authType"
        private const val KEY_PASSWORD = "password"
        private const val KEY_KEY_PATH = "keyPath"
        private const val KEY_KEY_PASS = "keyPass"

        fun fromJson(json: JSONObject): SshConfig = SshConfig(
            host = json.optString(KEY_HOST),
            port = json.optInt(KEY_PORT, DEFAULT_SSH_PORT),
            user = json.optString(KEY_USER),
            remoteHost = json.optString(KEY_REMOTE_HOST, DEFAULT_REMOTE_HOST),
            remotePort = json.optInt(KEY_REMOTE_PORT, DEFAULT_REMOTE_PORT),
            authType = json.optString(KEY_AUTH_TYPE, AUTH_PASSWORD),
            password = json.optString(KEY_PASSWORD),
            keyPath = json.optString(KEY_KEY_PATH),
            keyPass = json.optString(KEY_KEY_PASS),
        )

        /** 解析失败（键不存在或不是合法 JSON）返回 null。 */
        fun parse(raw: String?): SshConfig? {
            val text = raw ?: return null
            return try {
                fromJson(JSONObject(text))
            } catch (_: Exception) {
                null
            }
        }

        /** 从加密偏好里读；未配置或解析失败返回 null。 */
        fun load(prefs: SharedPreferences): SshConfig? =
            parse(SecurePrefs.getString(prefs, PREF_KEY))

        /** 写入加密偏好。 */
        fun save(prefs: SharedPreferences, config: SshConfig) {
            SecurePrefs.putString(prefs, PREF_KEY, config.toJson().toString())
        }
    }
}
