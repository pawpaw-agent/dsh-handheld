package com.dshhandheld.app

import com.dshhandheld.diag.DiagLog
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 私钥导入：把 OpenSSH 格式的私钥转成 dbclient 能读的 **dropbear 格式**。
 *
 * ## 为什么必须有这一步（不是可选优化）
 *
 * dbclient 的 `-i` **只认 dropbear 自有格式**。客户端加载身份文件的路径是
 * `cli-runopts.c:loadidentityfile()` → `common-runopts.c:readhostkey()` →
 * `signkey.c:buf_get_priv_key()`，那个解析器把文件当成裸的 length-prefixed
 * 结构读：4 字节大端长度 + key-type 名（`ssh-ed25519` / `ssh-rsa` /
 * `ecdsa-sha2-…`），既没有口令参数，也不做任何解密。
 *
 * 于是拿 `ssh-keygen` 生成的密钥直接喂给它（本类出现之前 App 的行为），
 * dbclient 会在**连接之前**就退出：
 *
 * ```
 * -----BEGIN OPENSSH PRIVATE KEY-----   ← 被当成 4 字节长度读，值是天文数字
 * ./dbclient: Exited: String too long
 * ```
 *
 * 实测（dropbear 2026.94 + 本仓库 scripts/localoptions.h 本机编译版）：
 * OpenSSH ed25519（无口令/带口令）、OpenSSH RSA、传统 PEM 全部同样失败；
 * 只有 `dropbearkey` 的产物或 `dropbearconvert` 的产物能加载。
 * 上游 README 的说法与此一致：*"you will have to convert OpenSSH style keys to
 * Dropbear format, or use dropbearkey to create them."*
 *
 * 转换器就是同一份源码里的 `dropbearconvert`（由 `scripts/build-dropbear.sh`
 * 的 `BUILD_ONLY` 一起构建，CI 与 dbclient / dropbearkey 一并放进 jniLibs）。
 *
 * ## 口令：结构上做不到，所以不再假装支持
 *
 * `dropbearconvert` 同样解不开带口令的密钥——它内部走 `keyimport.c:openssh_read()`，
 * 而那个函数的 passphrase 参数被标注为 `UNUSED`。实测：现代 OpenSSH 格式报
 * `Error decoding OpenSSH key`，传统 PEM 报 `Ciphers other than DES-EDE3-CBC not
 * supported`。
 *
 * 所以这种情况**不回退**、也不静默改用密码认证，而是返回
 * [Outcome.NeedsPassphraseRemoval]，由 UI 给出一句可执行的下一步
 * （先在电脑上去掉口令再导入）。此前 UI 收了口令却从不使用，用户只会得到
 * 「连不上你的电脑（检查地址/账号/私钥）」——一个指向错误方向的提示。
 */
object SshKeyImport {

    private const val TAG = "SshKeyImport"

    /** 导入的原始文件落点（`filesDir` 下）。固定名字 = 重复导入覆盖，不累积垃圾。 */
    const val IMPORTED_FILE_NAME = "ssh_private_key"

    /** 转换产物的落点（`filesDir` 下）。隧道模式与终端模式共用同一个落点。 */
    const val CONVERTED_FILE_NAME = "id_dropbear_converted"

    /** 转换器在 `nativeLibraryDir` 下的文件名（APK 里必须叫 `lib*.so` 才会被解包）。 */
    const val CONVERT_BIN_NAME = "libdropbearconvert.so"

    /** 转换是毫秒级操作；给足余量，但绝不允许它把调用线程永久挂住。 */
    private const val CONVERT_TIMEOUT_SEC = 10L

    /** PEM / OpenSSH 私钥的 armor 前缀（11 字节）。 */
    private const val PEM_MAGIC = "-----BEGIN "

    sealed class Outcome {
        /** 可以交给 dbclient 的密钥。[converted] 表示这次真的跑了一次转换。 */
        data class Ready(val file: File, val converted: Boolean) : Outcome()

        /** 带口令的密钥：dropbear 侧无法解密，需要用户先在电脑上去掉口令。 */
        data class NeedsPassphraseRemoval(val source: File) : Outcome()

        /** 其它失败：文件不在、不是私钥、转换器缺失。 */
        data class Failed(val reason: String) : Outcome()
    }

    /** `nativeLibraryDir` 下的转换器路径；APK 没打包它时返回 null。 */
    fun converterPath(nativeLibDir: String): String? =
        File(nativeLibDir, CONVERT_BIN_NAME).takeIf { it.exists() }?.absolutePath

    /**
     * 保证 [src] 是 dbclient 能读的格式；必要时用 [converter] 转一份到 [workDir]。
     *
     * **会 fork 子进程**（最长 [CONVERT_TIMEOUT_SEC] 秒），调用方必须已经在后台线程。
     *
     * 幂等：已经是 dropbear 格式的文件直接返回，不会重复转换。
     */
    fun ensureUsable(src: File, workDir: File, converter: String?): Outcome {
        if (!src.exists()) return Outcome.Failed("密钥文件不存在：${src.absolutePath}")

        // 两个判据互斥，顺序不影响结果：PEM 以 11 字节 armor 开头（4 字节长度会读出
        // 天文数字，落不进 7..64），dropbear 格式以 0x00 开头（永远匹配不上 armor）。
        // 先判 dropbear 只是因为它是最省事的那条路：无需转换，直接可用。
        if (looksLikeDropbear(src)) return Outcome.Ready(src, converted = false)

        if (!looksLikePem(src)) {
            return Outcome.Failed(
                "这不像是一把 SSH 私钥（既不是 OpenSSH/PEM，也不是 dropbear 格式）"
            )
        }

        if (converter == null) {
            return Outcome.Failed("缺少 $CONVERT_BIN_NAME（APK 未包含？）")
        }

        val dst = File(workDir, CONVERTED_FILE_NAME)
        runCatching { dst.delete() }
        val text = run(converter, "openssh", "dropbear", src.absolutePath, dst.absolutePath)
        if (dst.exists() && dst.length() > 0L) {
            DiagLog.i(TAG, "已转换为 dropbear 格式：${src.name} → ${dst.name}（${dst.length()} 字节）")
            return Outcome.Ready(dst, converted = true)
        }

        DiagLog.w(TAG, "dropbearconvert 未产出文件：${text.take(200)}")
        return if (looksEncrypted(text)) {
            Outcome.NeedsPassphraseRemoval(src)
        } else {
            Outcome.Failed(text.ifBlank { "dropbearconvert 没有产出文件" })
        }
    }

    /**
     * dropbear 格式的判据：`buf_get_priv_key` 读的第一个东西是
     * `buf_getstring()`（4 字节大端长度 + 内容），内容就是算法名。
     * 只读文件头，不整份载入。
     */
    private fun looksLikeDropbear(f: File): Boolean {
        return try {
            f.inputStream().use { ins ->
                val lenBuf = ByteArray(4)
                if (ins.read(lenBuf) != 4) {
                    false
                } else {
                    val len = ((lenBuf[0].toInt() and 0xFF) shl 24) or
                        ((lenBuf[1].toInt() and 0xFF) shl 16) or
                        ((lenBuf[2].toInt() and 0xFF) shl 8) or
                        (lenBuf[3].toInt() and 0xFF)
                    // 算法名最长是 ecdsa-sha2-nistp521（19）；给到 64 足够且能挡掉
                    // 「长度字段读出天文数字」的 PEM 文件
                    if (len !in 7..64) {
                        false
                    } else {
                        val name = ByteArray(len)
                        val n = ins.read(name)
                        n == len && String(name, Charsets.US_ASCII).let {
                            it.startsWith("ssh-") || it.startsWith("ecdsa-")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            DiagLog.w(TAG, "读密钥头失败：${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /** OpenSSH / PEM 私钥一律以 `-----BEGIN ` 开头（实测四种格式都是）。 */
    private fun looksLikePem(f: File): Boolean = try {
        f.inputStream().use { ins ->
            val head = ByteArray(PEM_MAGIC.length)
            val n = ins.read(head)
            n == head.size && String(head, Charsets.US_ASCII) == PEM_MAGIC
        }
    } catch (e: Exception) {
        DiagLog.w(TAG, "读密钥头失败：${e.javaClass.simpleName}: ${e.message}")
        false
    }

    /** 口令相关的报错都归到「你需要先去口令」，不要当成「格式不认识」。 */
    private fun looksEncrypted(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("decoding openssh key") ||
            t.contains("ciphers other than") ||
            t.contains("passphrase") ||
            t.contains("encrypted")
    }

    /** 跑一次 dropbearconvert（合并 stderr），超时就杀进程。返回它的全部输出。 */
    private fun run(bin: String, vararg args: String): String {
        return try {
            val p = ProcessBuilder(listOf(bin) + args).redirectErrorStream(true).start()
            val out = StringBuilder()
            val reader = Thread {
                try {
                    p.inputStream.bufferedReader().forEachLine { out.append(it).append('\n') }
                } catch (_: Exception) {
                }
            }.apply { isDaemon = true; start() }
            if (!p.waitFor(CONVERT_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                DiagLog.w(TAG, "dropbearconvert 超时（${CONVERT_TIMEOUT_SEC}s），杀进程")
                p.destroy()
                if (!p.waitFor(1, TimeUnit.SECONDS)) p.destroyForcibly()
            }
            reader.join(500)
            out.toString().trim()
        } catch (e: Exception) {
            DiagLog.w(TAG, "dropbearconvert 启动失败：${e.javaClass.simpleName}: ${e.message}")
            e.message ?: "dropbearconvert 启动失败"
        }
    }
}
