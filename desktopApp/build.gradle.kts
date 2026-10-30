import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okio)
    implementation(libs.mediamp.all)

    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    when {
        os.contains("mac") && arch.contains("aarch64") -> runtimeOnly(libs.mediamp.runtime.macos.arm64)
        os.contains("mac") -> runtimeOnly(libs.mediamp.runtime.macos.x64)
        os.contains("win") && arch.contains("aarch64") -> runtimeOnly(libs.mediamp.runtime.windows.arm64)
        os.contains("win") -> runtimeOnly(libs.mediamp.runtime.windows.x64)
        // 跨平台打 Windows 包：./gradlew :desktopApp:packageUberJarForCurrentOS -PwindowsPackage
        project.hasProperty("windowsPackage") -> {
            runtimeOnly(libs.mediamp.runtime.windows.x64)
            // skiko 渲染库随宿主OS解析，打Windows包必须显式带上
            implementation("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:0.150.2")
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.bilimusicplayer.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi)
            packageName = "BiliMusicPlayer"
            packageVersion = "1.5.4"
            description = "Bilibili music player for Android, macOS and Windows"
            vendor = "LuoBiliMusicPlayer"
        }
    }
}