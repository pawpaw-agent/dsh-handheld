package com.dshhandheld.app

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import com.dshhandheld.diag.DiagLog

/**
 * 敏感偏好项的静态加密（AES-256-GCM，密钥存 AndroidKeyStore）。
 *
 * 为什么自己写而不是用 `androidx.security:security-crypto`：
 * 该库的 **全部 API 已被官方废弃**（1.1.0-beta01 起：“Deprecated all APIs in favour of
 * existing platform APIs and **direct use of Android Keystore**”），因此这里直接按官方
 * 指引使用平台 Keystore，不引入任何依赖。
 *
 * ## 威胁模型
 *
 * 主要防的是**拿到设备本地数据的攻击者**：debuggable 包或 root 设备上 `run-as` 直接读
 * `shared_prefs` 下的 XML、adb backup、以及在旧机上被物理提取文件系统。密钥由
 * AndroidKeyStore 持有且**不可导出**（即便应用私有目录被完整复制，没有该设备的
 * Keystore 也无法解密）。
 *
 * **不防**：已 root 且能在应用进程内执行代码的攻击者——那种情况下任何应用侧加密都无解，
 * 因为应用自己必须能解密。这不是本类的目标。
 *
 * ## 设计要点
 *
 * - **系统生成 IV**：AES/GCM 默认 `setRandomizedEncryptionRequired(true)`，因此不自行
 *   提供 IV，从 `cipher.iv` 读回。避免固定/重复 IV 这种致命错误。
 * - **明文兼容**：读取时若发现值不是密文（历史版本写的明文），原样返回并**顺手迁移**为
 *   密文，用户无感。这样升级不会丢配置。
 * - **失败即降级而非崩溃**：Keystore 密钥可能因设备策略变化而永久失效
 *   （`KeyPermanentlyInvalidatedException` 等）。此时解密返回 null，让上层走“需要重新
 *   输入”的既有路径，绝不因为读不到凭据而让 App 起不来。
 */
object SecurePrefs {

    private const val TAG = "DshHandheld"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"

    /** Keystore 内的密钥别名；改名等于让所有已存密文失效。 */
    private const val KEY_ALIAS = "dsh-handheld-credentials-v1"

    /** 密文标记。带版本号，便于将来换算法时区分。 */
    private const val CIPHER_PREFIX = "enc.v1."

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val KEY_SIZE_BITS = 256

    /** GCM 标准 IV 长度（12 字节）。加解密两侧共用，避免写错。 */
    private const val IV_BYTES = 12

    // ---------------------------------------------------------------- Keystore

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    /** 取密钥；不存在则生成。密钥不可导出，且不要求用户认证（后台重连也要能解密）。 */
    @Synchronized
    private fun secretKey(): SecretKey? {
        return try {
            val ks = keyStore()
            (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
                ?: generateKey()
        } catch (e: Exception) {
            DiagLog.w(TAG, "SecurePrefs: 无法取得 Keystore 密钥：${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                // 不要求用户认证：App 冷启动后台重建隧道时无法弹出指纹/密码框。
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    // ------------------------------------------------------------------ 加解密

    /** 加密为 `enc.v1.<base64(iv || ciphertext)>`；失败时返回 null（调用方应放弃写入）。 */
    fun encrypt(plain: String): String? {
        val key = secretKey() ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)   // 由系统生成随机 IV
            val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val blob = cipher.iv + ciphertext       // IV 长度随算法固定（GCM 12 字节）
            CIPHER_PREFIX + Base64.encodeToString(blob, Base64.NO_WRAP)
        } catch (e: Exception) {
            DiagLog.w(TAG, "SecurePrefs: 加密失败：${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** 解密 `encrypt` 的产物；输入不是密文时按明文返回（历史数据）。 */
    fun decrypt(stored: String): String? {
        if (!stored.startsWith(CIPHER_PREFIX)) return stored   // 明文（旧数据）
        val key = secretKey() ?: return null
        return try {
            val blob = Base64.decode(stored.removePrefix(CIPHER_PREFIX), Base64.NO_WRAP)
            val iv = blob.copyOfRange(0, IV_BYTES)
            val ciphertext = blob.copyOfRange(IV_BYTES, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            DiagLog.w(TAG, "SecurePrefs: 解密失败：${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    // ------------------------------------------------------- SharedPreferences

    /**
     * 读敏感项。读到明文旧值时**顺带迁移**为密文（best-effort：加密不可用则保持明文，
     * 不阻断功能——宁可暂时明文也不要让用户连不上）。
     */
    /**
     * 最近一次「密文在、但解不开」的 key（审计 L2）。
     *
     * 进程内一次性信号：上层（连接屏）据此把「还没配置过」换成一句能让人行动的提示。
     * 不做持久化 —— 它描述的是**本次进程**读配置时的状态。
     */
    @Volatile
    var lastUndecryptableKey: String? = null
        private set

    fun getString(prefs: SharedPreferences, key: String): String? {
        val raw = prefs.getString(key, null) ?: return null
        if (!raw.startsWith(CIPHER_PREFIX)) {
            DiagLog.i(TAG, "SecurePrefs: $key 为历史明文，迁移为密文")
            putString(prefs, key, raw)
            return raw
        }
        val plain = decrypt(raw)
        if (plain == null) {
            DiagLog.w(TAG, "SecurePrefs: $key 解密失败（密钥失效？），按未配置处理")
            // 记下「是密文但解不开」这件事（审计 L2）：否则上层无法区分它与「从来没配过」，
            // 用户看到的是 App「忘了」配置、也没有任何解释，只能全部重填。
            lastUndecryptableKey = key
        }
        return plain
    }

    /** 写敏感项。加密不可用时**回退明文**并告警，保证功能不被加密层拖垮。 */
    fun putString(prefs: SharedPreferences, key: String, value: String) {
        val encrypted = encrypt(value)
        if (encrypted == null) {
            DiagLog.w(TAG, "SecurePrefs: $key 加密不可用，回退明文存储")
            prefs.edit().putString(key, value).apply()
        } else {
            prefs.edit().putString(key, encrypted).apply()
        }
    }
}
