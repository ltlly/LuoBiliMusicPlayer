package com.bilimusicplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale

/**
 * B站封面加载统一入口。
 *
 * 修复"滑动列表时封面慢慢加载"的三个根因：
 * 1. API 返回的是原图 URL（单张几百KB~1MB），列表却只需要小图 —— 现在统一请求
 *    B站 CDN 缩略图（@宽w_高h_1c.webp，实测 1MB → 7.5KB，约 136 倍缩小）。
 * 2. 各界面之前用不同 size 构造请求，缓存 key 各不相同，同一张封面被反复下载 ——
 *    现在所有列表/小图场景使用同一规范 size（列表 320px / 播放页 960px），
 *    同一 URL 全局只下载一次，内存缓存直接复用。
 * 3. Precision.INEXACT 允许更大的缓存结果命中更小的显示需求，进一步提高复用率。
 */

/** 列表小图规范边长（px）。所有小尺寸场景共用此值，保证缓存 key 一致 */
private const val LIST_COVER_PX = 320

/** 播放页大图规范边长（px） */
private const val PLAYER_COVER_PX = 960

/** URL 基础规范化：协议相对 // → https，http → https */
fun normalizeCoverUrl(url: String): String = when {
    url.startsWith("//") -> "https:$url"
    url.startsWith("http://") -> url.replaceFirst("http://", "https://")
    else -> url
}

/**
 * 生成 B站 CDN 缩略图 URL。
 * hdslb.com 的图片支持追加 @宽w_高h_1c.webp 即时缩放（_1c = 居中裁剪）。
 * 非 hdslb 域名原样返回（不拼参数）。
 */
fun biliThumbUrl(url: String, widthPx: Int, heightPx: Int): String {
    val normalized = normalizeCoverUrl(url)
    if (!normalized.contains("hdslb.com")) return normalized
    if (normalized.contains("@")) return normalized // 已带缩放参数
    return "${normalized}@${widthPx}w_${heightPx}h_1c.webp"
}

/** 列表缩略图 URL（320×180 webp，约 10-20KB） */
fun listCoverUrl(url: String): String = biliThumbUrl(url, LIST_COVER_PX, LIST_COVER_PX * 9 / 16)

/** 播放页大图 URL（960×540 webp，约 75KB；含模糊背景复用） */
fun playerCoverUrl(url: String): String = biliThumbUrl(url, PLAYER_COVER_PX, PLAYER_COVER_PX * 9 / 16)

/**
 * 统一的列表封面组件。
 * 固定请求 size + INEXACT 精度 → 与其它小图场景共享同一份磁盘/内存缓存。
 */
@Composable
fun BiliCoverThumb(
    url: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp,
    contentDescription: String? = "封面"
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier.clip(shape),
        contentAlignment = Alignment.Center
    ) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(listCoverUrl(url))
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .crossfade(false)
                    .size(LIST_COVER_PX)               // 固定 size：统一缓存 key
                    .precision(Precision.INEXACT)      // 允许大图缓存命中小图需求
                    .scale(Scale.FIT)
                    .build(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * 统一的播放页大封面组件（含复用：大图缓存可命中，小图场景各自请求小图）。
 */
@Composable
fun BiliCoverLarge(
    url: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = "封面"
) {
    val context = LocalContext.current
    if (!url.isNullOrBlank()) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(playerCoverUrl(url))
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .crossfade(false)
                .size(PLAYER_COVER_PX)
                .precision(Precision.INEXACT)
                .scale(Scale.FIT)
                .build(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    } else {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                modifier = Modifier.size(120.dp)
            )
        }
    }
}
