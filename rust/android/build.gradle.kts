plugins { id("com.android.application") version "8.12.0" }

android {
    namespace = "com.bilimusicplayer"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.bilimusicplayer"
        minSdk = 33
        targetSdk = 36
        versionCode = 13
        versionName = "1.6.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets["main"].manifest.srcFile("AndroidManifest.xml")
    sourceSets["main"].res.srcDir("../../app/src/main/res")
}
layout.buildDirectory.set(file("../target/asuka/android-gradle"))
dependencies {
    implementation("com.bilimusicplayer:bilimusicplayer:1.6.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-database:1.11.1")
    implementation("androidx.work:work-runtime:2.12.0")
}
