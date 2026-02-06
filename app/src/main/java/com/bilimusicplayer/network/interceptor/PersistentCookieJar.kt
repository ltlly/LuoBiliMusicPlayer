package com.bilimusicplayer.network.interceptor

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Persistent cookie storage using SharedPreferences
 */
class PersistentCookieJar(context: Context) : CookieJar {
    private val prefs: SharedPreferences = context.getSharedPreferences("cookies", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val cookies = mutableMapOf<String, MutableList<SerializableCookie>>()

    init {
        loadAll()
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val key = url.host
        val newCookies = cookies.map { SerializableCookie(it) }

        synchronized(this.cookies) {
            val existing = this.cookies.getOrPut(key) { mutableListOf() }

            // Remove old cookies with same name
            newCookies.forEach { newCookie ->
                existing.removeAll { it.name == newCookie.name }
            }

            // Add new cookies
            existing.addAll(newCookies)

            Log.d(TAG, "Saved ${newCookies.size} cookies for $key: ${newCookies.map { "${it.name}=${it.value} (domain=${it.domain}, hostOnly=${it.hostOnly})" }}")

            // Save to SharedPreferences
            save(key, existing)
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val requestHost = url.host
        val now = System.currentTimeMillis()

        synchronized(cookies) {
            Log.d(TAG, "Loading cookies for $requestHost. Total stored hosts: ${cookies.keys}")

            val matchingCookies = mutableListOf<SerializableCookie>()

            // Check all stored cookie domains
            cookies.forEach { (storedHost, cookieList) ->
                Log.d(TAG, "  Checking host $storedHost with ${cookieList.size} cookies")
                cookieList.forEach { cookie ->
                    // Match if:
                    // 1. Exact host match (hostOnly=true)
                    // 2. Domain match (hostOnly=false and requestHost ends with domain)
                    val matches = if (cookie.hostOnly) {
                        requestHost == cookie.domain
                    } else {
                        requestHost == cookie.domain || requestHost.endsWith(".${cookie.domain}")
                    }

                    Log.d(TAG, "    Cookie ${cookie.name}: domain=${cookie.domain}, hostOnly=${cookie.hostOnly}, matches=$matches, expired=${cookie.expiresAt < now && cookie.expiresAt != -1L}")

                    if (matches && (cookie.expiresAt == -1L || cookie.expiresAt > now)) {
                        matchingCookies.add(cookie)
                    }
                }
            }

            Log.d(TAG, "Loading cookies for $requestHost: found ${matchingCookies.size} cookies: ${matchingCookies.map { it.name }}")

            return matchingCookies.map { it.toCookie() }
        }
    }

    companion object {
        private const val TAG = "PersistentCookieJar"
    }

    /**
     * Clear all cookies
     */
    fun clear() {
        synchronized(cookies) {
            cookies.clear()
            prefs.edit().clear().apply()
        }
    }

    /**
     * Clear cookies for specific host
     */
    fun clear(host: String) {
        synchronized(cookies) {
            cookies.remove(host)
            prefs.edit().remove(host).apply()
        }
    }

    private fun save(key: String, cookies: List<SerializableCookie>) {
        val json = gson.toJson(cookies)
        prefs.edit().putString(key, json).apply()
    }

    private fun loadAll() {
        synchronized(cookies) {
            prefs.all.forEach { (key, value) ->
                if (value is String) {
                    try {
                        val type = object : TypeToken<List<SerializableCookie>>() {}.type
                        val cookieList: List<SerializableCookie> = gson.fromJson(value, type)
                        cookies[key] = cookieList.toMutableList()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    /**
     * Serializable cookie wrapper
     */
    data class SerializableCookie(
        val name: String,
        val value: String,
        val expiresAt: Long,
        val domain: String,
        val path: String,
        val secure: Boolean,
        val httpOnly: Boolean,
        val hostOnly: Boolean
    ) {
        constructor(cookie: Cookie) : this(
            name = cookie.name,
            value = cookie.value,
            expiresAt = cookie.expiresAt,
            domain = cookie.domain,
            path = cookie.path,
            secure = cookie.secure,
            httpOnly = cookie.httpOnly,
            hostOnly = cookie.hostOnly
        )

        fun toCookie(): Cookie {
            return Cookie.Builder()
                .name(name)
                .value(value)
                .expiresAt(expiresAt)
                .apply {
                    if (hostOnly) {
                        hostOnlyDomain(domain)
                    } else {
                        domain(domain)
                    }
                }
                .path(path)
                .apply {
                    if (secure) secure()
                    if (httpOnly) httpOnly()
                }
                .build()
        }
    }
}
