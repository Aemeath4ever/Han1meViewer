package com.yenaly.han1meviewer.logic

import android.annotation.SuppressLint
import android.util.Log
import com.yenaly.han1meviewer.EMPTY_STRING
import com.yenaly.han1meviewer.HanimeConstants.HANIME_URL
import com.yenaly.han1meviewer.HanimeResolution
import com.yenaly.han1meviewer.LOCAL_DATE_FORMAT
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.Preferences.isAlreadyLogin
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.logic.exception.LoginStateExpiredException
import com.yenaly.han1meviewer.logic.exception.ParseException
import com.yenaly.han1meviewer.logic.model.HanimeInfo
import com.yenaly.han1meviewer.logic.model.HanimePreview
import com.yenaly.han1meviewer.logic.model.HanimeSearchResult
import com.yenaly.han1meviewer.logic.model.HanimeVideo
import com.yenaly.han1meviewer.logic.model.CreatorUploadingItem
import com.yenaly.han1meviewer.logic.model.HomePage
import com.yenaly.han1meviewer.logic.model.MyListItems
import com.yenaly.han1meviewer.logic.model.MySubscriptions
import com.yenaly.han1meviewer.logic.model.Playlists
import com.yenaly.han1meviewer.logic.model.SubscriptionItem
import com.yenaly.han1meviewer.logic.model.SubscriptionVideosItem
import com.yenaly.han1meviewer.logic.model.UserAccount
import com.yenaly.han1meviewer.logic.model.VideoComments
import com.yenaly.han1meviewer.logic.state.PageLoadingState
import com.yenaly.han1meviewer.logic.state.VideoLoadingState
import com.yenaly.han1meviewer.logic.state.WebsiteState
import com.yenaly.han1meviewer.toVideoCode
import kotlinx.datetime.LocalDate
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.select.Elements

/**
 * @project Han1meViewer
 * @author Yenaly Liew
 * @time 2023/07/31 031 16:43
 */
object Parser {

    /**
     * 所需 Regex
     */
    object Regex {
        val videoSource = Regex("""const source = '(.+)'""")
        val viewAndUploadTime = Regex("""(觀看次數|观看次数)：(.+次) *(\d{4}-\d{2}-\d{2})""")

        // missav 影片頁的 seek 縮略圖，可用於兜底提取 surrit.com 的影片 UUID（HTML 中斜槓可能被轉義為 \/）
        val missavSurritId = Regex("""surrit\.com\\?/([a-f0-9-]+)\\?/""")

        // AV 站的 CDN 節點（t26 / t27 / t30 等）可能已失效，需要統一切換成可用的 t33
        val avCdnHost = Regex("""^(\s*(?:https?:)?//)t\d+\.cdn2020\.com(?=[/:]|\z)""", RegexOption.IGNORE_CASE)
    }

    fun extractTokenFromLoginPage(body: String): String {
        val parseBody = Jsoup.parse(body).body()
        return parseBody.selectFirst("input[name=_token]")?.attr("value")
            ?: throw ParseException("Can't find csrf token from login page.")
    }

    fun homePageVer2(body: String): WebsiteState<HomePage> {
        val isAVSite = Preferences.baseUrl == HANIME_URL[3]
        val parseBody = Jsoup.parse(body).body()
        val csrfToken = parseBody.selectFirst("input[name=_token]")?.attr("value") // csrf token
        val homePageParse = parseBody.select("div[id=home-rows-wrapper] > div")

        // 用户信息
        val userInfo = parseBody.selectFirst("div[id=user-modal-dp-wrapper]")
        val avatarUrl: String? = userInfo?.selectFirst("img")?.absUrl("src")
        val username: String? = userInfo?.getElementById("user-modal-name")?.text()
        val userHomePageLink = parseBody.getElementById("user-modal-trigger")?.attr("href")?:""

        if (isAlreadyLogin && isLoginStateExpired(userHomePageLink, username)) {
            return WebsiteState.Error(LoginStateExpiredException(getString(R.string.login_state_expired)))
        }

        val userIdRegex = Regex("""/user/(\d+)""")
        val userId: String = userIdRegex.find(userHomePageLink)?.groupValues?.get(1) ?: ""
        Log.i("userInfo","name:$username;id:$userId")

        // 头图及其描述
        val bannerCSS = parseBody.selectFirst("div[id=home-banner-wrapper]")
        val bannerImg = bannerCSS?.previousElementSibling()
        val bannerTitle = bannerImg?.selectFirst("img")?.attr("alt")
            .logIfParseNull(Parser::homePageVer2.name, "bannerTitle")
        val bannerPic = bannerImg?.select("img")?.let { imgList ->
            imgList.getOrNull(1)?.absUrl("src") ?: imgList.getOrNull(0)?.absUrl("src")
        }?.logIfParseNull(Parser::homePageVer2.name, "bannerPic")
        val bannerDesc = bannerCSS?.selectFirst("h4")?.ownText()
        val bannerVideoCodeScript = parseBody.select("script")
            .firstOrNull{ it.data().contains("watch?v=")}
            ?.data()
        val regex = Regex("""watch\?v=(\d+)""")
        var bannerVideoCode = bannerVideoCodeScript?.let { script ->
            regex.find(script)?.groupValues?.get(1)
        }
        // 目前先判断注释里的，以后可能会有变化
        if (bannerVideoCode == null) {
            bannerCSS?.traverse { node, _ ->
                if (node is Comment) {
                    node.data.toVideoCode()?.let {
                        bannerVideoCode = it
                        return@traverse
                    }
                }
            }
        }
        bannerVideoCode.logIfParseNull(Parser::homePageVer2.name, "bannerVideoCode")
        val banner = if (bannerTitle != null && bannerPic != null) {
            HomePage.Banner(
                title = bannerTitle, description = bannerDesc,
                picUrl = bannerPic, videoCode = bannerVideoCode,
            )
        } else null

        // 主页模块
        val latestReleaseClass = homePageParse.getOrNull(0) // 最新上市
        val latestUploadClass = homePageParse.getOrNull(1)  //最新上传
        val ecchiAnimeClass = homePageParse.getOrNull(2)  //里番
        val shortEpisodeAnimeClass = homePageParse.getOrNull(3)  // 泡面番
        val motionAnimeClass = homePageParse.getOrNull(5)  // Motion Anime
        val threeDCGClass = homePageParse.getOrNull(6)  //3DCG
        val twoPointFiveDAnimeClass = homePageParse.getOrNull(7)  // 2.5D
        val twoDAnimeClass = homePageParse.getOrNull(8)  // 2D
        val aiGeneratedClass = homePageParse.getOrNull(10)  // AI生成
        val mmdClass = homePageParse.getOrNull(11)  //  MMD
        val cosplayClass = homePageParse.getOrNull(12)  // Cosplay
        val watchingNowClass = homePageParse.getOrNull(13)  // 他们在看

        val newAnimeTrailerClass = homePageParse.getOrNull(if (isAVSite) 13 else 12)

        val latestReleaseList = latestReleaseClass.extractHanimeInfo()
        val latestHanimeList = mutableListOf<HanimeInfo>()
        if (isAVSite){
            latestHanimeList.addAll(latestUploadClass.extractHanimeInfo())
        } else {
            latestHanimeList.addAll(latestUploadClass.extractHanimeInfo())
        }
        val ecchiAnimeList = ecchiAnimeClass.extractHanimeInfo()
        val shortEpisodeAnimeList = shortEpisodeAnimeClass.extractHanimeInfo()
        val motionAnimeList = motionAnimeClass.extractHanimeInfo()
        val threeDCGList = threeDCGClass.extractHanimeInfo()
        val twoPointFiveDAnimeList = twoPointFiveDAnimeClass.extractHanimeInfo()
        val twoDAnimeList = mutableListOf<HanimeInfo>()
        if (isAVSite){
            twoDAnimeList.addAll(twoDAnimeClass.extractHanimeInfo())
        } else {
            twoDAnimeList.addAll(twoDAnimeClass.extractHanimeInfo())
        }

        val aiGeneratedList = aiGeneratedClass.extractHanimeInfo()
        val mmdList = mmdClass.extractHanimeInfo()
        val cosplayList = cosplayClass.extractHanimeInfo()
        val watchingNowList = watchingNowClass.extractHanimeInfo()

        val newAnimeTrailerList = mutableListOf<HanimeInfo>()
        if (isAVSite){
            newAnimeTrailerList.addAll(newAnimeTrailerClass.extractHanimeInfo())
        } else {
            val newAnimeTrailerItems =
                newAnimeTrailerClass?.select("a")
            newAnimeTrailerItems?.forEach { newAnimeTrailerItem ->
                val videoCode = newAnimeTrailerItem.attr("href").toVideoCode()

                val coverUrl = newAnimeTrailerItem.selectFirst("img")?.attr("src")
                val title = newAnimeTrailerItem.selectFirst("div.home-rows-videos-title")?.text()
                if (title == null || coverUrl == null || videoCode == null) return@forEach
                newAnimeTrailerList.add(
                    HanimeInfo(
                        title = title,
                        coverUrl = coverUrl,
                        videoCode = videoCode,
                        duration = "",
                        currentArtist = null,
                        views = null,
                        uploadTime = null,
                        genre = null,
                        itemType = HanimeInfo.SIMPLIFIED
                    )
                )
            }
        }

        // emit!
        return WebsiteState.Success(
            HomePage(
                csrfToken,
                avatarUrl, username, banner = banner,
                latestHanime = latestHanimeList,
                latestRelease = latestReleaseList,
                ecchiAnime = ecchiAnimeList,
                shortEpisodeAnime = shortEpisodeAnimeList,
                twoPointFiveDAnime = twoPointFiveDAnimeList,
                threeDCG = threeDCGList,
                motionAnime = motionAnimeList,
                twoDAnime = twoDAnimeList,
                aiGenerated = aiGeneratedList,
                mmd = mmdList,
                cosplay = cosplayList,
                watchingNow = watchingNowList,
                newAnimeTrailer = newAnimeTrailerList,
                userId = userId
            )
        )
    }

    private fun isLoginStateExpired(userHomePageLink: String, username: String?): Boolean {
        return userHomePageLink.contains("/login") || username.isNullOrBlank()
    }

    private fun getString(resId: Int) = com.yenaly.yenaly_libs.utils.applicationContext.getString(resId)
    fun Element?.extractHanimeInfo(selector: String = "div[class^=horizontal-card]"): MutableList<HanimeInfo> {
        val resultList = mutableListOf<HanimeInfo>()
        this?.select(selector)?.forEach { item ->
            hanimeNormalItemVer2(item)?.let { hanimeInfo ->
                resultList.add(hanimeInfo)
            }
        }
        return resultList
    }

    fun hanimeSearch(body: String): PageLoadingState<HanimeSearchResult> {
        val parseBody = Jsoup.parse(body).body()
        val maxPage = parseSearchMaxPage(parseBody)
        val allContentsClass =
            parseBody.getElementsByClass("content-padding-new").firstOrNull()
        val allSimplifiedContentsClass =
            parseBody.getElementsByClass("home-rows-videos-wrapper").firstOrNull()

        // emit!
        if (allContentsClass != null) {
            return hanimeSearchNormalVer2(allContentsClass, maxPage)
        } else if (allSimplifiedContentsClass != null) {
            return hanimeSearchSimplified(allSimplifiedContentsClass, maxPage)
        }
        return PageLoadingState.Success(HanimeSearchResult(emptyList(), maxPage))
    }

    /**
     * missav 搜索頁（SSR HTML）解析，路徑形如 /en/search/{query}?page=N。
     *
     * [page] 為當前頁碼，用於判斷是否存在下一頁。
     */
    fun missavSearch(body: String, page: Int): PageLoadingState<HanimeSearchResult> {
        val parseBody = Jsoup.parse(body).body()
        val results = parseMissavCards(parseBody)
        val hasNext = parseBody.select("a[rel=next]").isNotEmpty() ||
                parseBody.select("a[href*=page]").any {
                    it.attr("href").contains("page=${page + 1}")
                }
        val maxPage = if (results.isEmpty()) page else if (hasNext) page + 1 else page
        return PageLoadingState.Success(HanimeSearchResult(results, maxPage))
    }

    /**
     * missav 女優頁（`/actresses/{name}`）的作品列表。
     *
     * 卡片與搜尋頁同構，分頁是 Laravel 的 `?page=N`。頁首的女優頭像也可能帶
     * `alt`，但它不會是影片卡片，故過濾掉含空白的「影片碼」。
     */
    fun missavArtistVideos(body: String, page: Int): PageLoadingState<HanimeSearchResult> {
        val parseBody = Jsoup.parse(body).body()
        val results = parseMissavCards(parseBody)
            .filterNot { it.videoCode.contains(' ') }
            .toMutableList()
        val hasNext = parseBody.select("a[rel=next]").isNotEmpty() ||
                parseBody.select("a[href]").any {
                    it.attr("href").contains("page=${page + 1}")
                }
        val maxPage = if (results.isEmpty()) page else if (hasNext) page + 1 else page
        Log.d("missav_artist", "artist page=$page cards=${results.size} hasNext=$hasNext")
        return PageLoadingState.Success(HanimeSearchResult(results, maxPage))
    }

    /**
     * jable 搜尋頁解析。
     *
     * 第 1 頁是完整 HTML；第 2 頁起是 KVS 的 AJAX block 片段，卡片結構相同。
     * 分頁資訊在 `ul.pagination` 的 `data-parameters="...;from:NN"` 裡。
     */
    fun jableSearch(body: String, page: Int): PageLoadingState<HanimeSearchResult> {
        val parseBody = Jsoup.parse(body).body()
        val results = parseJableCards(parseBody)
        val maxPage = jableMaxPage(parseBody, page)
        return PageLoadingState.Success(HanimeSearchResult(results, maxPage))
    }

    /**
     * jable 模特頁（`/models/{hash}/`）的作品列表。
     *
     * 卡片與搜尋頁同構；分頁沿用 KVS 的 `ul.pagination`。
     * 同時把分頁連結寫進日誌，方便站點改版後確認下一頁的真實 URL 形式。
     */
    fun jableArtistVideos(body: String, page: Int): PageLoadingState<HanimeSearchResult> {
        val parseBody = Jsoup.parse(body).body()
        val results = parseJableCards(parseBody)
        val maxPage = jableMaxPage(parseBody, page)
        val paginationLinks = parseBody.select("ul.pagination a[href]")
            .map { "${it.attr("href")} ${it.attr("data-parameters")}".trim() }
        Log.d(
            "jable_artist",
            "artist page=$page cards=${results.size} maxPage=$maxPage links=$paginationLinks"
        )
        return PageLoadingState.Success(HanimeSearchResult(results, maxPage))
    }

    /**
     * jable 會員中心（`/my/...`）的影片列表：喜歡、稍後觀看、單個片單內容都用它。
     *
     * KVS 的分頁 block 與搜尋頁共用同一套卡片結構；第一頁有時是完整頁面，
     * 第二頁起是 block 片段，兩種都能直接走 [parseJableCards]。
     */
    fun jableUserVideoList(body: String, page: Int): PageLoadingState<MyListItems<HanimeInfo>> {
        val parseBody = Jsoup.parse(body).body()
        // 會員頁可能同時有「推薦」「最近更新」等影片區塊，若整頁解析會把
        // 不相關的卡片混進喜歡/片單列表。優先在 KVS block 容器內找。
        val container = parseBody.selectFirst("#list_videos_my_favourite_videos")
            ?: parseBody.selectFirst("#playlist_view_playlist_view")
            ?: parseBody.selectFirst("#list_videos_common_videos_list")
        // AJAX block 有時只回一段沒有 id 的 HTML 片段；此時整段就是列表。
        // 但完整頁面若找不到 block 容器，寧可回空列表，也不要把整頁的
        // 推薦/最近更新誤當成使用者的收藏。
        val scope = container ?: parseBody.takeIf { !body.contains("<html", ignoreCase = true) }
        var results = scope?.let { parseJableCards(it) } ?: mutableListOf()
        if (results.isEmpty()) {
            results = scope?.let { parseJableGenericVideoCards(it) } ?: mutableListOf()
        }
        val maxPage = jableMaxPage(parseBody, page)
        Log.d("jable_user", "video list page=$page cards=${results.size} maxPage=$maxPage")
        return PageLoadingState.Success(MyListItems(results, maxPage = maxPage))
    }

    /**
     * jable 會員中心的片單資料夾列表（`/my/playlists/`）。
     *
     * 站點的片單路徑有兩種寫法：`/my/playlists/{id}/`（自己的）與
     * `/playlists/{id}/{slug}/`（公開頁）。`listCode` 直接保存完整連結，
     * 之後取片單內容時不必再猜 id。
     */
    fun jablePlaylists(body: String): WebsiteState<Playlists> {
        val parseBody = Jsoup.parse(body).body()
        val lists = mutableListOf<Playlists.Playlist>()
        val seen = mutableSetOf<String>()
        val playlistPath = Regex("""/(?:my/)?playlists/([^/?#]+)""")
        for (a in parseBody.select("a[href]")) {
            val href = a.absUrl("href").ifBlank { a.attr("href") }.trim()
            // 分頁連結（`/my/playlists/2/`）長得和片單連結一樣，必須先排除，
            // 否則第 2 頁會被當成一個叫「2」的片單。
            if (a.closest("ul.pagination") != null) continue
            if (href.contains("from_my_playlists") || href.contains("block_id=")) continue
            if (a.hasAttr("data-action") || href.contains("delete")) continue
            val path = href.substringBefore('?').substringBefore('#')
            val id = playlistPath.find(path)?.groupValues?.get(1) ?: continue
            if (id.isBlank() || id in setOf("create", "delete", "my")) continue
            if (path.trimEnd('/').endsWith("/playlists")) continue
            // 同一張卡片通常有封面與標題兩個連結，按路徑去重。
            val key = path.trimEnd('/')
            if (!seen.add(key)) continue

            val card = a.closest(
                "div.video-img-box, div.item, div.col-6, div.col-sm-4, div.col-lg-3, li, article"
            ) ?: a.parent() ?: a
            val title = card.selectFirst("h6.title a, .title a, h6.title, h5, h4, .title")
                ?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: a.text().trim().takeIf { it.isNotEmpty() && it.length <= 160 }
                ?: id
            if (title.contains("建立", ignoreCase = false) ||
                title.contains("create", ignoreCase = true)
            ) continue
            val img = card.selectFirst("img")
            val coverUrl = img?.let {
                sequenceOf("data-src", "data-original", "src")
                    .map { attr -> it.absUrl(attr) }
                    .firstOrNull { url ->
                        url.isNotBlank() && !url.contains("/assets/images/placeholder")
                    }
            }
            val countText = card.selectFirst(".count, .video-count, .stat-item")?.text()
            val total = countText?.filter(Char::isDigit)?.toIntOrNull()
                ?: card.selectFirst(".label")?.text()
                    ?.takeIf { text ->
                        text.contains("video", ignoreCase = true) ||
                                text.contains("部") || text.contains("影片")
                    }
                    ?.filter(Char::isDigit)?.toIntOrNull()
                ?: -1
            lists += Playlists.Playlist(
                listCode = href,
                title = title,
                total = total,
                coverUrl = coverUrl,
            )
        }
        Log.d(
            "jable_playlists",
            "playlists=${lists.size} hrefs=${lists.map { it.listCode }}"
        )
        return WebsiteState.Success(
            Playlists(playlists = lists, maxPage = jableMaxPage(parseBody, 1))
        )
    }

    /**
     * 保守的 KVS 通用影片卡片解析：只在 [parseJableCards] 一張都沒抓到時兜底。
     * 用 `a[href*=/videos/]` 反查最近的卡片容器，避免會員頁改版後整個列表空白。
     */
    private fun parseJableGenericVideoCards(root: Element): MutableList<HanimeInfo> {
        val results = mutableListOf<HanimeInfo>()
        val seen = mutableSetOf<String>()
        for (a in root.select("a[href]")) {
            val slug = jableVideoSlug(a.attr("href")) ?: continue
            if (!seen.add(slug)) continue
            val card = a.closest(
                "div.video-img-box, div.item, div.col-6, div.col-sm-4, div.col-lg-3, article, li"
            ) ?: a.parent() ?: a
            val img = card.selectFirst("img") ?: a.selectFirst("img") ?: continue
            val coverUrl = sequenceOf("data-src", "data-original", "src")
                .map { attr -> img.absUrl(attr) }
                .firstOrNull { url ->
                    url.isNotBlank() && !url.contains("/assets/images/placeholder")
                }
                ?: continue
            val title = card.selectFirst("h6.title a, .title a, h6, h5, .title")
                ?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: img.attr("alt").trim().takeIf { it.isNotEmpty() }
                ?: slug
            results += HanimeInfo(
                title = title,
                coverUrl = coverUrl,
                videoCode = slug,
                duration = card.selectFirst("span.label")?.text()?.trim()
                    ?.takeIf { it.isNotEmpty() },
                jableVideoId = card.selectFirst("[data-fav-video-id]")
                    ?.attr("data-fav-video-id")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() },
                // jable 封面是 16:9 橫圖，用 NORMAL 讓卡片容器保持 16:9、搜尋頁兩列，
                // 與站點自身一致；SIMPLIFIED 會套 3:4 的直式容器，底部留大片空白。
                itemType = HanimeInfo.NORMAL,
            )
        }
        return results
    }

    /**
     * jable 通用卡片解析。
     *
     * 卡片形如：
     * ```html
     * <div class="video-img-box">
     *   <div class="img-box"><a href="https://jable.tv/videos/{slug}/">
     *     <img data-src=".../320x180/1.jpg"> <span class="label">2:10:08</span>
     *   </a></div>
     *   <div class="detail"><h6 class="title"><a>標題</a></h6><p class="sub-title">觀看數 喜歡數</p></div>
     * </div>
     * ```
     * 部分卡片連結帶 `/s0/` 鏡像前綴，且首頁會混入廣告卡片，因此只認 `.../videos/{slug}/`。
     */
    private fun parseJableCards(root: Element): MutableList<HanimeInfo> {
        val results = mutableListOf<HanimeInfo>()
        val seen = mutableSetOf<String>()
        for (card in root.select("div.video-img-box")) {
            val link = card.selectFirst("div.img-box a[href]")
                ?: card.selectFirst("a[href]")
                ?: continue
            val slug = jableVideoSlug(link.attr("href")) ?: continue
            if (!seen.add(slug)) continue
            val img = card.selectFirst("img")
            val imgCover = img?.let {
                sequenceOf("data-src", "data-original", "src")
                    .map { attr -> it.absUrl(attr) }
                    .firstOrNull { url ->
                        url.isNotBlank() && !url.contains("/assets/images/placeholder")
                    }
            }
            // 「全新上市」輪播的卡片沒有 <img>，封面放在連結的 inline background-image。
            val styleCover = card.selectFirst("div.img-box a[style*=background-image]")
                ?.attr("style")
                ?.let { style ->
                    Regex("""url\(['"]?([^'")]+)['"]?\)""")
                        .find(style)?.groupValues?.get(1)
                }
            val coverUrl = imgCover ?: styleCover?.takeIf { it.isNotBlank() } ?: continue
            val title = card.selectFirst("h6.title a")?.text()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: slug
            val duration = card.selectFirst("span.label")?.text()?.trim()
                ?.takeIf { it.isNotEmpty() }
            // p.sub-title 的 DOM 是「眼睛圖標 + 觀看數 + 心形圖標 + 喜歡數」，
            // 直接 text() 會把兩個數字連在一起，取第一個非空 TextNode 才是觀看數。
            val views = card.selectFirst("p.sub-title")
                ?.childNodes()
                ?.filterIsInstance<TextNode>()
                ?.map { it.text().trim() }
                ?.firstOrNull { it.isNotEmpty() }
            results.add(
                HanimeInfo(
                    title = title,
                    coverUrl = coverUrl,
                    videoCode = slug,
                    duration = duration,
                    views = views,
                    // 收藏列表的卡片帶着站內影片 id，長按移除時直接用它，
                    // 不必再額外抓一次影片頁。
                    jableVideoId = card.selectFirst("[data-fav-video-id]")
                        ?.attr("data-fav-video-id")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() },
                    itemType = HanimeInfo.NORMAL,
                )
            )
        }
        return results
    }

    /**
     * 把 jable 登入表單轉成 [FieldMap]：保留站點現有的 hidden 欄位（可能有 token），
     * 再覆蓋帳號密碼，最後補上 AJAX 表單固定追加的 `format=json&mode=async`。
     */
    fun jableLoginFormFields(
        body: String,
        username: String,
        password: String,
    ): Map<String, String> {
        val form = Jsoup.parse(body).selectFirst("form[action*=login]")
        val fields = LinkedHashMap<String, String>()
        form?.select("input[name]")?.forEach { input ->
            val name = input.attr("name")
            if (name.isNotBlank()) fields[name] = input.attr("value")
        }
        fields["username"] = username
        fields["pass"] = password
        fields["remember_me"] = "1"
        fields["action"] = "login"
        fields["email_link"] = Preferences.baseUrl + "email/"
        fields["format"] = "json"
        fields["mode"] = "async"
        return fields
    }

    /** 從 `https://jable.tv/videos/{slug}/`（或帶 `/s0/` 前綴）取出 slug。 */
    private fun jableVideoSlug(href: String): String? =
        Regex("""/videos/([A-Za-z0-9][A-Za-z0-9_-]*)(?:/|${'$'})""")
            .find(href)
            ?.groupValues
            ?.get(1)
            ?.takeIf { it != "videos" }

    /** KVS 分頁總頁數：`data-parameters` 裡最大的 `from:` 值（頁碼從 1 開始）。 */
    private fun jableMaxPage(root: Element, currentPage: Int): Int {
        val fromMax = root.select("ul.pagination a[data-parameters]")
            .mapNotNull { link ->
                Regex("""from[^:;]*:(\d+)""")
                    .find(link.attr("data-parameters"))
                    ?.groupValues?.get(1)?.toIntOrNull()
            }
            .maxOrNull()
        val hrefMax = root.select("ul.pagination a.page-link[href]")
            .mapNotNull { link ->
                Regex("""/(\d+)${'$'}""")
                    .find(link.attr("href").trimEnd('/'))
                    ?.groupValues?.get(1)?.toIntOrNull()
            }
            .maxOrNull()
        return maxOf(currentPage, fromMax ?: 0, hrefMax ?: 0)
    }

    /**
     * missav 通用卡片解析。
     *
     * 卡片形如 `<a alt="{slug}" href=".../dmXX/en/{slug}"><img/></a>`，slug 即影片碼；
     * 封面取自 img 的 data-src / src，時長取自同一容器內的時間 span，
     * 標題優先取相鄰標題連結的文本，兜底取 img 的 alt。
     */
    private fun parseMissavCards(
        root: Element,
        uncensored: Boolean = false,
    ): MutableList<HanimeInfo> {
        val results = mutableListOf<HanimeInfo>()
        val seen = mutableSetOf<String>()
        for (card in root.select("a[alt]")) {
            val slug = card.attr("alt").trim()
            if (slug.isBlank() || slug == "Search" || !seen.add(slug)) continue
            val img = card.selectFirst("img") ?: continue
            val cover = img.attr("data-src").ifBlank { img.attr("src") }
            if (cover.isBlank()) continue
            val wrapper = card.parent()
            val duration = wrapper?.select("span")?.eachText()
                ?.lastOrNull { it.matches(Regex("""\d+:\d{2}(:\d{2})?""")) }
            val title = wrapper?.nextElementSibling()?.selectFirst("a")?.text()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: img.attr("alt").trim().ifBlank { slug }
            results.add(
                HanimeInfo(
                    title = title, coverUrl = cover, videoCode = slug,
                    // missav 封面約 3:2（`cover-t.jpg`），站點自身用 16:9 容器裁切，
                    // 這裡也走 NORMAL 的 16:9 卡片，避免直式容器底部留白。
                    duration = duration, itemType = HanimeInfo.NORMAL,
                    // 無碼不能只看「是不是在無碼影片這個分節」：FC2 和 *-uncensored-leak
                    // 會出現在最近更新/隨機等分節裏，以前這些卡片不會有無碼角標。
                    isUncensored = uncensored || isMissavUncensoredCode(slug)
                )
            )
        }
        return results
    }

    private fun hanimeNormalItemVer2(hanimeSearchItem: Element): HanimeInfo? {
        val title =
            hanimeSearchItem.selectFirst("div.title, h4.video-title")?.text()?.trim()
                .logIfParseNull(Parser::hanimeNormalItemVer2.name, "title")
        val coverUrl =
            hanimeSearchItem.select("img").getOrNull(0)?.absUrl("src")
                .logIfParseNull(Parser::hanimeNormalItemVer2.name, "coverUrl")
        val videoCode =
            hanimeSearchItem.select("a").getOrNull(0)?.absUrl("href")?.toVideoCode()
                .logIfParseNull(Parser::hanimeNormalItemVer2.name, "videoCode")
        if (title == null || coverUrl == null || videoCode == null) return null
        val durationAndViews = hanimeSearchItem.select("div[class^=thumb-container]")
        val duration = durationAndViews.select("div[class^=duration]").text()
        val views = durationAndViews.select("div[class^=stat-item]").getOrNull(1)?.text()
        val subtitleElement = hanimeSearchItem.selectFirst("div.subtitle, div.video-meta-data")
        val artistAndUploadTime = subtitleElement?.text()?.trim().orEmpty()
        var artist = ""
        var uploadTime = ""
        if (artistAndUploadTime.contains("•")) {
            val parts = artistAndUploadTime.split("•").map { it.trim() }
            artist = parts.getOrNull(0).orEmpty()
            uploadTime = parts.getOrNull(1).orEmpty()
        } else {
            artist = subtitleElement?.selectFirst("a")?.text()?.trim().orEmpty()
            uploadTime = subtitleElement?.selectFirst("span.subtitle-time, span")?.text()?.trim().orEmpty()
        }
        val infoBoxes = hanimeSearchItem.selectFirst(".stats-container .stat-item")
        val reviews = infoBoxes?.ownText()?.trim() ?: ""
        val playlistItemId = hanimeSearchItem.id()
            .takeIf { it.startsWith("playlist-item-") }
            ?.substringAfter("playlist-item-")
        return HanimeInfo(
            title = title,
            coverUrl = coverUrl,
            videoCode = videoCode,
            duration = duration.logIfParseNull(Parser::hanimeNormalItemVer2.name, "duration"),
            currentArtist = artist,
            views = views.logIfParseNull(Parser::hanimeNormalItemVer2.name, "views"),
            uploadTime = uploadTime,
            genre = null,
            playlistItemId = playlistItemId,
            itemType = HanimeInfo.NORMAL,
            reviews = reviews
        )
    }

    // 每一个简化版视频单元
    private fun hanimeSimplifiedItem(hanimeSearchItem: Element): HanimeInfo? {
        val videoCode = hanimeSearchItem.attr("href").toVideoCode()
            .logIfParseNull(Parser::hanimeSimplifiedItem.name, "videoCode")
        val coverUrl = hanimeSearchItem.selectFirst("img")?.attr("src")
            .logIfParseNull(Parser::hanimeSimplifiedItem.name, "coverUrl")
        val title = hanimeSearchItem.selectFirst("div[class=home-rows-videos-title]")?.text()
            .logIfParseNull(Parser::hanimeSimplifiedItem.name, "title")
        if (videoCode == null || coverUrl == null || title == null) return null
        return HanimeInfo(
            title = title,
            coverUrl = coverUrl,
            videoCode = videoCode,
            itemType = HanimeInfo.SIMPLIFIED
        )
    }

    // 出来后是正常视频单元的页面用这个
    private fun hanimeSearchNormalVer2(
        allContentsClass: Element,
        maxPage: Int,
    ): PageLoadingState<HanimeSearchResult> {
        val hanimeSearchList = mutableListOf<HanimeInfo>()
        val hanimeSearchItems =
            allContentsClass.select("div[class^=horizontal-card]")
        if (hanimeSearchItems.isEmpty()) {
            return PageLoadingState.NoMoreData
        } else {
            hanimeSearchItems.forEach { hanimeSearchItem ->
                hanimeNormalItemVer2(hanimeSearchItem)?.let(hanimeSearchList::add)
            }
        }
        Log.d("search_result", "$hanimeSearchList")
        return PageLoadingState.Success(HanimeSearchResult(hanimeSearchList, maxPage))
    }

    // 出来后是简化版视频单元的页面用这个
    private fun hanimeSearchSimplified(
        allSimplifiedContentsClass: Element,
        maxPage: Int,
    ): PageLoadingState<HanimeSearchResult> {
        val hanimeSearchList = mutableListOf<HanimeInfo>()
        val hanimeSearchItems = allSimplifiedContentsClass.children()
        if (hanimeSearchItems.isEmpty()) {
            return PageLoadingState.NoMoreData
        } else hanimeSearchItems.forEach { hanimeSearchItem ->
            hanimeSimplifiedItem(hanimeSearchItem)?.let(hanimeSearchList::add)
        }
        return PageLoadingState.Success(HanimeSearchResult(hanimeSearchList, maxPage))
    }

    /**
     * AV 站的 CDN 節點可能已失效，這裡統一修正成可用的節點；其餘站點原樣返回。
     */
    private fun fixAvCdnHost(url: String): String {
        if (Preferences.baseUrl != HANIME_URL[3]) return url
        return Regex.avCdnHost.replace(url) { "${it.groupValues[1]}t33.cdn2020.com" }
    }

    fun hanimeVideoVer2(body: String): VideoLoadingState<HanimeVideo> {
        val parseBody = Jsoup.parse(body).body()
        val csrfToken = parseBody.selectFirst("input[name=_token]")?.attr("value") // csrf token

        val currentUserId =
            parseBody.selectFirst("input[name=like-user-id]")?.attr("value") // current user id

        val title = parseBody.getElementById("shareBtn-title")?.text()
            .throwIfParseNull(Parser::hanimeVideoVer2.name, "title")

        var likeStatus = parseBody.selectFirst("[name=like-status]")
            ?.attr("value")
        Log.i("likeStatus", likeStatus.toString())
        if (!likeStatus.isNullOrEmpty()) {
            likeStatus = "1"
        }
        var unlikeStatus = parseBody.selectFirst("[name=unlike-status]")
            ?.attr("value")
        if (!unlikeStatus.isNullOrEmpty()) {
            unlikeStatus = "1"
        }
        val likesCount = parseBody.selectFirst("input[name=likes-count]")
            ?.attr("value")?.toIntOrNull()
        val unlikesCount = parseBody.selectFirst("input[name=unlikes-count]")
            ?.attr("value")?.toIntOrNull()
        val videoDetailWrapper = parseBody.selectFirst("div[class=video-details-wrapper]")
        val videoCaptionText = videoDetailWrapper?.selectFirst("div[class^=video-caption-text]")
        val chineseTitle = videoCaptionText?.previousElementSibling()?.ownText()
        val introduction = videoCaptionText?.ownText()
        val uploadTimeWithViews = videoDetailWrapper?.selectFirst("div > div > div")?.text()
        val uploadTimeWithViewsGroups = uploadTimeWithViews?.let {
            Regex.viewAndUploadTime.find(it)?.groups
        }
        val uploadTime = uploadTimeWithViewsGroups?.get(3)?.value?.let { time ->
            runCatching {
                LocalDate.parse(time, LOCAL_DATE_FORMAT)
            }.getOrNull()
        }

        val views = uploadTimeWithViewsGroups?.get(2)?.value

        val tags = parseBody.getElementsByClass("single-video-tag")
        val tagListWithLikeNum = mutableListOf<String>()
        tags.forEach { tag ->
            val child = tag.childOrNull(0)
            if (child != null && child.hasAttr("href")) {
                tagListWithLikeNum.add(child.text())
            }
        }
        val tagList = tagListWithLikeNum.map {
            it.substringBefore(" (")
                .removePrefix("#")
                .trim()
        }
        val myListCheckboxWrapper = parseBody.select("div[class~=playlist-checkbox-wrapper]")
        val myListInfo = mutableListOf<HanimeVideo.MyList.MyListInfo>()
        myListCheckboxWrapper.forEach {
            val listTitle = it.selectFirst("span")?.ownText()
                .logIfParseNull(Parser::hanimeVideoVer2.name, "myListTitle", loginNeeded = true)
            val listInput = it.selectFirst("input")
            val listCode = listInput?.attr("id")
                .logIfParseNull(Parser::hanimeVideoVer2.name, "myListCode", loginNeeded = true)
            val isSelected = listInput?.hasAttr("checked") == true
            if (listTitle != null && listCode != null) {
                myListInfo += HanimeVideo.MyList.MyListInfo(
                    code = listCode, title = listTitle, isSelected = isSelected
                )
            }
        }
        val isWatchLater = parseBody.getElementById("playlist-save-checkbox")
            ?.selectFirst("input")?.hasAttr("checked") == true
        val myList = HanimeVideo.MyList(isWatchLater = isWatchLater, myListInfo = myListInfo)

        val playlistWrapper = parseBody.selectFirst("div.video-playlist-wrapper")
            ?: parseBody.selectFirst("div[id=video-playlist-wrapper]")
        val playlist = playlistWrapper?.let {
            val playlistVideoList = mutableListOf<HanimeInfo>()
            val playlistScroll = it.getElementById("playlist-scroll")
            if (playlistScroll != null) {
                val children = playlistScroll.children()
                if (children.firstOrNull()?.hasClass("playlist-hover-wrap") == true) {
                    // 新版页面结构
                    val playlistName = it.selectFirst("#playlist-top-block h4 a")?.text()
                    children.forEach { child ->
                        val dataHref = child.attr("data-href")
                        val videoCode = dataHref.toVideoCode()
                            .throwIfParseNull(Parser::hanimeVideoVer2.name, "videoCode")
                        val thumbContainer = child.selectFirst(".thumb-container")
                        val coverUrl = thumbContainer?.selectFirst("img.main-thumb")?.absUrl("src")
                            .throwIfParseNull(Parser::hanimeVideoVer2.name, "playlistEachCoverUrl")
                        val title = child.selectFirst("h4.video-title a")?.text()
                            .throwIfParseNull(Parser::hanimeVideoVer2.name, "playlistEachTitle")
                        val duration = thumbContainer?.selectFirst(".duration")?.text()
                        val statItems = thumbContainer?.select(".stat-item")
                        val reviews = statItems?.firstOrNull()?.ownText()?.trim()
                        val views = statItems?.getOrNull(1)?.text()
                        val isPlaying = child.hasClass("videos-scroll")
                        val artist = child.selectFirst(".meta-author a")?.text()
                        val genre = child.selectFirst(".meta-stats a")?.text()
                        val uploadTime = child.selectFirst(".meta-stats span")?.text()
                        playlistVideoList.add(
                            HanimeInfo(
                                title = title, coverUrl = coverUrl,
                                videoCode = videoCode,
                                duration = duration.logIfParseNull(
                                    Parser::hanimeVideoVer2.name,
                                    "$title duration"
                                ),
                                views = views.logIfParseNull(
                                    Parser::hanimeVideoVer2.name,
                                    "$title views"
                                ),
                                isPlaying = isPlaying,
                                itemType = HanimeInfo.NORMAL,
                                currentArtist = artist,
                                reviews = reviews,
                                genre = genre,
                                uploadTime = uploadTime
                            )
                        )
                    }
                    HanimeVideo.Playlist(playlistName = playlistName, video = playlistVideoList)
                } else {
                    // 旧版页面结构（兼容兜底）
                    val playlistName = it.selectFirst("div > div > h4")?.text()
                    children.forEach { parent ->
                        if (parent.tagName() == "a") {
                            return@forEach
                        }
                        val videoCode = parent.selectFirst("div > a")?.absUrl("href")?.toVideoCode()
                            .throwIfParseNull(Parser::hanimeVideoVer2.name, "videoCode")
                        val cardMobilePanel = parent.selectFirst("div[class^=card-mobile-panel]")
                        val eachTitleCover = cardMobilePanel?.select("div > div > div > img")?.getOrNull(1)
                        val eachIsPlaying = cardMobilePanel?.select("div > div > div > div")
                            ?.firstOrNull()
                            ?.text()
                            ?.contains("播放") == true
                        val cardMobileDuration = cardMobilePanel?.select("div[class*=card-mobile-duration]")
                        val eachDuration = cardMobileDuration?.firstOrNull()?.text()
                        val eachViews = cardMobileDuration?.getOrNull(2)?.text()
                        val playlistEachCoverUrl = eachTitleCover?.absUrl("src")
                            .throwIfParseNull(Parser::hanimeVideoVer2.name, "playlistEachCoverUrl")
                        val playlistEachTitle = eachTitleCover?.attr("alt")
                            .throwIfParseNull(Parser::hanimeVideoVer2.name, "playlistEachTitle")
                        val artist = cardMobilePanel?.selectFirst("a.card-mobile-user")?.text()
                        val infoBoxes = cardMobilePanel?.select("div.card-mobile-duration.card-playlist-large")
                        val reviews = infoBoxes?.firstOrNull()?.ownText()?.trim()
                        playlistVideoList.add(
                            HanimeInfo(
                                title = playlistEachTitle, coverUrl = playlistEachCoverUrl,
                                videoCode = videoCode,
                                duration = eachDuration.logIfParseNull(
                                    Parser::hanimeVideoVer2.name,
                                    "$playlistEachTitle duration"
                                ),
                                views = eachViews.logIfParseNull(
                                    Parser::hanimeVideoVer2.name,
                                    "$playlistEachTitle views"
                                ),
                                isPlaying = eachIsPlaying,
                                itemType = HanimeInfo.NORMAL,
                                currentArtist = artist,
                                reviews = reviews
                            )
                        )
                    }
                    HanimeVideo.Playlist(playlistName = playlistName, video = playlistVideoList)
                }
            } else {
                null
            }
        }

        val relatedAnimeList = mutableListOf<HanimeInfo>()
        val relatedTabContent = parseBody.getElementById("related-tabcontent")

        relatedTabContent?.also {
            val children: List<Element> = it.childOrNull(0)?.children() ?: emptyList()
            val isSimplified =
                children.getOrNull(0)?.select("a")?.getOrNull(0)
                    ?.getElementsByClass("home-rows-videos-div")
                    ?.firstOrNull() != null
            if (isSimplified) {
                for (each in children) {
                    val eachContent = each.selectFirst("a") ?: continue
                    val homeRowsVideosDiv =
                        eachContent.getElementsByClass("home-rows-videos-div").firstOrNull()
                            ?: continue
                    val eachVideoCode = eachContent.absUrl("href").toVideoCode() ?: continue
                    val eachCoverUrl = homeRowsVideosDiv.selectFirst("img")
                        ?.let { img ->
                            sequenceOf("data-src", "data-original", "src")
                                .map { attr -> img.absUrl(attr).ifBlank { img.attr(attr) } }
                                .firstOrNull { it.isNotBlank() }
                        }
                        ?: continue
                    // 新版標題 div 可能還帶別的 class（例如 `... title fs-14`），舊的
                    // `div[class$=title]` 匹配不到就拋 ParseException，會讓整部影片
                    // 解析失敗並被誤導去瀏覽器。這裡放寬選擇器，壞卡片直接跳過。
                    val eachTitle = homeRowsVideosDiv.selectFirst("[class*=title]")
                        ?.text()?.trim()?.takeIf { it.isNotEmpty() }
                        ?: homeRowsVideosDiv.selectFirst("img")?.attr("alt")?.trim()
                            ?.takeIf { it.isNotEmpty() }
                        ?: continue
                    relatedAnimeList.add(
                        HanimeInfo(
                            title = eachTitle, coverUrl = eachCoverUrl,
                            videoCode = eachVideoCode,
                            itemType = HanimeInfo.SIMPLIFIED
                        )
                    )
                }
            } else {
                relatedAnimeList.addAll(relatedTabContent.extractHanimeInfo())
//                children?.forEachStep2 { each ->
//                    Log.i("children",each.toString())
//                    relatedAnimeList.addAll(each.extractHanimeInfo())
////                    val item = each.select("div[class^=video-item-container]")[0]
////                    hanimeNormalItemVer2(item)?.let(relatedAnimeList::add)
//                }
            }
        }
        Log.d("related_anime_list", relatedAnimeList.toString())

        val hanimeResolution = HanimeResolution()
        val videoClass = parseBody.selectFirst("video[id=player]")
        val videoCoverUrl = videoClass?.absUrl("poster").orEmpty()
        val videos = videoClass?.children()
        if (!videos.isNullOrEmpty()) {
            videos.forEach { source ->
                val resolution = source.attr("size") + "P"
                val sourceUrl = fixAvCdnHost(source.absUrl("src"))
                val videoType = source.attr("type")
                hanimeResolution.parseResolution(resolution, sourceUrl, videoType)
            }
        } else {
            val playerDivWrapper = parseBody.selectFirst("div[id=player-div-wrapper]")
            playerDivWrapper?.select("script")?.let { scripts ->
                for (script in scripts) {
                    val data = script.data()
                    if (data.isBlank()) continue
                    val result =
                        Regex.videoSource.find(data)?.groups?.get(1)?.value ?: continue
                    hanimeResolution.parseResolution(null, fixAvCdnHost(result))
                    break
                }
            }
        }

        val artistAvatarUrl = parseBody
            .select("div.video-details-wrapper > div > a > div > img[style*='position: absolute'][style*='border-radius: 50%']")
            .attr("src")
        val artistNameCSS = parseBody.getElementById("video-artist-name")
        val artistGenre = artistNameCSS?.nextElementSibling()?.text()?.trim()
        val artistName = artistNameCSS?.text()?.trim()
        val postCSS = parseBody.getElementById("video-subscribe-form")
        val post = postCSS?.let {
            val userId = it.selectFirst("input[name=subscribe-user-id]")?.attr("value")
            val artistId = it.selectFirst("input[name=subscribe-artist-id]")?.attr("value")
            val isSubscribed = it.selectFirst("input[name=subscribe-status]")?.attr("value")
            if (userId != null && artistId != null && isSubscribed != null) {
                HanimeVideo.Artist.POST(
                    userId = userId,
                    artistId = artistId,
                    isSubscribed = isSubscribed == "1"
                )
            } else null
        }
        val artist = if (artistName != null && artistGenre != null) {
            HanimeVideo.Artist(
                name = artistName,
                avatarUrl = artistAvatarUrl,
                genre = artistGenre,
                post = post,
            )
        } else null
        val originalComic = parseBody.selectFirst("a.video-comic-btn")?.attr("href")

        return VideoLoadingState.Success(
            HanimeVideo(
                title = title, coverUrl = videoCoverUrl,
                chineseTitle = chineseTitle.logIfParseNull(
                    Parser::hanimeVideoVer2.name,
                    "chineseTitle"
                ),
                uploadTime = uploadTime.logIfParseNull(Parser::hanimeVideoVer2.name, "uploadTime"),
                views = views.logIfParseNull(Parser::hanimeVideoVer2.name, "views"),
                introduction = introduction.logIfParseNull(
                    Parser::hanimeVideoVer2.name,
                    "introduction"
                ),
                videoUrls = hanimeResolution.toResolutionLinkMap(),
                tags = tagList,
                myList = myList,
                playlist = playlist,
                relatedHanimes = relatedAnimeList,
                artist = artist.logIfParseNull(Parser::hanimeVideoVer2.name, "artist"),
                favTimes = likesCount,
                isFav = likeStatus == "1",
                unlikesCount = unlikesCount,
                isUnlike = unlikeStatus == "1",
                csrfToken = csrfToken,
                currentUserId = currentUserId,
                originalComic = originalComic
            )
        )
    }

    /**
     * missav 影片頁解析。
     *
     * 元數據取自 og: meta 標籤；影片源在 eval-packed 的播放器腳本內，解包後得到
     * surrit.com 各分辨率 m3u8；相關影片複用通用卡片解析。
     */
    fun missavVideo(body: String): VideoLoadingState<HanimeVideo> {
        val doc = Jsoup.parse(body)
        fun ogMeta(property: String) =
            doc.selectFirst("meta[property=\"$property\"]")?.attr("content")?.trim()
                ?.takeIf { it.isNotEmpty() }

        val title = ogMeta("og:title")
            ?: throw ParseException("missavVideo: og:title not found")
        val coverUrl = ogMeta("og:image").orEmpty()
        val introduction = ogMeta("og:description")
        val uploadTime = ogMeta("og:video:release_date")?.let { time ->
            runCatching { LocalDate.parse(time, LOCAL_DATE_FORMAT) }.getOrNull()
        }
        val artistName = ogMeta("og:video:actor")
        val directorName = ogMeta("og:video:director")

        val hanimeResolution = HanimeResolution()
        var masterUrl: String? = null
        val packedScript = doc.select("script")
            .map { it.data() }
            .firstOrNull { it.contains("eval(function(p,a,c,k,e,d)") }
        val unpacked = packedScript?.let(::unpackDeanEdwardsScript)
        if (unpacked != null) {
            Regex("""(\w*)='(https?://surrit\.[^']+)'\s*;""").findAll(unpacked).forEach { m ->
                val name = m.groupValues[1]
                val url = m.groupValues[2]
                val height = Regex("""x(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull()
                when (height) {
                    1080 -> hanimeResolution.parseResolution(HanimeResolution.RES_1080P, url)
                    720 -> hanimeResolution.parseResolution(HanimeResolution.RES_720P, url)
                    480 -> hanimeResolution.parseResolution(HanimeResolution.RES_480P, url)
                    240 -> hanimeResolution.parseResolution(HanimeResolution.RES_240P, url)
                    else -> if (name == "source") masterUrl = url
                }
            }
        }
        // 兜底：從 seek 縮略圖中提取 UUID，拼接主播放列表
        if (unpacked == null) {
            Regex.missavSurritId.find(body)?.let {
                masterUrl = "https://surrit.com/${it.groupValues[1]}/playlist.m3u8"
            }
        }
        masterUrl?.let { hanimeResolution.parseResolution(null, it) }

        val relatedAnimeList = parseMissavCards(doc.body())

        // 詳情頁信息區：`div.space-y-2` 內每行是一個 div.text-secondary，
        // 標籤/女優/片商都是站內鏈接；用帶 <time datetime> 的那一塊定位，避免依賴語言文案。
        val infoLinks = doc.select("div.space-y-2")
            .firstOrNull { it.select("time[datetime]").isNotEmpty() }
            ?.select("a[href]")
            .orEmpty()
        val tags = infoLinks
            .filter { link ->
                val href = link.attr("href")
                "/genres/" in href || "/uncensored-leak" in href
            }
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
        val actressLink = infoLinks.firstOrNull { "/actresses/" in it.attr("href") }
        val actressName = actressLink
            ?.text()?.trim()?.takeIf { it.isNotEmpty() }
        // 直接保存女優主頁連結：名字有日文/英文兩種寫法時，用名字搜索會漏掉其他作品，
        // 用站內的 /actresses/{name} 頁面則能拿到完整列表。
        val actressProfileUrl = actressLink
            ?.absUrl("href")
            ?.takeIf { it.isNotBlank() }

        // 站內物品 id：影片頁的喜歡/片單組件寫死 `api/items/{itemId}/save`
        val missavItemId = Regex("""api/items/([A-Za-z0-9]+)/save""")
            .find(body)?.groupValues?.get(1)
        // Blade 渲染的初始喜歡狀態（未登入恆為 false，登入後由 /view 接口校正）
        val saved = Regex("""saved:\s*(true|false)""")
            .find(body)?.groupValues?.get(1)?.toBoolean() ?: false

        return VideoLoadingState.Success(
            HanimeVideo(
                title = title, coverUrl = coverUrl,
                chineseTitle = title,
                uploadTime = uploadTime,
                introduction = introduction,
                videoUrls = hanimeResolution.toResolutionLinkMap(),
                tags = tags,
                relatedHanimes = relatedAnimeList,
                isFav = saved,
                missavItemId = missavItemId,
                artist = (actressName ?: artistName)?.let {
                    HanimeVideo.Artist(
                        name = it,
                        avatarUrl = "",
                        genre = directorName.orEmpty(),
                        profileUrl = actressProfileUrl,
                    )
                },
            )
        )
    }

    /**
     * jable 影片頁解析。
     *
     * 元數據在 `og:` meta 與 `.video-info` 區塊；播放地址是內聯腳本裡的
     * `var hlsUrl = '...'`（通常是帶簽名的 m3u8），只對應一個「Unknown」畫質。
     * 收藏按鈕的初始狀態在 `button.fav` 的 `active` class，站內 id 在
     * `pageContext.videoId`。
     */
    fun jableVideo(body: String): VideoLoadingState<HanimeVideo> {
        val doc = Jsoup.parse(body)
        fun ogMeta(property: String) =
            doc.selectFirst("meta[property=\"$property\"]")?.attr("content")?.trim()
                ?.takeIf { it.isNotEmpty() }

        val title = ogMeta("og:title")
            ?: doc.selectFirst(".info-header .header-left h4")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw ParseException("jableVideo: title not found")
        val coverUrl = ogMeta("og:image").orEmpty()

        val rawHlsUrl = Regex("""var\s+hlsUrl\s*=\s*['"]([^'"]+)['"]""")
            .find(body)?.groupValues?.get(1)
            ?: Regex("""(?:source|src)\s*:\s*['"]([^'"]+\.m3u8[^'"]*)['"]""")
                .find(body)?.groupValues?.get(1)
        val hlsUrl = rawHlsUrl?.replace("\\/", "/")?.let { url ->
            when {
                url.startsWith("//") -> "https:$url"
                url.startsWith("/") ->
                    Preferences.baseUrl.trimEnd('/') + url
                else -> url
            }
        }
        val resolution = HanimeResolution()
        hlsUrl?.let { resolution.parseResolution(null, it) }

        val uploadTime = doc.selectFirst(".header-right .inactive-color")?.text()
            ?.let { Regex("""(\d{4}-\d{2}-\d{2})""").find(it)?.groupValues?.get(1) }
            ?.let { runCatching { LocalDate.parse(it, LOCAL_DATE_FORMAT) }.getOrNull() }
        val metaSpans = doc.select(".info-header .header-left h6 span.mr-3")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
        // 第一項是相對時間（「4 年前」），第二項才是觀看數；若站點調整結構則退回第一個含數字的。
        val views = metaSpans.getOrNull(1)
            ?: metaSpans.firstOrNull { span ->
                span.any(Char::isDigit) && !span.contains("前") && !span.contains("ago")
            }
        val tags = doc.select("h5.tags a")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
        val artist = doc.select("div.models a.model")
            .mapNotNull { model ->
                val name = model.selectFirst("span[title]")?.attr("title")?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: model.text().trim().takeIf { it.isNotEmpty() }
                // 模特頭像旁邊的頭像連到站內模特頁；保留連結，點擊時直接列出她的作品。
                val profileUrl = model.absUrl("href").ifBlank { model.attr("href") }
                    .trim().takeIf { it.isNotEmpty() }
                name?.let {
                    HanimeVideo.Artist(
                        name = it,
                        avatarUrl = "",
                        genre = "",
                        profileUrl = profileUrl,
                    )
                }
            }
            .distinctBy { it.name }
            .firstOrNull()

        val favButton = doc.selectFirst("button.fav")
        // 收藏接口的 video_id 是站點資料庫 id。登入後主按鈕會帶 data-fav-video-id；
        // 匿名頁只有 pageContext.videoId。不能直接取全頁第一個 [data-fav-video-id]——
        // 那通常是「猜你喜歡」第一張卡片的 id，會收藏到別的影片。
        val videoId = favButton?.attr("data-fav-video-id")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("section.video-info [data-fav-video-id]")
                ?.attr("data-fav-video-id")?.takeIf { it.isNotBlank() }
            ?: Regex("""videoId\s*:\s*['"]([^'"]+)['"]""")
                .find(body)?.groupValues?.get(1)
            ?: doc.selectFirst("[data-fav-video-id]")?.attr("data-fav-video-id")
        val favTimes = favButton?.selectFirst(".count")?.text()
            ?.filter(Char::isDigit)
            ?.toIntOrNull()
        val isFav = favButton?.hasClass("active") == true
        // 「稍後觀看」是同一排按鈕裏的書籤鍵（SVG `#icon-bookmark-inline`）。
        // 登入後才會帶 data-fav-type=1；未登入頁只有指向 login-required 的錨點。
        val watchLaterButton = doc.select("button.btn-action").firstOrNull { button ->
            button.attr("data-fav-type") == "1" ||
                    button.outerHtml().contains("icon-bookmark")
        }
        val isWatchLater = watchLaterButton?.hasClass("active") == true

        return VideoLoadingState.Success(
            HanimeVideo(
                title = title,
                coverUrl = coverUrl,
                chineseTitle = title,
                introduction = ogMeta("og:description"),
                uploadTime = uploadTime,
                views = views,
                videoUrls = resolution.toResolutionLinkMap(),
                tags = tags,
                relatedHanimes = parseJableCards(doc.body()),
                artist = artist,
                favTimes = favTimes,
                isFav = isFav,
                jableVideoId = videoId,
                isWatchLater = isWatchLater,
            )
        )
    }

    /**
     * jable 影片頁留言（KVS `video_comments_video_comments` block）。
     *
     * 匿名頁與 AJAX 分頁片段的結構一致：
     * `#video_comments_video_comments_items .item[data-comment-id]`，
     * 每條留言是 `img.avatar` + `.title`（會員連結、相對時間）+ `.comment-text`。
     * 原站留言沒有讚/踩與回覆，這裡只映射可展示的欄位。
     */
    fun jableComments(body: String): WebsiteState<VideoComments> {
        val doc = Jsoup.parse(body)
        val scope = doc.selectFirst("#video_comments_video_comments_items")
            ?: doc.selectFirst(".comment-list")
            ?: doc.body()
        val comments = scope.select(".item[data-comment-id]").mapNotNull { item ->
            val id = item.attr("data-comment-id").trim()
                .takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val avatar = item.selectFirst("img.avatar")?.let { img ->
                img.attr("src").ifBlank { img.attr("data-src") }
                    .let { if (it.startsWith("//")) "https:$it" else it }
            }.orEmpty()
            val username = item.selectFirst(".title a[href*='/members/']")?.text()?.trim()
                ?: item.selectFirst(".title a")?.text()?.trim().orEmpty()
            val date = item.selectFirst(".title .inactive-color")?.text()?.trim().orEmpty()
            val content = item.selectFirst(".comment-text")?.let { text ->
                // 表情是 `<img alt=":xx:">`，直接去掉，避免把短碼當正文顯示。
                text.select("img").remove()
                text.text().trim()
            }.orEmpty()
            VideoComments.VideoComment(
                avatar = avatar,
                username = username,
                date = date,
                content = content,
                thumbUp = null,
                isChildComment = false,
                hasMoreReplies = false,
                replyCount = 0,
                id = id,
                post = VideoComments.VideoComment.POST(foreignId = id),
            )
        }
        val total = doc.selectFirst(".comments .sub-title")?.text()
            ?.filter(Char::isDigit)
            ?.toIntOrNull()
        Log.d("jable_comments", "comments=${comments.size} total=$total")
        return WebsiteState.Success(VideoComments(comments.toMutableList()))
    }

    /**
     * missav 影片所屬的用戶片單：`{data: [{key, name, is_added}]}`。
     *
     * 復用 [HanimeVideo.MyList]（code 即片單 key，isSelected 即 is_added），
     * 這樣影片頁的「加入清單」彈窗無需為 missav 另寫一套 UI。
     */
    /**
     * missav 使用者頁（`/cn/saved`、`/cn/history`、`/cn/playlists/{key}`）的影片列表。
     * 這些頁面是 Laravel 直出的 SSR，卡片結構與搜尋頁相同，直接複用 [parseMissavCards]。
     */
    fun missavUserVideoList(body: String, page: Int): PageLoadingState<MyListItems<HanimeInfo>> {
        val parseBody = Jsoup.parse(body).body()
        val results = parseMissavCards(parseBody)
        val hasNext = parseBody.select("a[rel=next]").isNotEmpty() ||
                parseBody.select("a[href]").any { it.attr("href").contains("page=${page + 1}") }
        val maxPage = when {
            results.isEmpty() -> page
            hasNext -> page + 1
            else -> page
        }
        Log.d("missav_user", "video list page=$page cards=${results.size} hasNext=$hasNext")
        return PageLoadingState.Success(MyListItems(results, maxPage = maxPage))
    }

    /**
     * missav 我的片單資料夾列表（`/cn/playlists`）。
     *
     * 站點用 `/cn/playlists/{key}` 進入單個片單，這裡只抓「資料夾」層。
     * 選擇器刻意只用 `a[href]` + Kotlin 端過濾：複雜的 `[href*=/xxx/]` 選擇器
     * 曾讓 Jsoup 直接拋異常整頁崩掉。
     */
    fun missavPlaylistFolders(body: String): WebsiteState<Playlists> {
        val parseBody = Jsoup.parse(body).body()
        val lists = mutableListOf<Playlists.Playlist>()
        val seen = mutableSetOf<String>()
        // 片單列表是 `<ul role="list"><li><a href="/cn/playlists/{key}">`。
        // 同頁還有三個坑：
        //  1. 「建立片單」指向 `/playlists/create`，會被當成一個片單；
        //  2. 分頁/麵包屑是 `?page=N` 連結；
        //  3. 第 2 頁以後（超出實際片單數時）只會剩「建立片單」。
        // 以前三者都沒排除，於是每頁都解析出 1 個「片單」→ 列表永遠不為空 →
        // ViewModel 的 noMore 永不成立 → 一直翻到 page=30 還在 fetch。
        for (a in parseBody.select("li a[href]")) {
            val href = a.attr("href")
            if (href.contains("page=")) continue
            val idx = href.indexOf("/playlists/")
            if (idx < 0) continue
            val key = href.substring(idx + "/playlists/".length)
                .substringBefore('?').trim('/')
            if (key.isBlank() || key == "create" || !seen.add(key)) continue
            // 標題是卡片裏的第一個 <p>（class 含 truncate）；用 a.text() 會把
            // 「私人 / 擁有者 / 最后更新于 …」整串吞進來。
            val title = a.selectFirst("p.truncate")?.text()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: a.selectFirst("p")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: key
            // missav 的片單列表頁不提供封面，也沒有影片數量（站點本身就只列名稱/擁有者/更新時間）。
            // total = -1 / coverUrl = null 由 UI 顯示中性佔位，不再顯示「-1 部」和載入失敗圖。
            lists += Playlists.Playlist(
                listCode = key,
                title = title,
                total = -1,
                coverUrl = null,
            )
        }
        val maxPage = parseBody.select("a[href]")
            .mapNotNull {
                Regex("""[?&]page=(\d+)""").find(it.attr("href"))
                    ?.groupValues?.get(1)?.toIntOrNull()
            }
            .maxOrNull() ?: 1
        Log.d("missav_user", "playlist folders=${lists.size} maxPage=$maxPage")
        return WebsiteState.Success(
            Playlists(playlists = lists, csrfToken = null, maxPage = maxPage)
        )
    }

    fun missavPlaylists(body: String): HanimeVideo.MyList? {
        val data = runCatching { org.json.JSONObject(body).optJSONArray("data") }
            .getOrNull() ?: return null
        val infos = buildList {
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val key = item.optString("key").takeIf { it.isNotEmpty() } ?: continue
                add(
                    HanimeVideo.MyList.MyListInfo(
                        code = key,
                        title = item.optString("name"),
                        isSelected = item.optBoolean("is_added", false),
                    )
                )
            }
        }
        return HanimeVideo.MyList(isWatchLater = false, myListInfo = infos)
    }

    /**
     * 解包 Dean Edwards packer（`eval(function(p,a,c,k,e,d){...})`），
     * missav 播放器用其混淆 m3u8 地址。
     */
    private fun unpackDeanEdwardsScript(script: String): String? {
        val match = Regex(
            """\}\('(.*)',\s*(\d+),\s*(\d+),\s*'([^']*)'\.split\('\|'\)""",
            RegexOption.DOT_MATCHES_ALL
        ).find(script) ?: return null
        val radix = match.groupValues[2].toIntOrNull() ?: return null
        val count = match.groupValues[3].toIntOrNull() ?: return null
        val keywords = match.groupValues[4].split('|')
        val payload = match.groupValues[1]
            .replace("\\'", "'")
            .replace("\\\"", "\"")
        fun encodeIndex(index: Int): String {
            var current = index
            val prefix = if (current < radix) "" else encodeIndex(current / radix)
            current %= radix
            val char = if (current > 35) (current + 29).toChar()
            else "0123456789abcdefghijklmnopqrstuvwxyz"[current]
            return prefix + char
        }
        var unpacked = payload
        for (i in count - 1 downTo 0) {
            val keyword = keywords.getOrNull(i) ?: continue
            // 用 lambda 替換以避免轉義問題；注意此處不能寫 Regex.escapeReplacement——
            // 會被 Parser.Regex 嵌套對象遮蔽而解析失敗
            unpacked = unpacked.replace(Regex("""\b${encodeIndex(i)}\b""")) { keyword }
        }
        return unpacked
    }

    /**
     * missav 首頁解析：按分節容器（`div.sm:container`，內含 h2 標題 + 卡片 grid）提取卡片。
     *
     * 靜態 HTML 只含服務端渲染的分節：新作上市→latestHanime、最近更新→latestRelease、
     * 無碼影片等其餘分節按頁面順序存入 [HomePage.missavSections]。
     * 「推薦給你」等 Recombee 分節為客戶端渲染，靜態 HTML 中只有骨架，解析結果為空會被跳過。
     * 區塊標題含「無碼/uncensored」時，區塊內卡片標記 [HanimeInfo.isUncensored]。
     */
    fun missavHomePage(body: String): WebsiteState<HomePage> {
        val parseBody = Jsoup.parse(body).body()
        // 已登入時，頁面內聯的 Alpine 根狀態長這樣：`user: { name: "..." }`（未登入是 `user: null`）。
        // 站點本身可能在 user.name 為 null 時顯示別的名字，所以兩邊都兜。
        // 注意 `}` 在 Java 正則裡必須轉義，否則 PatternSyntaxException: Illegal repetition（src 17），
        // 整個首頁解析會直接失敗 → 使用者看到「頁面載入失敗」。
        val missavUser = Regex("""user:\s*\{[^{}]*\}""").find(body)?.value
        val missavUserName = missavUser
            ?.let { Regex("""["']?name["']?\s*:\s*["']([^"']+)["']""").find(it)?.groupValues?.get(1) }
            ?.trim()?.takeIf { it.isNotEmpty() }
        var latestHanime: MutableList<HanimeInfo> = mutableListOf()
        var latestRelease: MutableList<HanimeInfo> = mutableListOf()
        val extraSections = mutableListOf<HomePage.MissavSection>()
        for (heading in parseBody.select("h2")) {
            val sectionTitle = heading.text().trim()
            if (sectionTitle.isEmpty()) continue
            // 分節容器：h2 所在標題欄的祖先，卡片的 grid div 是它的直接子節點。
            // 只認「含影片卡（a[alt] 內有 video）」且「只含一個 h2」的祖先，
            // 避免爬到頁面外層把其他分節的卡片吞進來（頁尾/登入框就是這樣被誤判的）。
            val container = generateSequence(heading.parent()) { it.parent() }
                .take(4)
                .firstOrNull {
                    it.select("a[alt] video").isNotEmpty() && it.select("h2").size == 1
                }
                ?: continue
            val uncensored = sectionTitle.lowercase().let {
                it.contains("uncensored") || it.contains("無碼") || it.contains("无码")
            }
            val cards = parseMissavCards(container, uncensored)
            val title = sectionTitle.lowercase()
            when {
                title.contains("新作") || title.contains("新片") ||
                    title == "new releases" || title.contains("最新") -> latestHanime = cards
                title.contains("更新") || title == "recent update" -> latestRelease = cards
                else -> if (cards.isNotEmpty()) {
                    extraSections.add(HomePage.MissavSection(title = sectionTitle, videos = cards))
                }
            }
        }
        return WebsiteState.Success(
            HomePage(
                csrfToken = null, avatarUrl = null,
                username = missavUserName ?: Preferences.missavLoginEmail.takeIf { it.isNotBlank() },
                banner = null,
                latestHanime = latestHanime,
                latestRelease = latestRelease,
                missavSections = extraSections,
                ecchiAnime = mutableListOf(), shortEpisodeAnime = mutableListOf(),
                twoPointFiveDAnime = mutableListOf(), threeDCG = mutableListOf(),
                motionAnime = mutableListOf(), twoDAnime = mutableListOf(),
                aiGenerated = mutableListOf(), mmd = mutableListOf(),
                cosplay = mutableListOf(), watchingNow = mutableListOf(),
                newAnimeTrailer = mutableListOf(), userId = ""
            )
        )
    }

    /**
     * jable 首頁解析。
     *
     * 首頁由多個同構的 `section` 組成，每節標題在 `.title-box h2.h3-md`，
     * 卡片是 `div.video-img-box`。只保留真的有影片卡的分節，廣告位與
     * 「影片主題/專題合集」這類純連結區塊自然被跳過。
     */
    fun jableHomePage(body: String): WebsiteState<HomePage> {
        val parseBody = Jsoup.parse(body).body()
        val sections = mutableListOf<HomePage.MissavSection>()
        for (section in parseBody.select("section")) {
            val title = section.selectFirst("div.title-box h2.h3-md")?.text()?.trim()
                ?: section.selectFirst("div.title-with-avatar h2.h3-md")?.text()?.trim()
                ?: continue
            val cards = parseJableCards(section)
            if (cards.isEmpty()) continue
            if (sections.any { it.title == title }) continue
            sections.add(HomePage.MissavSection(title = title, videos = cards))
        }
        return WebsiteState.Success(
            HomePage(
                csrfToken = null,
                avatarUrl = null,
                username = Preferences.jableLoginEmail.takeIf {
                    isAlreadyLogin && it.isNotBlank()
                },
                banner = null,
                latestHanime = mutableListOf(),
                latestRelease = mutableListOf(),
                jableSections = sections,
                ecchiAnime = mutableListOf(), shortEpisodeAnime = mutableListOf(),
                twoPointFiveDAnime = mutableListOf(), threeDCG = mutableListOf(),
                motionAnime = mutableListOf(), twoDAnime = mutableListOf(),
                aiGenerated = mutableListOf(), mmd = mutableListOf(),
                cosplay = mutableListOf(), watchingNow = mutableListOf(),
                newAnimeTrailer = mutableListOf(), userId = "",
            )
        )
    }

    fun hanimePreview(body: String): WebsiteState<HanimePreview> {
        val parseBody = Jsoup.parse(body).body()

        // latest hanime
        val latestHanimeList = mutableListOf<HanimeInfo>()
        val latestHanimeClass = parseBody.selectFirst("div[class$=owl-theme]")
        latestHanimeClass?.let {
            val latestHanimeItems = latestHanimeClass.select("div[class=home-rows-videos-div]")
            latestHanimeItems.forEach { latestHanimeItem ->
                val coverUrl = latestHanimeItem.selectFirst("img")?.absUrl("src")
                    .throwIfParseNull(Parser::hanimePreview.name, "coverUrl")
                val title = latestHanimeItem.selectFirst("div[class$=title]")?.text()
                    .throwIfParseNull(Parser::hanimePreview.name, "title")
                latestHanimeList.add(
                    HanimeInfo(
                        coverUrl = coverUrl,
                        title = title,
                        videoCode = EMPTY_STRING /* empty string here! */,
                        itemType = HanimeInfo.SIMPLIFIED
                    )
                )
            }
        }

        val contentPaddingClass = parseBody.select("div[class=content-padding] > div")
        val previewInfo = mutableListOf<HanimePreview.PreviewInfo>()
        for (i in 0 until contentPaddingClass.size / 2) {

            val firstPart = contentPaddingClass.getOrNull(i * 2)
            val secondPart = contentPaddingClass.getOrNull(i * 2 + 1)

            val videoCode = firstPart?.id()
            val title = firstPart?.selectFirst("h4")?.text()
            val coverUrl =
                firstPart?.selectFirst("div[class=preview-info-cover] > img")?.absUrl("src")
            val previewInfoContentClass =
                firstPart?.getElementsByClass("preview-info-content-padding")?.firstOrNull()
            val videoTitle = previewInfoContentClass?.selectFirst("h4")?.text()
            val brand = previewInfoContentClass?.selectFirst("h5")?.selectFirst("a")?.text()
            val releaseDate = previewInfoContentClass?.select("h5")?.getOrNull(1)?.ownText()

            val introduction = secondPart?.selectFirst("h5")?.text()
            val tagClass = secondPart?.select("div[class=single-video-tag] > a")
            val tags = mutableListOf<String>()
            tagClass?.forEach { tag: Element? ->
                tag?.let { tags.add(tag.text()) }
            }
            val relatedPicClass = secondPart?.select("img[class=preview-image-modal-trigger]")
            val relatedPics = mutableListOf<String>()
            relatedPicClass?.forEach { relatedPic: Element? ->
                relatedPic?.let { relatedPics.add(relatedPic.absUrl("src")) }
            }

            previewInfo.add(
                HanimePreview.PreviewInfo(
                    title = title,
                    videoTitle = videoTitle,
                    coverUrl = coverUrl,
                    introduction = introduction.logIfParseNull(
                        Parser::hanimePreview.name,
                        "$title introduction"
                    ),
                    brand = brand.logIfParseNull(Parser::hanimePreview.name, "$title brand"),
                    releaseDate = releaseDate.logIfParseNull(
                        Parser::hanimePreview.name,
                        "$title releaseDate"
                    ),
                    videoCode = videoCode.logIfParseNull(
                        Parser::hanimePreview.name,
                        "$title videoCode"
                    ),
                    tags = tags,
                    relatedPicsUrl = relatedPics
                )
            )
        }

        val header = parseBody.selectFirst("div[id=player-div-wrapper]")
        val headerPicUrl = header?.selectFirst("img")?.absUrl("src")
        val hasPrevious = parseBody.getElementsByClass("hidden-md hidden-lg").firstOrNull()
            ?.select("div[style*=left]")?.firstOrNull() != null
        val hasNext = parseBody.getElementsByClass("hidden-md hidden-lg").firstOrNull()
            ?.select("div[style*=right]")?.firstOrNull() != null

        return WebsiteState.Success(
            HanimePreview(
                headerPicUrl = headerPicUrl.logIfParseNull(
                    Parser::hanimePreview.name,
                    "headerPicUrl"
                ),
                hasPrevious = hasPrevious,
                hasNext = hasNext,
                latestHanime = latestHanimeList,
                previewInfo = previewInfo
            )
        )
    }

    fun myListItems(body: String): PageLoadingState<MyListItems<HanimeInfo>> {
        val parseBody = Jsoup.parse(body).body()
        val csrfToken = parseBody.selectFirst("input[name=_token]")?.attr("value")
        val desc = parseBody.getElementById("playlist-show-description")?.ownText()
        val allHanimeClass = parseBody.getElementsByClass("horizontal-row").firstOrNull()
        val myListHanimeList = allHanimeClass.extractHanimeInfo("div[class^=user-tab-item-wrapper]")
        val maxPage = parseListMaxPage(parseBody)

        return PageLoadingState.Success(
            MyListItems(
                myListHanimeList,
                desc = desc,
                csrfToken = csrfToken,
                maxPage = maxPage,
            )
        )
    }

    fun myPlayListItems(body: String): PageLoadingState<MyListItems<HanimeInfo>> {
        val parseBody = Jsoup.parse(body).body()
        val csrfToken = parseBody.selectFirst("input[name=_token]")?.attr("value")
        val desc = parseBody.select("p.playlist-description").first()?.text()
        val allHanimeClass = parseBody.getElementsByClass("playlist-video-list").firstOrNull()
        val myListHanimeList = allHanimeClass.extractHanimeInfo("div[class^=user-tab-item-wrapper]")
        val maxPage = parseListMaxPage(parseBody)

        return PageLoadingState.Success(
            MyListItems(
                myListHanimeList,
                desc = desc,
                csrfToken = csrfToken,
                maxPage = maxPage,
            )
        )
    }

    fun onlineWatchHistoryItems(body: String): PageLoadingState<MyListItems<HanimeInfo>> {
        val parseBody = Jsoup.parse(body).body()
        val csrfToken = parseBody.selectFirst("input[name=_token]")?.attr("value")
        val allHanimeClass = parseBody.getElementsByClass("horizontal-row").firstOrNull()
        val items = allHanimeClass.extractHanimeInfo("div[class^=user-tab-item-wrapper]")
        return if (items.isEmpty()) {
            PageLoadingState.NoMoreData
        } else {
            PageLoadingState.Success(
                MyListItems(items, csrfToken = csrfToken, maxPage = parseListMaxPage(parseBody))
            )
        }
    }

    fun userAccountPage(body: String): WebsiteState<UserAccount> {
        val parseBody = Jsoup.parse(body).body()
        extractFormError(parseBody)?.let { errorMessage ->
            return WebsiteState.Error(IllegalStateException(errorMessage))
        }

        val csrfToken = parseBody.selectFirst("meta[name=csrf-token]")?.attr("content")
            ?: parseBody.selectFirst("input[name=_token]")?.attr("value")
        val avatarElement = parseBody.selectFirst("img#playlist-avatar")
        val avatarUrl = avatarElement?.absUrl("src")?.ifBlank { avatarElement.attr("src") }
            .throwIfParseNull(Parser::userAccountPage.name, "avatarUrl")
        val username = parseBody.selectFirst("input[name=name]")?.attr("value")?.trim()
            .throwIfParseNull(Parser::userAccountPage.name, "username")
        val email = parseBody.selectFirst("input[name=email]")?.attr("value")?.trim()
            .throwIfParseNull(Parser::userAccountPage.name, "email")
        val userIdText = parseBody.selectFirst("div.profile-sub-stats-id")?.text()
            .throwIfParseNull(Parser::userAccountPage.name, "userId")
        val userId = Regex("""\d+""").find(userIdText)?.value
            .throwIfParseNull(Parser::userAccountPage.name, "userId")
        val joinedLabel = parseBody.selectFirst("#user-modal-created")?.text()?.trim()
        val statsText = parseBody.selectFirst("div.profile-sub-stats-new-line")?.text().orEmpty()
        val statNumbers = Regex("""\d+""").findAll(statsText)
            .mapNotNull { it.value.toIntOrNull() }
            .toList()

        return WebsiteState.Success(
            UserAccount(
                csrfToken = csrfToken,
                avatarUrl = avatarUrl,
                username = username,
                email = email,
                userId = userId,
                joinedLabel = joinedLabel,
                subscriberCount = statNumbers.getOrElse(0) { 0 },
                videoCount = statNumbers.getOrElse(1) { 0 },
            )
        )
    }

    fun creatorUploadedItems(body: String): PageLoadingState<MyListItems<HanimeInfo>> {
        val parseBody = Jsoup.parse(body).body()
        val csrfToken = parseBody.selectFirst("input[name=_token]")?.attr("value")
        val allHanimeClass = parseBody.getElementsByClass("horizontal-row").firstOrNull()
        val items = allHanimeClass.extractHanimeInfo("div[class^=user-tab-item-wrapper]")
        return if (items.isEmpty()) {
            PageLoadingState.NoMoreData
        } else {
            PageLoadingState.Success(
                MyListItems(items, csrfToken = csrfToken, maxPage = parseListMaxPage(parseBody))
            )
        }
    }

    fun creatorUploadingItems(body: String): PageLoadingState<MyListItems<CreatorUploadingItem>> {
        val parseBody = Jsoup.parse(body).body()
        val csrfToken = parseBody.selectFirst("input[name=_token]")?.attr("value")
        val wrappers = parseBody.select("div.user-tab-item-wrapper")
        if (wrappers.isEmpty()) return PageLoadingState.NoMoreData

        val items = wrappers.mapNotNull { wrapper ->
            val card = wrapper.selectFirst("div.video-item-container") ?: return@mapNotNull null
            val link = card.selectFirst("a.video-link") ?: return@mapNotNull null
            val title = card.selectFirst("div.title")?.text()?.trim() ?: return@mapNotNull null
            val coverUrl = card.selectFirst("img.main-thumb")?.absUrl("src")?.ifBlank {
                card.selectFirst("img.main-thumb")?.attr("src")
            } ?: return@mapNotNull null
            val duration = card.selectFirst("div.duration")?.text()?.trim()
            val subtitleText = card.selectFirst("div.subtitle")?.text()?.trim().orEmpty()
            val artist = subtitleText.substringBefore("•").trim().ifBlank { null }
            val uploadTime = subtitleText.substringAfter("•", "").trim().ifBlank { null }
            val remoteVideoUrl = link.attr("href")
            val reviewStatus = card.selectFirst("div.stats-container div.stat-item")?.text()?.trim()
                ?: return@mapNotNull null
            val itemId = wrapper.id().substringAfterLast("-").ifBlank { title }

            CreatorUploadingItem(
                title = title,
                coverUrl = coverUrl,
                videoCode = itemId,
                duration = duration,
                currentArtist = artist,
                uploadTime = uploadTime,
                remoteVideoUrl = remoteVideoUrl,
                reviewStatus = reviewStatus,
            )
        }

        return if (items.isEmpty()) {
            PageLoadingState.NoMoreData
        } else {
            PageLoadingState.Success(
                MyListItems(items, csrfToken = csrfToken, maxPage = parseListMaxPage(parseBody))
            )
        }
    }

    private fun extractFormError(parseBody: Element): String? {
        val selectors = listOf(
            ".alert-danger",
            ".invalid-feedback",
            ".text-danger",
            ".help-block",
            ".error-message",
        )
        selectors.forEach { selector ->
            parseBody.select(selector)
                .map { it.text().trim() }
                .firstOrNull { it.isNotBlank() }
                ?.let { return it }
        }
        return null
    }


    fun playlists(body: String): WebsiteState<Playlists> {
        val parseBody = Jsoup.parse(body).body()
        val csrfToken = parseBody.selectFirst("input[name=_token]")?.attr("value")
        val lists = parseBody.getElementsByClass("user-tab-item-wrapper")
        val playlists = mutableListOf<Playlists.Playlist>()
        lists.forEach {
            val listCode = it.selectFirst("a[class=video-link]")?.absUrl("href")?.substringAfter('=')
                .throwIfParseNull(Parser::playlists.name, "listCode")
            val listTitle = it.selectFirst("div[class=title]")?.ownText()
                .throwIfParseNull(Parser::playlists.name, "listTitle")
            val listTotal = it.selectFirst("div[class=stat-item]")?.text()
            val formatedTotal = listTotal?.filter { char -> char.isDigit() }?.toIntOrNull() ?: -1
            val coverUrl = it.select("img[class=main-thumb]").first()?.attr("src")
            playlists += Playlists.Playlist(
                listCode = listCode, title = listTitle, total = formatedTotal, coverUrl = coverUrl
            )
        }
        return WebsiteState.Success(
            Playlists(
                playlists = playlists,
                csrfToken = csrfToken,
                maxPage = parseListMaxPage(parseBody),
            )
        )
    }

    @SuppressLint("BuildListAdds")
    fun comments(body: String): WebsiteState<VideoComments> {
        val jsonObject = JSONObject(body)
        val commentBody = jsonObject.get("comments").toString()
        val parseBody = Jsoup.parse(commentBody).body()
        val csrfToken = parseBody.selectFirst("input[name=_token]")?.attr("value")
        val currentUserId = parseBody.selectFirst("input[name=comment-user-id]")?.attr("value")
        val commentList = mutableListOf<VideoComments.VideoComment>()
        val allCommentsClass = parseBody.getElementById("comment-start")

        buildList {
            allCommentsClass?.children()?.chunked(4)?.forEach { elements ->
                add(Element("div").apply { appendChildren(elements) })
            }
        }.forEach { child: Element ->
            val avatarUrl = child.selectFirst("img")?.absUrl("src")
                .throwIfParseNull(Parser::comments.name, "avatarUrl")
            val textClass = child.getElementsByClass("comment-index-text")
            val nameAndDateClass = textClass.firstOrNull()
            val username = nameAndDateClass?.selectFirst("a")?.ownText()?.trim()
                .throwIfParseNull(Parser::comments.name, "username")
            val date = nameAndDateClass?.selectFirst("span")?.ownText()?.trim()
                .throwIfParseNull(Parser::comments.name, "date")
            val content = textClass.getOrNull(1)?.text()
                .throwIfParseNull(Parser::comments.name, "content")
            val hasMoreReplies = child.selectFirst("div[class^=load-replies-btn]") != null
            val thumbUp = child.getElementById("comment-like-form-wrapper")
                ?.select("span[style]")?.getOrNull(1)
                ?.text()?.toIntOrNull()
            val id = child.selectFirst("div[id^=reply-section-wrapper]")
                ?.id()?.substringAfterLast("-")

            val foreignId = child.getElementById("foreign_id")?.attr("value")
            val isPositive = child.getElementById("is_positive")?.attr("value")
            val likeUserId = child.selectFirst("input[name=comment-like-user-id]")?.attr("value")
            val commentLikesCount =
                child.selectFirst("input[name=comment-likes-count]")?.attr("value")
            val commentLikesSum = child.selectFirst("input[name=comment-likes-sum]")?.attr("value")
            val likeCommentStatus =
                child.selectFirst("input[name=like-comment-status]")?.attr("value")
            val unlikeCommentStatus =
                child.selectFirst("input[name=unlike-comment-status]")?.attr("value")

            val post = VideoComments.VideoComment.POST(
                foreignId.logIfParseNull(Parser::comments.name, "foreignId", loginNeeded = true),
                isPositive == "1",
                likeUserId.logIfParseNull(Parser::comments.name, "likeUserId", loginNeeded = true),
                commentLikesCount?.toIntOrNull().logIfParseNull(
                    Parser::comments.name,
                    "commentLikesCount", loginNeeded = true
                ),
                commentLikesSum?.toIntOrNull().logIfParseNull(
                    Parser::comments.name,
                    "commentLikesSum", loginNeeded = true
                ),
                likeCommentStatus == "1",
                unlikeCommentStatus == "1",
            )
            val regex = """\d+""".toRegex()
            val replyCountText = child.select("div.load-replies-btn").text()
            val replyCount = regex.find(replyCountText)?.value?.toInt()
            val reportRedirectUrl = ""
            val reportableId = child.select("span.report-btn").first()?.attr("data-reportable-id")
            val reportableType = child.select("span.report-btn").first()?.attr("data-reportable-type")

            commentList.add(
                VideoComments.VideoComment(
                    avatar = avatarUrl, username = username, date = date,
                    content = content, hasMoreReplies = hasMoreReplies, replyCount = replyCount,
                    thumbUp = thumbUp.logIfParseNull(Parser::comments.name, "thumbUp"),
                    id = id.logIfParseNull(Parser::comments.name, "id"),
                    isChildComment = false, post = post,
                    redirectUrl = reportRedirectUrl, reportableId = reportableId, reportableType = reportableType
                )
            )
        }
        Log.d("commentList", commentList.toString())
        return WebsiteState.Success(
            VideoComments(
                commentList,
                currentUserId,
                csrfToken
            )
        )
    }

    fun commentReply(body: String): WebsiteState<VideoComments> {
        val jsonObject = JSONObject(body)
        val replyBody = jsonObject.get("replies").toString()
        val replyList = mutableListOf<VideoComments.VideoComment>()
        val parseBody = Jsoup.parse(replyBody).body()
        val replyStart = parseBody.selectFirst("div[id^=reply-start]")
        replyStart?.let {
            val allRepliesClass = it.children()
            for (i in allRepliesClass.indices step 2) {
                val basicClass = allRepliesClass.getOrNull(i)
                val postClass = allRepliesClass.getOrNull(i + 1)

                val avatarUrl = basicClass?.selectFirst("img")?.absUrl("src")
                    .throwIfParseNull(Parser::commentReply.name, "avatarUrl")
                val textClass = basicClass?.getElementsByClass("comment-index-text")
                val nameAndDateClass = textClass?.firstOrNull()
                val username = nameAndDateClass?.selectFirst("a")?.ownText()?.trim()
                    .throwIfParseNull(Parser::commentReply.name, "name")
                val date = nameAndDateClass?.selectFirst("span")?.ownText()?.trim()
                    .throwIfParseNull(Parser::commentReply.name, "date")
                val content = textClass?.getOrNull(1)?.text()
                    .throwIfParseNull(Parser::commentReply.name, "content")
                val thumbUp = postClass
                    ?.select("span[style]")?.getOrNull(1)
                    ?.text()?.toIntOrNull()

                val foreignId =
                    postClass?.getElementById("foreign_id")?.attr("value")
                val isPositive =
                    postClass?.getElementById("is_positive")?.attr("value")
                val likeUserId =
                    postClass?.selectFirst("input[name=comment-like-user-id]")?.attr("value")
                val commentLikesCount =
                    postClass?.selectFirst("input[name=comment-likes-count]")?.attr("value")
                val commentLikesSum =
                    postClass?.selectFirst("input[name=comment-likes-sum]")?.attr("value")
                val likeCommentStatus =
                    postClass?.selectFirst("input[name=like-comment-status]")?.attr("value")
                val unlikeCommentStatus =
                    postClass?.selectFirst("input[name=unlike-comment-status]")?.attr("value")
                val post = VideoComments.VideoComment.POST(
                    foreignId.logIfParseNull(
                        Parser::commentReply.name,
                        "foreignId",
                        loginNeeded = true
                    ),
                    isPositive == "1",
                    likeUserId.logIfParseNull(
                        Parser::commentReply.name,
                        "likeUserId",
                        loginNeeded = true
                    ),
                    commentLikesCount?.toIntOrNull().logIfParseNull(
                        Parser::commentReply.name,
                        "commentLikesCount", loginNeeded = true
                    ),
                    commentLikesSum?.toIntOrNull().logIfParseNull(
                        Parser::commentReply.name,
                        "commentLikesSum", loginNeeded = true
                    ),
                    likeCommentStatus == "1",
                    unlikeCommentStatus == "1",
                )
                val reportRedirectUrl = ""
                val reportableId = basicClass?.select("span.report-btn")?.first()?.attr("data-reportable-id")
                val reportableType = basicClass?.select("span.report-btn")?.first()?.attr("data-reportable-type")
                replyList.add(
                    VideoComments.VideoComment(
                        avatar = avatarUrl, username = username, date = date,
                        content = content,
                        thumbUp = thumbUp.logIfParseNull(Parser::commentReply.name, "thumbUp"),
                        id = null,
                        isChildComment = true, post = post, reportableId = reportableId,
                        reportableType = reportableType, redirectUrl = reportRedirectUrl
                    )
                )
            }
        }

        return WebsiteState.Success(VideoComments(replyList))
    }

    fun reportCommentResponse(body: String): WebsiteState<String> {
        // 暂时无法判断是否举报成功
        return WebsiteState.Success("已成功檢舉該則評論，我們會儘快處理您的檢舉。")
//        return if (body.contains("已成功檢舉該則評論")) {
//            WebsiteState.Success("已成功檢舉該則評論，我們會儘快處理您的檢舉。")
//        } else {
//            val doc = Jsoup.parse(body)
//            val msg = doc.select("#error").text()
//            if (msg.contains("已成功檢舉")) {
//                WebsiteState.Success(msg)
//            } else {
//                WebsiteState.Error(Throwable("举报失败或未检测到成功提示"))
//            }
//        }
    }

    fun getMySubscriptions(body: String): WebsiteState<MySubscriptions> {
        val parseBody = Jsoup.parse(body).body()
        val maxPage = parseMaxPage(parseBody)
        Log.i("getMySubscriptions", "MaxPageList=$maxPage")
        val subscriptionsRoot = parseBody.selectFirst("div.subscriptions-nav")
            ?: return WebsiteState.Error(IllegalStateException("找不到 subscriptions-nav"))
        val subscriptionsVideosRoot = parseBody.selectFirst("div.content-padding-new")
            ?: return WebsiteState.Error(IllegalStateException("找不到 subscriptionsVideosRoot"))

        // 解析订阅作者
        val artists = subscriptionsRoot.select("div.subscriptions-artist-card").mapNotNull { card ->
            try {
                val imgs = card.select("img")
                val avatarSrc = imgs.getOrNull(1)?.absUrl("src") ?: return@mapNotNull null
                val artistName = card.selectFirst("div.card-mobile-title")?.text()?.trim()
                    ?: return@mapNotNull null

                SubscriptionItem(
                    artistName = artistName,
                    avatar = avatarSrc
                )
            } catch (_: Exception) {
                null
            }
        }

        // 解析订阅视频
        val videos = subscriptionsVideosRoot.select("div[class^=video-item-container]")
            .mapNotNull { videoCard ->
                try {
                    val link =
                        videoCard.selectFirst("a[class^=video-link]")?.absUrl("href") ?: return@mapNotNull null
                    val videoCode = Regex("""watch\?v=(\d+)""").find(link)?.groupValues?.get(1)
                        ?: return@mapNotNull null
                    val coverUrl = videoCard.select("img[class^=main-thumb]").getOrNull(0)?.absUrl("src") ?: return@mapNotNull null
                    val title = videoCard.attr("title").trim()
                    val durationAndViews = videoCard.select("div[class^=thumb-container]")
                    val duration = durationAndViews.select("div[class^=duration]").text()
                    val views = durationAndViews.select("div[class^=stat-item]").getOrNull(1)?.text()
                    val subtitleElement = videoCard.selectFirst("div.subtitle")
                    val artistAndUploadTime = subtitleElement?.text()?.trim().orEmpty()
                    var artist = ""
                    var uploadTime = ""
                    if (artistAndUploadTime.contains("•")) {
                        val parts = artistAndUploadTime.split("•").map { it.trim() }
                        artist = parts.getOrNull(0).orEmpty()
                        uploadTime = parts.getOrNull(1).orEmpty()
                    } else {
                        artist = subtitleElement?.selectFirst("a")?.text()?.trim().orEmpty()
                        uploadTime = subtitleElement?.selectFirst("span.subtitle-time, span")?.text()?.trim().orEmpty()
                    }
                    val infoBoxes = videoCard.selectFirst(".stats-container .stat-item")
                    val reviews = infoBoxes?.ownText()?.trim() ?: ""

                    SubscriptionVideosItem(
                        title = title,
                        coverUrl = coverUrl,
                        videoCode = videoCode,
                        duration = duration,
                        views = views,
                        reviews = reviews,
                        currentArtist = artist,
                        uploadTime = uploadTime
                    )
                } catch (_: Exception) {
                    null
                }
            }

        return WebsiteState.Success(
            MySubscriptions(
                subscriptions = artists,
                subscriptionsVideos = videos,
                maxPage = maxPage
            )
        )
    }

    private fun parseMaxPage(parseBody: Element): Int {
        return parseBody
            .select("ul.pagination")
            .lastOrNull()
            ?.select("a.page-link[href]")
            ?.mapNotNull {
                Regex("""[?&]page=(\d+)""").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
            }
            ?.maxOrNull() ?: 1
    }

    /**
     * 搜索页没有静态的分页列表，总页数藏在「跳到指定页」的表单里：
     * <input id="skip-page-input" oninput="validateNumberInput(this, 1, 232)" .../>
     */
    private fun parseSearchMaxPage(parseBody: Element): Int {
        val input = parseBody.selectFirst("input#skip-page-input") ?: return 1
        return Regex("""validateNumberInput\(this,\s*\d+,\s*(\d+)\)""")
            .find(input.attr("oninput"))
            ?.groupValues?.get(1)
            ?.toIntOrNull()
            ?: 1
    }

    /**
     * 列表页（我的清单、历史、创作者中心等）的总页数：优先尝试「跳到指定页」表单，
     * 否则回退到订阅页使用的 ul.pagination 结构。
     */
    private fun parseListMaxPage(parseBody: Element): Int {
        val searchMax = parseSearchMaxPage(parseBody)
        if (searchMax > 1) return searchMax
        return parseMaxPage(parseBody)
    }

    /**
     * 這個網站的網頁結構真的很奇怪，所以我寫了一個 forEachStep2 來處理
     */
    private inline fun Elements.forEachStep2(action: (Element) -> Unit) {
        for (i in 0 until size step 2) {
            action(get(i))
        }
    }

    /**
     * 得到 Element 的 child，如果 index 超出範圍，就返回 null
     */
    private fun Element.childOrNull(index: Int): Element? {
        return try {
            child(index)
        } catch (_: IndexOutOfBoundsException) {
            null
        }
    }

    /**
     * 基本都是必需的參數，所以如果是 null，就直接丟出 [ParseException]
     *
     * @param funcName 這個參數是在哪個函數中被使用的
     * @param varName 這個參數的名稱
     * @return 如果 [this] 不是 null，就回傳 [this]
     * @throws ParseException 如果 [this] 是 null，就丟出 [ParseException]
     */
    private fun <T> T?.throwIfParseNull(funcName: String, varName: String): T = this
        ?: throw ParseException(funcName, varName)

    /**
     * 如果 [this] 是 null，就在 logcat 中顯示訊息
     *
     * @param funcName 這個參數是在哪個函數中被使用的
     * @param varName 這個參數的名稱
     * @return 回傳 [this]
     */
    private fun <T> T?.logIfParseNull(
        funcName: String, varName: String, loginNeeded: Boolean = false,
    ): T? = also {
        if (it == null) {
            if (loginNeeded) {
                if (isAlreadyLogin) {
                    Log.d("Parse::$funcName", "[$varName] is null. 而且處於登入狀態，這有點不正常")
                }
            } else {
                Log.d("Parse::$funcName", "[$varName] is null. 這有點不正常")
            }
        }
    }
}

/**
 * missav 的番號裏藏着是否無碼的信息。
 *
 * 站點自己也是這麼判的：詳情頁的「無碼影片」角標只認 `-uncensored-leak` 後綴，
 * 而 FC2-PPV 這類素人片源本身就沒有馬賽克，站點把它們歸在「無碼影片」分節。
 * 所以不能只看卡片落在哪個分節 —— 最近更新/隨機裏同樣會混進這兩類。
 */
internal fun isMissavUncensoredCode(code: String): Boolean {
    val lower = code.lowercase()
    return lower.contains("uncensored") || lower.startsWith("fc2")
}
