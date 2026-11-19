import java.util.Properties

plugins { id("com.android.application") version "8.12.0" }

layout.buildDirectory.set(file("../target/asuka/android-gradle"))
android {
    namespace = "com.bilimusicplayer"
    compileSdk = 36
    System.getenv("ANDROID_NDK_HOME")?.let { path ->
        val metadata = Properties()
        file("$path/source.properties").inputStream().use { metadata.load(it) }
        ndkPath = path
        ndkVersion = metadata.getProperty("Pkg.Revision")
    }
    defaultConfig {
        applicationId = "com.bilimusicplayer"
        minSdk = 33
        targetSdk = 36
        versionCode = 19
        versionName = "1.7.4"
    }
    buildTypes {
        release {
            val abi = providers.gradleProperty("playerAbi").getOrElse("arm64-v8a")
            val supported = setOf("arm64-v8a", "armeabi-v7a", "x86_64")
            require(abi == "universal" || abi in supported) { "Unsupported player ABI: $abi" }
            ndk { abiFilters += if (abi == "universal") supported else setOf(abi) }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets["main"].manifest.srcFile("AndroidManifest.xml")
    sourceSets["main"].res.srcDir("../../app/src/main/res")
}
dependencies {
    implementation("com.bilimusicplayer:bilimusicplayer:1.7.4")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-database:1.11.1")
    implementation("androidx.work:work-runtime:2.12.0")
}
