# Rust JNI resolves platform methods by their original class names and signatures.
-keep class com.bilimusicplayer.PlayerActivity { *; }
-keep class com.bilimusicplayer.PlayerPlatform { *; }
-keep class com.bilimusicplayer.StreamingOutput { *; }
-keepclassmembers class com.bilimusicplayer.QualityTasks {
    public java.lang.String perform(java.lang.String, java.lang.String);
}
-keepclassmembers class androidx.work.ListenableWorker {
    public boolean isStopped();
}

# Existing WorkManager jobs persist the full worker class name across app upgrades.
-keep class com.bilimusicplayer.service.download.** extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
