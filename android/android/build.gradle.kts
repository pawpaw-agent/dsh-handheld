// Top-level build file for the dsh-handheld Android app
//
// 这里只有一个插件：AGP。**Kotlin 不再单独声明** —— AGP 9 起内置 Kotlin 编译
// （`android.builtInKotlin` 默认 true），自带 KGP 2.2.10。此时再应用
// `org.jetbrains.kotlin.android` 会直接构建失败：
//   The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin
//   support since AGP 9.0.
//
// 内置 Kotlin 顺带解决了一件事：`android.kotlinOptions{ jvmTarget }` 不再需要，
// 它默认取 `android.compileOptions.targetCompatibility`（本项目是 17）。
//
// 若要改用比 AGP 自带的 2.2.10 更高的 KGP，**不能**再写进 plugins{} 块，只能在
// 顶级 build 文件里用 buildscript { dependencies { classpath(...) } } 覆盖。
// 迁移说明：https://developer.android.com/build/migrate-to-built-in-kotlin
plugins {
    id("com.android.application") version "9.4.0" apply false
}
