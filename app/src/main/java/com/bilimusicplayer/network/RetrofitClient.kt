package com.bilimusicplayer.network

import android.content.Context
import com.bilimusicplayer.network.bilibili.BiliApi
import com.bilimusicplayer.network.bilibili.auth.BiliAuthApi
import com.bilimusicplayer.network.bilibili.favorite.BiliFavoriteApi
import com.bilimusicplayer.network.interceptor.PersistentCookieJar
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * DNS resolver that prioritizes IPv6 addresses.
 * B站 supports IPv6 and rate-limits by IP.
 * Since each device has a unique public IPv6 address under bridge mode,
 * using IPv6 avoids sharing the same IPv4 NAT address that may be rate-limited.
 */
private class IPv6FirstDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = Dns.SYSTEM.lookup(hostname)
        // Sort: IPv6 addresses first, then IPv4
        return addresses.sortedBy { if (it is Inet6Address) 0 else 1 }
    }
}

/**
 * Retrofit client for Bilibili APIs
 */
object RetrofitClient {

    private const val BASE_URL = "https://api.bilibili.com/"
    private const val PASSPORT_BASE_URL = "https://passport.bilibili.com/"

    private lateinit var cookieJar: PersistentCookieJar

    /**
     * Initialize with application context
     */
    fun init(context: Context) {
        cookieJar = PersistentCookieJar(context)
    }

    /**
     * Get cookie jar instance
     */
    fun getCookieJar(): PersistentCookieJar {
        return cookieJar
    }

    /**
     * OkHttp client with cookie support
     */
    private val okHttpClient: OkHttpClient by lazy {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        OkHttpClient.Builder()
            .dns(IPv6FirstDns())
            .cookieJar(cookieJar)
            .addInterceptor(loggingInterceptor)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                    .header("Referer", "https://www.bilibili.com")
                    .header("Origin", "https://www.bilibili.com")
                    .build()
                chain.proceed(request)
            }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Retrofit instance for API endpoints
     */
    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    /**
     * Retrofit instance for Passport endpoints
     */
    private val passportRetrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(PASSPORT_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    /**
     * Bilibili General API
     */
    val biliApi: BiliApi by lazy {
        retrofit.create(BiliApi::class.java)
    }

    /**
     * Bilibili Auth API
     */
    val biliAuthApi: BiliAuthApi by lazy {
        passportRetrofit.create(BiliAuthApi::class.java)
    }

    /**
     * Bilibili Favorite API
     */
    val biliFavoriteApi: BiliFavoriteApi by lazy {
        retrofit.create(BiliFavoriteApi::class.java)
    }
}
