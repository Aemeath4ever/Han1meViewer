package com.yenaly.han1meviewer.logic

import android.content.Context
import android.util.Base64
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.SiteType
import com.yenaly.han1meviewer.USER_AGENT
import com.yenaly.han1meviewer.labelRes
import com.yenaly.han1meviewer.logic.entity.LocalFavoriteEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 把本地收藏導出成單檔離線 HTML。
 *
 * 封面會盡量下載後以 data URI 內嵌，失敗時用站點色塊佔位，保證檔案離線可看；
 * 影片連結、標題、評分與評價都會寫進卡片。圖片請求刻意使用不帶站點 cookie 的
 * 獨立 client，避免匯出過程觸發 Cloudflare 驗證或影響目前的登入會話。
 */
object LocalFavoriteHtmlExporter {

    private const val MAX_COVER_BYTES = 2 * 1024 * 1024

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    suspend fun build(
        context: Context,
        items: List<LocalFavoriteEntity>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): String = withContext(Dispatchers.IO) {
        val ordered = items.sortedWith(
            compareBy<LocalFavoriteEntity>(
                { siteOrder(it.site) },
                { -it.updatedAt },
            )
        )
        val covers = ArrayList<String?>(ordered.size)
        ordered.forEachIndexed { index, item ->
            covers += fetchCoverDataUri(item)
            withContext(Dispatchers.Main) { onProgress(index + 1, ordered.size) }
        }
        // 前置 UTF-8 BOM：有些轻量 HTML 查看器不看 <meta charset>，只靠 BOM 判断编码，
        // 否则中文会显示成乱码。浏览器会忽略 doctype 前的 BOM。
        "﻿" + render(context, ordered, covers)
    }

    private suspend fun fetchCoverDataUri(item: LocalFavoriteEntity): String? =
        withContext(Dispatchers.IO) {
            val url = resolveCoverUrl(item) ?: return@withContext null
            runCatching {
                val referer = item.videoUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}/" }
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .apply {
                        if (!referer.isNullOrBlank()) header("Referer", referer)
                    }
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body ?: return@use null
                    if (body.contentLength() > MAX_COVER_BYTES) return@use null
                    val bytes = body.bytes()
                    if (bytes.size > MAX_COVER_BYTES) return@use null
                    val mime = sniffMime(bytes, response.header("Content-Type"))
                        ?: return@use null
                    "data:$mime;base64," +
                            Base64.encodeToString(bytes, Base64.NO_WRAP)
                }
            }.getOrNull()
        }

    private fun resolveCoverUrl(item: LocalFavoriteEntity): String? {
        val raw = item.coverUrl.trim()
        if (raw.isEmpty()) return null
        val normalized = if (raw.startsWith("//")) "https:$raw" else raw
        normalized.toHttpUrlOrNull()?.let { return it.toString() }
        return item.videoUrl.trim().toHttpUrlOrNull()?.resolve(normalized)?.toString()
    }

    private fun sniffMime(bytes: ByteArray, header: String?): String? {
        val declared = header?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        if (!declared.isNullOrEmpty() && declared.startsWith("image/")) return declared
        if (bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()
        ) {
            return "image/jpeg"
        }
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte()
        ) {
            return "image/png"
        }
        if (bytes.size >= 12) {
            val head = String(bytes, 0, 12, Charsets.ISO_8859_1)
            if (head.startsWith("RIFF") && head.regionMatches(8, "WEBP", 0, 4)) {
                return "image/webp"
            }
            if (head.startsWith("GIF8")) return "image/gif"
        }
        return null
    }

    private fun render(
        context: Context,
        items: List<LocalFavoriteEntity>,
        covers: List<String?>,
    ): String {
        val header = context.getString(R.string.local_favorite_export_html_header)
        val exportedAt = SimpleDateFormat(
            "yyyy-MM-dd HH:mm",
            Locale.getDefault()
        ).format(Date())
        val summary = context.getString(
            R.string.local_favorite_export_html_summary,
            items.size,
            exportedAt,
        )
        val openText = context.getString(R.string.local_favorite_export_html_open)
        val unratedText = context.getString(R.string.local_favorite_unrated)

        return buildString {
            append(
                """<!doctype html>
<html lang="${Locale.getDefault().toLanguageTag()}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${header.escapeHtml()}</title>
<style>
:root{color-scheme:light dark;--bg:#f6f6f8;--card:#fff;--text:#1b1b1f;--muted:#6b6b76;--line:#e3e3ea;--accent:#c62b45}
@media (prefers-color-scheme:dark){:root{--bg:#121214;--card:#1d1d21;--text:#f2f2f5;--muted:#a6a6b0;--line:#303038;--accent:#ff6b81}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg,#f6f6f8);color:var(--text,#1b1b1f);font-family:system-ui,-apple-system,"Segoe UI","Noto Sans CJK SC","Microsoft YaHei",sans-serif}
header{padding:24px 18px 8px}h1{margin:0;font-size:22px}p.sub{margin:6px 0 0;color:var(--muted,#6b6b76);font-size:13px}
main{padding:12px 18px 32px}.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(260px,1fr));gap:14px}
.card{background:var(--card,#fff);border:1px solid var(--line,#e3e3ea);border-radius:14px;overflow:hidden;display:flex;flex-direction:column;color:inherit;text-decoration:none}
a.card:active,a.card:hover{border-color:var(--accent,#c62b45)}
.cover{position:relative;padding-top:56.25%;background:linear-gradient(135deg,#3a3a44,#202027)}
.cover img{position:absolute;top:0;left:0;width:100%;height:100%;object-fit:cover;display:block}
.cover .ph{position:absolute;top:0;left:0;right:0;bottom:0;display:flex;align-items:center;justify-content:center;color:#fff;font-weight:700;letter-spacing:.06em}
.badge{position:absolute;top:8px;left:8px;background:rgba(0,0,0,.62);color:#fff;font-size:11px;padding:2px 7px;border-radius:999px}
.body{padding:10px 12px 12px;display:flex;flex-direction:column;gap:6px;flex:1}
.title{font-size:14px;font-weight:600;line-height:1.45;overflow-wrap:anywhere}
.meta{display:flex;align-items:center;gap:6px;flex-wrap:wrap}
.stars{color:var(--accent,#c62b45);font-size:14px;letter-spacing:1px}
.score{color:var(--muted,#6b6b76);font-size:12px}
.comment{color:var(--muted,#6b6b76);font-size:12px;line-height:1.5;white-space:pre-wrap;overflow-wrap:anywhere}
.url{margin-top:auto;padding-top:4px;color:var(--accent,#c62b45);font-size:11px;line-height:1.4;word-break:break-all}
footer{padding:0 18px 28px;color:var(--muted,#6b6b76);font-size:11px}
</style>
</head>
<body>
<header><h1>${header.escapeHtml()}</h1><p class="sub">${summary.escapeHtml()}</p></header>
<main><div class="grid">
"""
            )
            items.forEachIndexed { index, item ->
                val siteLabel = item.resolvedSiteType?.let { context.getString(it.labelRes) }
                    ?: item.site
                val title = item.title.escapeHtml()
                val cover = covers.getOrNull(index)
                val coverHtml = if (cover != null) {
                    """<img src="$cover" alt="$title" loading="lazy">"""
                } else {
                    """<div class="ph">${siteLabel.escapeHtml()}</div>"""
                }
                val rating = item.rating.coerceIn(0, 5)
                val starText = "★".repeat(rating) + "☆".repeat(5 - rating)
                val scoreText = if (rating > 0) "$rating / 5" else unratedText
                // 星数与数字评分同时给出：个别字体缺 ★／☆ 时文本信息仍然完整。
                val metaHtml = """<div class="meta"><span class="stars">$starText</span><span class="score">${scoreText.escapeHtml()}</span></div>"""
                val commentHtml = item.comment.trim()
                    .takeIf { it.isNotEmpty() }
                    ?.let { """<div class="comment">${it.escapeHtml()}</div>""" }
                    .orEmpty()
                val safeLink = item.videoUrl.trim().takeIf {
                    it.startsWith("http://", true) || it.startsWith("https://", true)
                }
                // 卡片底部始终显示原始地址本身（而不是「前往影片页」这种泛化文字），
                // 避免离线后不知道这张卡究竟指向哪里。
                val urlText = (safeLink ?: item.videoCode).escapeHtml()
                // 整张卡片就是原始链接：封面、标题、评价、空白处都能点开原站影片页。
                val cardOpen = if (safeLink != null) {
                    """<a class="card" href="${safeLink.escapeHtml()}" title="${openText.escapeHtml()}" target="_blank" rel="noopener noreferrer">"""
                } else {
                    """<article class="card">"""
                }
                val cardClose = if (safeLink != null) "</a>" else "</article>"

                append(
                    """$cardOpen
<div class="cover">$coverHtml<span class="badge">${siteLabel.escapeHtml()}</span></div>
<div class="body">
<div class="title">$title</div>
$metaHtml
$commentHtml
<div class="url">$urlText</div>
</div>
$cardClose
"""
                )
            }
            append(
                """</div></main>
<footer>Generated by Han1meViewer</footer>
</body>
</html>
"""
            )
        }
    }

    private fun siteOrder(site: String): Int =
        SiteType.entries.indexOfFirst { it.name == site }
            .takeIf { it >= 0 } ?: Int.MAX_VALUE

    private fun String.escapeHtml(): String = buildString(length) {
        forEach { char ->
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(char)
            }
        }
    }
}
