plugins {
    id("com.android.application")
    // 不再声明 `org.jetbrains.kotlin.android`：AGP 9 内置 Kotlin，见顶级 build.gradle.kts。
}

// ── 发布签名 ────────────────────────────────────────────────────────────────
// 签名材料经环境变量注入（CI 从 GitHub Secrets 解出），**不入库**：公开仓库里的
// 签名密钥等于任何人都能签出可覆盖安装的“升级包”。
//
// 缺失时回退 debug 签名，使 fork / 本地构建 / PR 构建仍可产出 APK。这种产物**同样不可
// 调试**（release 变体显式 isDebuggable = false），但签的是公开的 debug 密钥 ——
// 任何人都能签出可覆盖安装的“升级包”，因此不得对外分发。CI 会对此告警。
val releaseStorePath: String? = System.getenv("SIGNING_KEYSTORE_PATH")
val releaseStorePassword: String? = System.getenv("SIGNING_STORE_PASSWORD")
val releaseKeyAlias: String? = System.getenv("SIGNING_KEY_ALIAS")
val releaseKeyPassword: String? = System.getenv("SIGNING_KEY_PASSWORD")
val hasReleaseSigning: Boolean = releaseStorePath != null &&
    file(releaseStorePath).exists() &&
    !releaseStorePassword.isNullOrEmpty() &&
    !releaseKeyAlias.isNullOrEmpty() &&
    !releaseKeyPassword.isNullOrEmpty()

android {
    namespace = "com.dshhandheld.app"
    // 36 是 androidx.core 1.18.0 的下限（其 AAR 元数据 minCompileSdk=36），也是 AGP 9
    // 默认 build-tools（36.0.0）对应的平台版本。再往上（37）留给 core-ktx 1.19.0，
    // 见 README「版本与升级」。
    compileSdk = 36

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStorePath!!)
                storeType = "PKCS12"
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.dshhandheld.app"
        minSdk = 26
        // targetSdk 刻意停在 34，**不跟着 compileSdk 走**。
        //
        // compileSdk 只决定「能调用哪些 API」，targetSdk 决定「系统按哪一版的行为对待
        // 这个 App」——后者是运行时行为变更，而 34→35 恰好是最重的一档：Android 15 起
        // 强制 edge-to-edge（系统栏区域不再自动让位），35→36 还有一批前台服务与权限
        // 收紧。本项目的主界面是一整个 WebView 加一层终端，正是最吃 insets 的形状。
        //
        // 依赖升级不该顺带改变运行时行为，所以这一档单列，需要真机回归后再动。
        // 另注：AGP 9 起 `android.sdk.defaultTargetSdkToCompileSdkIfUnset` 默认为 true，
        // 即**不写 targetSdk 就会自动跟随 compileSdk** —— 这里必须显式写死。
        targetSdk = 34
        versionCode = 41
        versionName = "0.1.14"
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            // 发布包必须不可调试：debuggable=true 会让 `run-as` 无需 root 就能读走
            // 应用私有目录（含凭据），并使 WebView 远程调试对整个局域网开放。
            isDebuggable = false
            // R8 暂不开启：release 变体从未构建过，首次同时启用压缩/混淆与签名，
            // 一旦 R8 裁掉反射用到的类将无法在本机复现（且当前无法真机验证）。
            // 作为独立的后续项处理。见 README「版本与升级」。
            isMinifyEnabled = false
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    packaging {
        jniLibs {
            // 等价于原先写在 AndroidManifest 里的 android:extractNativeLibs="true"。
            // AGP 9 起在 manifest 里**显式**写那个属性会直接构建失败：
            //   android:extractNativeLibs is set to "true" in AndroidManifest.xml. Avoid
            //   setting android:extractNativeLibs="true" explicitly in AndroidManifest.xml,
            //   and instead set android.packagingOptions.jniLibs.useLegacyPackaging to true
            //   in the build script.
            //
            // 这里**必须**是 true，不是风格问题：dbclient / dropbearkey 是以**子进程**方式
            // 执行的，要有文件系统上的真实路径才能 execve。DshApp.onCreate 从
            // applicationInfo.nativeLibraryDir 取 libdbclient.so 的绝对路径，且要求
            // it.exists() —— useLegacyPackaging=false 时原生库不再解包出来（直接以页对齐
            // 方式留在 APK 里供 dlopen），那个路径不存在，binPath 会保持 null，隧道起不来。
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 这里原先有一个 `kotlinOptions { jvmTarget = "17" }`，AGP 9 起被移除：
    // 内置 Kotlin 的 `kotlin.compilerOptions.jvmTarget` 默认就取上面的
    // `compileOptions.targetCompatibility`（17），显式再写一遍是多余的。
    // 不显式写还有一个好处：两边不会各自漂移。
}

dependencies {
    // 升级上限受**两个**独立约束，二者都必须满足：
    //
    // 1) AAR 元数据的 minCompileSdk —— 不得超过当前 compileSdk 36
    //    core 1.13.1 → 34；1.15.0 → 35；1.17.0/1.18.0 → 36；1.19.0 → 37（并要求 AGP ≥ 9.1.0）
    //    webkit 1.15.0/1.17.0 → 33
    //    实测方法：解包 AAR 看 META-INF/com/android/build/gradle/aar-metadata.properties
    //
    // 2) 传递依赖的 kotlin-stdlib metadata 版本 —— 不得高于 Kotlin 编译器可读上限。
    //    这条曾把项目锁死：Kotlin 1.9.22 只能读到 metadata 2.0.0，而 webkit 1.16.0 起
    //    引入 kotlin-stdlib 2.1.20（metadata 2.1.0），一升就编译失败：
    //      "Module was compiled with an incompatible version of Kotlin"
    //    改用 AGP 9 内置 Kotlin（KGP 2.2.10）后这条不再构成限制 —— core-ktx 1.18.0 与
    //    webkit 1.17.0 都只依赖 kotlin-stdlib 2.1.20。
    //
    // 因此当前上限是 core-ktx 1.18.0 + webkit 1.17.0。再往上走 core-ktx 1.19.0 需要
    // compileSdk 37（连带 build-tools 37 与 `platforms;android-37.0`）—— 单列一步。
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.webkit:webkit:1.17.0")
    // Termux 终端组件（GPL-3.0，见项目 LICENSE）：TUI 模式的终端渲染 + 软键盘交互。
    // JitPack 多模块坐标：group 为 termux/termux-app 仓库点分路径（否则 Gradle
    // 会把 4 段坐标当成 group:artifact:version:module 而找不到）。
    // terminal-view 依赖 terminal-emulator（含 NDK 原生渲染，x86/x86_64/arm 全部 ABI）。
    implementation("com.github.termux.termux-app:terminal-view:0.118.1")
}
