package com.bilimusicplayer.network.interceptor

import com.bilimusicplayer.core.Log
import com.bilimusicplayer.settings.AppStorage
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Persistent cookie storage backed by the shared settings store.
 *
 * The whole host -> cookies map is serialized as one JSON document, so the
 * Android and desktop builds behave identically and startup performs a single read.
 */
class PersistentCookieJar(private val storage: AppStorage) : CookieJar {

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
            newCookies.forEach { newCookie ->
                existing.removeAll { it.name == newCookie.name }
            }
            existing.addAll(newCookies)
            Log.d(TAG, "Saved ${newCookies.size} cookies for $key")
            persist()
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val requestHost = url.host
        val now = System.currentTimeMillis()

        synchronized(cookies) {
            val matchingCookies = mutableListOf<SerializableCookie>()

            cookies.forEach { (_, cookieList) ->
                cookieList.forEach { cookie ->
                    val matches = if (cookie.hostOnly) {
                        requestHost == cookie.domain
                    } else {
                        requestHost == cookie.domain || requestHost.endsWith(".${cookie.domain}")
                    }
                    if (matches && (cookie.expiresAt == -1L || cookie.expiresAt > now)) {
                        matchingCookies.add(cookie)
                    }
                }
            }

            Log.d(TAG, "Loading cookies for $requestHost: found ${matchingCookies.size} cookies")
            return matchingCookies.map { it.toCookie() }
        }
    }

    fun clear() {
        synchronized(cookies) {
            cookies.clear()
            persist()
        }
    }

    fun clear(host: String) {
        synchronized(cookies) {
            cookies.remove(host)
            persist()
        }
    }

    private fun persist() {
        storage.saveCookieStoreJson(gson.toJson(cookies))
    }

    private fun loadAll() {
        val json = storage.cookieStoreJson() ?: return
        runCatching {
            val type = object : TypeToken<Map<String, List<SerializableCookie>>>() {}.type
            val stored: Map<String, List<SerializableCookie>> = gson.fromJson(json, type)
            stored.forEach { (host, list) -> cookies[host] = list.toMutableList() }
        }.onFailure { Log.e(TAG, "Failed to load cookies", it) }
    }

    /** Serializable cookie wrapper. */
    data class SerializableCookie(
        val name: String,
        val value: String,
        val expiresAt: Long,
        val domain: String,
        val path: String,
        val secure: Boolean,
        val httpOnly: Boolean,
        val hostOnly: Boolean,
    ) {
        constructor(cookie: Cookie) : this(
            name = cookie.name,
            value = cookie.value,
            expiresAt = cookie.expiresAt,
            domain = cookie.domain,
            path = cookie.path,
            secure = cookie.secure,
            httpOnly = cookie.httpOnly,
            hostOnly = cookie.hostOnly,
        )

        fun toCookie(): Cookie = Cookie.Builder()
            .name(name)
            .value(value)
            .expiresAt(expiresAt)
            .apply {
                if (hostOnly) hostOnlyDomain(domain) else domain(domain)
            }
            .path(path)
            .apply {
                if (secure) secure()
                if (httpOnly) httpOnly()
            }
            .build()
    }

    companion object {
        private const val TAG = "PersistentCookieJar"
    }
}
