// Terminal conformance harness — the differential test bed for the in-house
// terminal implementation (see docs/terminal-rewrite-plan.md, phase 0).
//
// This module is deliberately NOT an Android module and deliberately NOT a
// dependency of :app:
//
//  * Pure JVM, so it runs in seconds on CI and on a laptop, and so the emulator
//    core can be tested without a device or an emulator.
//  * Not an app dependency, so nothing here (least of all the vendored oracle jar
//    or the android.* stand-ins) can reach the APK.
//
// The `android.util` / `android.graphics` classes under src/main/java are stubs
// that exist only so the reference emulator can link on a plain JVM. Their
// presence is why this module must never be compiled into the app.

plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    // The reference emulator, used strictly as a black-box oracle for differential
    // testing: it is fed the corpus and its screen is compared against the in-house
    // implementation. Vendored rather than resolved so the harness is offline-capable
    // and the oracle's bytes are pinned; tools/fetch-oracle.sh regenerates it and
    // verifies the recorded SHA-256.
    implementation(files("libs/termux-terminal-emulator-0.118.1-classes.jar"))
}

/** Directory holding the recorded byte streams (see tools/). */
val corpusDir: Directory = layout.projectDirectory.dir("corpus")

/**
 * Register one harness invocation.
 *
 * @param taskName Gradle task name.
 * @param description one-line description shown by `gradle tasks`.
 * @param command harness subcommand (`check`, `coverage`, `selftest`, `list`).
 */
fun harnessTask(taskName: String, description: String, command: String) =
    tasks.register<JavaExec>(taskName) {
        group = "verification"
        this.description = description
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("dsh.conformance.Harness")
        args(command, corpusDir.asFile.absolutePath)
        // The harness prints a report; a non-zero exit means real drift or an
        // uncovered capability, and must fail the build.
        isIgnoreExitValue = false
    }

harnessTask("conformanceCoverage", "Report which terminal capabilities the corpus exercises", "coverage")
harnessTask("conformanceSelftest", "Prove the harness can fail (determinism, discrimination, detection)", "selftest")
harnessTask("conformanceCheck", "Compare every registered terminal implementation", "check")
harnessTask("conformanceList", "List the corpus cases", "list")

/**
 * Everything that must hold before the harness can be trusted.
 *
 * `coverage` guards against a corpus that stops exercising a capability, and
 * `selftest` guards against a harness that has silently stopped being able to
 * fail. `check` is included because with one implementation registered it still
 * verifies determinism under sliced feeding.
 */
/**
 * Bind the vendored oracle's version to the version the app compiles against (audit M6).
 *
 * The oracle is a **vendored** jar (`libs/termux-terminal-emulator-<version>-classes.jar`) while
 * `:app` resolves `terminal-view`/`terminal-emulator` from JitPack at build time. Nothing tied
 * those two together: bumping the app to `terminal-view:0.119.0` while leaving the oracle and the
 * corpus untouched kept this gate green — even though "upgrading terminal-view" is one of the
 * things the README claims the gate covers. This task fails when they disagree, and also fails
 * when either side cannot be determined (a check that cannot see its inputs must not pass).
 */
val oracleVersions = fileTree("libs") { include("termux-terminal-emulator-*-classes.jar") }
    .files.map { it.name.removePrefix("termux-terminal-emulator-").removeSuffix("-classes.jar") }
val appTerminalVersions = Regex("com\\.github\\.termux\\.termux-app:terminal-(?:view|emulator):([0-9][^\"'\\s]*)")
    .findAll(file("../app/build.gradle.kts").readText())
    .map { it.groupValues[1] }
    .toSet()

tasks.register("conformanceOracleVersion") {
    group = "verification"
    description = "Bind the vendored oracle version to :app's terminal-view/terminal-emulator version"
    doLast {
        val oracle = oracleVersions.singleOrNull()
            ?: throw GradleException(
                "libs/ 下应有且只有一个 termux-terminal-emulator-*-classes.jar（实际 ${oracleVersions.size} 个）" +
                    " —— 取不到就说明这条检查做不了，不能当通过")
        if (appTerminalVersions.isEmpty()) {
            throw GradleException(
                "在 android/app/build.gradle.kts 里没解析出 terminal-view/terminal-emulator 的版本" +
                    " —— 依赖改名/换声明方式后这条检查会静默失效，所以这里直接失败")
        }
        if (!appTerminalVersions.contains(oracle)) {
            throw GradleException(
                "oracle 版本（$oracle）与 :app 声明的 terminal-* 版本（${appTerminalVersions.joinToString()}）不一致。" +
                    "升级依赖时必须一起更新 oracle（tools/fetch-oracle.sh）与语料，" +
                    "否则这道门禁验的不是 app 真正用的那份实现。")
        }
        logger.lifecycle("✓ oracle 与 :app 的 terminal 版本一致：$oracle")
    }
}

tasks.register("conformance") {
    group = "verification"
    description = "Run the full terminal conformance gate (version binding + coverage + selftest + check)"
    dependsOn("conformanceOracleVersion", "conformanceCoverage", "conformanceSelftest", "conformanceCheck")
}
