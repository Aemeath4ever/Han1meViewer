package com.yenaly.han1meviewer.ui.screen.home.homepage

import com.yenaly.han1meviewer.HanimeConstants
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.logic.model.HomePage
import com.yenaly.han1meviewer.util.DisplayTextLocalizer

/** Hero 轮播中来自视频条目的数量上限，官网运营位不计入其中。 */
private const val HERO_VIDEO_ITEM_COUNT = 6

/**
 * 构建首页 Hero 轮播数据。
 *
 * 官网运营位固定排在首位；其后从下方分类行中取若干条视频，使轮播在网站只下发一条运营位时
 * 依然可以翻页。视频优先取自「他們在看」，其余分组按用户设置的顺序补足，并跳过与运营位
 * 重复的条目。
 *
 * @param homePage 仓库层返回的首页原始数据。
 * @param categories 已按用户设置过滤排序的分类行，确保 Hero 不会展示被隐藏分组的内容。
 */
fun buildHomeHeroItems(
    homePage: HomePage,
    categories: List<HomeCategory>
): List<HomeHeroItem> {
    val officialBanner = homePage.banner?.let { banner ->
        HomeHeroItem(
            imageUrl = banner.picUrl,
            title = banner.title,
            subtitle = banner.description,
            videoCode = banner.videoCode
        )
    }
    val videos = categories
        // 推薦區塊是行為畫像內容，不參與 hero 輪播，避免首屏和登入前不一致
        .filterNot { it.key.startsWith(HOME_CATEGORY_MISSAV_RECOMMENDED_PREFIX) }
        .sortedBy { if (it.key == HOME_CATEGORY_WATCHING_NOW) 0 else 1 }
        .flatMap { it.videos }
        .asSequence()
        .filter { it.videoCode != officialBanner?.videoCode }
        .distinctBy { it.videoCode }
        .take(HERO_VIDEO_ITEM_COUNT)
        .map { video ->
            HomeHeroItem(
                imageUrl = video.coverUrl,
                title = video.title,
                subtitle = listOfNotNull(
                    video.currentArtist?.takeIf { it.isNotBlank() },
                    video.views?.takeIf { it.isNotBlank() }
                        ?.let(DisplayTextLocalizer::localizeViews),
                    video.uploadTime?.takeIf { it.isNotBlank() }
                        ?.let(DisplayTextLocalizer::localizeRelativeTime),
                ).joinToString(" · ").takeIf { it.isNotEmpty() },
                videoCode = video.videoCode
            )
        }
        .toList()
    return listOfNotNull(officialBanner) + videos
}

/**
 * 将首页原始数据转换为 UI 可直接展示的分类行数据。
 *
 * @param homePage 仓库层返回的首页原始数据。
 * @return 当前站点类型下存在视频内容的分类行列表。
 */
fun buildCategoryList(homePage: HomePage): List<HomeCategory> {
    // jable 的分節來自站點自身（最近更新/全新上市/熱門影片/他們在看），
    // 與 Hanime固定分類沒有對應關係，原樣按頁面順序展示。
    if (Preferences.isJableSite) {
        return homePage.jableSections.mapIndexed { index, section ->
            HomeCategory(
                key = "$HOME_CATEGORY_JABLE_SECTION_PREFIX$index",
                titleRes = R.string.latest_av,
                titleText = section.title,
                videos = section.videos,
            )
        }.filter { it.videos.isNotEmpty() }
    }

    val isAVSite = Preferences.baseUrl == HanimeConstants.HANIME_URL[3]

    return listOfNotNull(
        HomeCategory(
            key = HOME_CATEGORY_LATEST_HANIME,
            titleRes = if (isAVSite) R.string.latest_av else R.string.latest_hanime,
            genre = if (isAVSite) "日本AV" else "裏番",
            videos = homePage.ecchiAnime
        ),
        HomeCategory(
            key = HOME_CATEGORY_LATEST_RELEASE,
            titleRes = R.string.latest_release,
            sort = "最新上市",
            videos = homePage.latestRelease
        ),
        HomeCategory(
            key = HOME_CATEGORY_LATEST_UPLOAD,
            titleRes = R.string.latest_upload,
            sort = "最新上傳",
            videos = homePage.latestHanime
        ),
        HomeCategory(
            key = HOME_CATEGORY_WATCHING_NOW,
            titleRes = R.string.they_watched,
            sort = "他們在看",
            videos = homePage.watchingNow
        ),
        HomeCategory(
            key = HOME_CATEGORY_SHORT_EPISODE,
            titleRes = if (isAVSite) R.string.amateur_nomask else R.string.category_instant_noodle,
            genre = if (isAVSite) "素人業餘" else "泡麵番",
            sort = "最新上傳",
            videos = homePage.shortEpisodeAnime
        ),
        HomeCategory(
            key = HOME_CATEGORY_MOTION_ANIME,
            titleRes = if (isAVSite) R.string.hd_uncensored else R.string.category_motion_anime,
            genre = if (isAVSite) "高清無碼" else "Motion Anime",
            sort = "最新上傳",
            videos = homePage.motionAnime
        ),
        HomeCategory(
            key = HOME_CATEGORY_3D_CG,
            titleRes = if (isAVSite) R.string.ai_decensored else R.string.category_3d_animation,
            genre = if (isAVSite) "AI解碼" else "3DCG",
            sort = "最新上傳",
            videos = homePage.threeDCG
        ),
        HomeCategory(
            key = HOME_CATEGORY_2_5D,
            titleRes = if (isAVSite) R.string.china_av else R.string.animation_2_5d,
            genre = if (isAVSite) "國產AV" else "2.5D",
            sort = "最新上傳",
            videos = homePage.twoPointFiveDAnime
        ),
        HomeCategory(
            key = HOME_CATEGORY_2D_ANIME,
            titleRes = if (isAVSite) R.string.chinese_amateur else R.string.animation_2d,
            genre = if (isAVSite) "國產素人" else "2D動畫",
            sort = "最新上傳",
            videos = homePage.twoDAnime
        ),
        HomeCategory(
            key = HOME_CATEGORY_AI_GENERATED,
            titleRes = if (isAVSite) R.string.chinese_subtitle else R.string.ai_generated,
            genre = if (isAVSite) null else "AI生成",
            tags = if (isAVSite) "中文字幕" else null,
            sort = "最新上傳",
            videos = homePage.aiGenerated
        ),
        HomeCategory(
            key = HOME_CATEGORY_MMD,
            titleRes = if (isAVSite) R.string.ranking_today else R.string.mmd,
            genre = if (isAVSite) null else "MMD",
            sort = if (isAVSite) "本日排行" else "最新上傳",
            videos = homePage.mmd
        ),
        HomeCategory(
            key = HOME_CATEGORY_COSPLAY,
            titleRes = if (isAVSite) R.string.ranking_this_month else R.string.category_cosplay,
            genre = if (isAVSite) null else "Cosplay",
            sort = if (isAVSite) "本月排行" else "最新上傳",
            videos = homePage.cosplay
        )
    ).let { base ->
        if (!isAVSite) base
        else {
            // missav 登入後，首頁最前面幾欄是 Recombee 按帳號行為生成的
            // 「推薦給你 / 中出 / 巨乳 …」；站點把它們排在新作上市之前。
            val recommended = homePage.missavRecommended.mapIndexed { index, section ->
                HomeCategory(
                    key = "$HOME_CATEGORY_MISSAV_RECOMMENDED_PREFIX$index",
                    titleRes = R.string.missav_recommended,
                    titleText = section.title,
                    videos = section.videos
                )
            }
            // missav 首頁的其餘靜態區塊（無碼影片、隨機等）維持在固定分類之後
            val missavExtra = homePage.missavSections.mapIndexed { index, section ->
                HomeCategory(
                    key = "${HOME_CATEGORY_MISSAV_SECTION_PREFIX}$index",
                    titleRes = R.string.latest_av,
                    titleText = section.title,
                    videos = section.videos
                )
            }
            recommended + base + missavExtra
        }
    }.filter { it.videos.isNotEmpty() }
        .let { categories ->
            val hiddenKeys = hiddenHomeCategoryKeys
            val orderIndex = homeCategoryOrder.withIndex().associate { it.value to it.index }
            val visible = categories
                .filterNot { it.key in hiddenKeys }
                .sortedBy { orderIndex[it.key] ?: Int.MAX_VALUE }
            if (!isAVSite) visible
            else {
                // 動態 key 不在用戶的排序表裏（Int.MAX_VALUE），排完會落到最後，
                // 所以推薦區塊要再單獨提到最前；其餘順序保持不變。
                val (recommended, rest) = visible.partition {
                    it.key.startsWith(HOME_CATEGORY_MISSAV_RECOMMENDED_PREFIX)
                }
                recommended + rest
            }
        }
}
