package com.bilimusicplayer.network.bilibili.favorite

import com.google.gson.annotations.SerializedName

/**
 * Navigation response for WBI keys
 */
data class NavResponse(
    val code: Int,
    val message: String,
    val data: NavData?
)

data class NavData(
    @SerializedName("wbi_img")
    val wbiImg: WbiImg,
    val isLogin: Boolean,
    val uname: String? = null,
    val mid: Long? = null,
    val face: String? = null
)

data class WbiImg(
    @SerializedName("img_url")
    val imgUrl: String,
    @SerializedName("sub_url")
    val subUrl: String
)

/**
 * Favorite list response
 */
data class FavoriteListResponse(
    val code: Int,
    val message: String,
    val data: FavoriteListData?
)

data class FavoriteListData(
    val count: Int,
    val list: List<FavoriteFolder>
)

data class FavoriteFolder(
    val id: Long,
    val fid: Long,
    val mid: Long,
    val title: String,
    val cover: String,
    @SerializedName("media_count")
    val mediaCount: Int,
    val attr: Int,
    val ctime: Long,
    val mtime: Long
)

/**
 * Favorite resource IDs response
 */
data class FavoriteResourceResponse(
    val code: Int,
    val message: String,
    val data: FavoriteResourceData?
)

data class FavoriteResourceData(
    val info: FolderInfo,
    val medias: List<FavoriteMedia>?
)

data class FolderInfo(
    val id: Long,
    val fid: Long,
    val title: String,
    val cover: String,
    @SerializedName("media_count")
    val mediaCount: Int
)

data class FavoriteMedia(
    val id: Long,
    val type: Int,
    val title: String,
    val cover: String,
    val bvid: String,
    val upper: Upper,
    val duration: Int,
    val intro: String,
    val ctime: Long,
    val pubtime: Long
)

data class Upper(
    val mid: Long,
    val name: String,
    val face: String
)

/**
 * Video detail response
 */
data class VideoDetailResponse(
    val code: Int,
    val message: String,
    val data: VideoDetail?
)

data class VideoDetail(
    val bvid: String,
    val aid: Long,
    val videos: Int,
    val tid: Int,
    val tname: String,
    val pic: String,
    val title: String,
    val desc: String,
    val owner: Owner,
    val cid: Long,
    val duration: Int,
    val pubdate: Long,
    val pages: List<VideoPage>?
)

data class Owner(
    val mid: Long,
    val name: String,
    val face: String
)

data class VideoPage(
    val cid: Long,
    val page: Int,
    val part: String,
    val duration: Int
)

/**
 * Play URL response
 */
data class PlayUrlResponse(
    val code: Int,
    val message: String,
    val data: PlayUrlData?
)

data class PlayUrlData(
    val quality: Int,
    val format: String,
    val timelength: Long,
    val durl: List<DashUrl>?,
    val dash: DashInfo?
)

data class DashUrl(
    val url: String,
    val length: Long,
    val size: Long
)

data class DashInfo(
    val duration: Long,
    val video: List<DashStream>?,
    val audio: List<DashStream>?
)

data class DashStream(
    val id: Int,
    val baseUrl: String,
    val backupUrl: List<String>?,
    val bandwidth: Int,
    val mimeType: String,
    val codecs: String,
    @SerializedName("SegmentBase")
    val segmentBase: SegmentBase?
)

data class SegmentBase(
    @SerializedName("Initialization")
    val initialization: String?,
    @SerializedName("indexRange")
    val indexRange: String?
)
