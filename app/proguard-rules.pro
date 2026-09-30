# Add project specific ProGuard rules here.

# Keep Retrofit interfaces
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes *Annotation*
# Retrofit suspend methods inspect Continuation<T>; full-mode R8 otherwise
# removes its generic signature even when the API method itself is kept.
-keep interface kotlin.coroutines.Continuation { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# Keep Gson models
-keep class com.bilimusicplayer.network.bilibili.** { *; }
-keep class com.bilimusicplayer.data.model.** { *; }
-keep class com.bilimusicplayer.service.SavedMediaItem { *; }
-keep class com.bilimusicplayer.network.interceptor.PersistentCookieJar$SerializableCookie { *; }

# Keep Room entities
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *

# Keep Media3 classes
-keep class androidx.media3.** { *; }

# Keep OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }
