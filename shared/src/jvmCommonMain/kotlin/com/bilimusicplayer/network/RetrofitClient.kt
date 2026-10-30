package com.bilimusicplayer.network

import com.bilimusicplayer.settings.AppStorage
import com.bilimusicplayer.network.bilibili.BiliApi
import com.bilimusicplayer.network.bilibili.auth.BiliAuthApi
import com.bilimusicplayer.network.bilibili.favorite.BiliFavoriteApi
import com.bilimusicplayer.network.interceptor.PersistentCookieJar
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.Inet6Address
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private lateinit var cookieJar: PersistentCookieJar
    private lateinit var requestGate: BiliRequestGate

    /**
     * Initialize with application context
     */
    fun init(storage: AppStorage) {
        cookieJar = PersistentCookieJar(storage)
        requestGate = BiliRequestGate(storage)
    }

    /**
     * Get cookie jar instance
     */
    fun getCookieJar(): PersistentCookieJar {
        return cookieJar
    }

    /**
     * Bilibili's web API expects a buvid3 cookie. Without it, signed favorite
     * requests are routinely answered with HTTP 412 even when the account is
     * logged in. The finger endpoint returns a browser-equivalent pair.
     */
    suspend fun ensureBuvidCookies() = withContext(Dispatchers.IO) {
        if (cookieJar.loadForRequest("https://api.bilibili.com".toHttpUrl()).any { it.name == "buvid3" }) return@withContext
        runCatching {
            val client = OkHttpClient()
            val request = Request.Builder()
                .url("https://api.bilibili.com/x/frontend/finger/spi")
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://www.bilibili.com")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching
                val body = response.body?.string() ?: return@runCatching
                val json = com.google.gson.JsonParser.parseString(body).asJsonObject
                val data = json.getAsJsonObject("data") ?: return@runCatching
                val b3 = data.get("b_3")?.asString ?: return@runCatching
                val b4 = data.get("b_4")?.asString ?: return@runCatching
                val expires = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000
                cookieJar.saveFromResponse(
                    "https://bilibili.com".toHttpUrl(),
                    listOf(
                        okhttp3.Cookie.Builder().name("buvid3").value(b3)
                            .domain("bilibili.com").path("/").expiresAt(expires).build(),
                        okhttp3.Cookie.Builder().name("buvid4").value(b4)
                            .domain("bilibili.com").path("/").expiresAt(expires).build(),
                    ),
                )
            }
        }
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
            .addInterceptor(requestGate)
            .addInterceptor(loggingInterceptor)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT)
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
            .validateEagerly(true)
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
            .validateEagerly(true)
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