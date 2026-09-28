package com.dshhandheld.app

import android.content.SharedPreferences
import com.dshhandheld.diag.DiagLog
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
 * （原 `TuiActivity.resolveKeyPath`，随原生终端一并移除）。所以这里只提供字段与 [toAuth]，
 * 终端模式的回退逻辑仍留在原处。
 *
 * 可见性是 public（默认）：`DshApp.ensureTunnel` 是 public 且以它作参数，
 * 而 Kotlin 不允许 public 函数暴露 internal 类型。
 */
data class SshConfig(
    /**
     * 主机列表里的稳定 id。空 = 还没入库（保存时生成）。
     * 多主机（2026-09-25）加的两个字段，`toJson`/`fromJson` 一起带上 —— 老的单条
     * `ssh_json` 里没有它们，解析时取默认值即可，不影响兼容。
     */
    val id: String = "",
    /** 列表里的显示名。留空则用 `user@host` 兜底。 */
    val name: String = "",
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

    /** 主机列表里显示什么。 */
    val label: String
        get() = name.ifBlank { if (user.isBlank()) host.ifBlank { "（未命名）" } else "$user@$host" }

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
        .put(KEY_ID, id)
        .put(KEY_NAME, name)
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

    /**
     * 复用指纹的输入：**连接相关**的字段，任一处不同就不复用同一条隧道。
     * 刻意不含 `id`/`name` —— 改个显示名不该让隧道重建。
     */
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

        private const val KEY_ID = "id"
        private const val KEY_NAME = "name"
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
            id = json.optString(KEY_ID),
            name = json.optString(KEY_NAME),
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

/**
 * 多主机：主机列表 + 当前生效的那条。
 *
 * 分工刻意如此：**`ssh_json` 永远是「当前生效」的那一条** —— 建隧道（[DshApp]）、
 * 复用指纹、连接屏预填都只认它，所以切换主机 = 把选中的那条写进 `ssh_json`，
 * 下游一个字都不用改。列表单独放 `ssh_hosts_json`。
 *
 * 迁移：老版本只有一条 `ssh_json`。首次读列表时若列表为空而旧配置在，就把它包成
 * 一条写回去（并持久化，避免每次读都生成新 id）。
 */
object SshHosts {
    private const val LIST_KEY = "ssh_hosts_json"
    private const val ACTIVE_KEY = "ssh_active_host"
    private const val TAG = "SshHosts"

    fun newId(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(8)

    /** 全部主机。空列表 = 还没配过任何主机。 */
    fun list(prefs: SharedPreferences): List<SshConfig> {
        val raw = SecurePrefs.getString(prefs, LIST_KEY)
        if (!raw.isNullOrBlank()) {
            val arr = runCatching { org.json.JSONArray(raw) }.getOrNull()
            if (arr != null) {
                val out = ArrayList<SshConfig>(arr.length())
                for (i in 0 until arr.length()) {
                    runCatching { SshConfig.fromJson(arr.getJSONObject(i)) }.getOrNull()?.let { out.add(it) }
                }
                if (out.isNotEmpty()) return out
            }
        }
        // 迁移：老版本只有一条 ssh_json
        val legacy = SshConfig.load(prefs) ?: return emptyList()
        val migrated = legacy.copy(id = legacy.id.ifBlank { newId() })
        save(prefs, listOf(migrated), migrated.id)
        DiagLog.i(TAG, "已把旧的单条配置迁移成主机列表：${migrated.label}")
        return listOf(migrated)
    }

    fun activeId(prefs: SharedPreferences): String =
        SecurePrefs.getString(prefs, ACTIVE_KEY).orEmpty()

    /** 写列表，并把当前那条同步进 `ssh_json`（下游只认它）。 */
    fun save(prefs: SharedPreferences, hosts: List<SshConfig>, active: String) {
        val arr = org.json.JSONArray()
        hosts.forEach { arr.put(it.toJson()) }
        SecurePrefs.putString(prefs, LIST_KEY, arr.toString())
        SecurePrefs.putString(prefs, ACTIVE_KEY, active)
        hosts.firstOrNull { it.id == active }?.let { SshConfig.save(prefs, it) }
    }

    /** 新增或更新（按 id），并设为当前。返回落库后的那条（带 id）。 */
    fun upsert(prefs: SharedPreferences, config: SshConfig): SshConfig {
        val id = config.id.ifBlank { newId() }
        val next = config.copy(id = id)
        val hosts = list(prefs).toMutableList()
        val at = hosts.indexOfFirst { it.id == id }
        if (at >= 0) hosts[at] = next else hosts.add(next)
        save(prefs, hosts, id)
        return next
    }

    /** 删除一条；删的若是当前那条，就把列表里第一条接上（没有则清空当前）。 */
    fun remove(prefs: SharedPreferences, id: String): List<SshConfig> {
        val hosts = list(prefs).filterNot { it.id == id }
        val active = if (activeId(prefs) == id) (hosts.firstOrNull()?.id ?: "") else activeId(prefs)
        save(prefs, hosts, active)
        DiagLog.i(TAG, "已删除主机 $id，剩 ${hosts.size} 条，当前=${active.ifBlank { "无" }}")
        return hosts
    }

    /** 只切当前，不改内容。 */
    fun setActive(prefs: SharedPreferences, id: String) {
        save(prefs, list(prefs), id)
    }
}
