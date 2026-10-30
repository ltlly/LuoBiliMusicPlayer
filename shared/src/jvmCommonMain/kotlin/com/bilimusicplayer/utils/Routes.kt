package com.bilimusicplayer.utils

import java.net.URLDecoder
import java.net.URLEncoder

/** Navigation arguments are plain path segments; encode them once at the call site. */
fun encodeRouteArg(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

fun decodeRouteArg(value: String): String = URLDecoder.decode(value, Charsets.UTF_8.name())
