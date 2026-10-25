package com.yenaly.han1meviewer.logic.model

/**
 * @project Hanime1
 * @author Yenaly Liew
 * @time 2022/06/08 008 22:45
 */
data class HomePage(
    val csrfToken: String?,
    val avatarUrl: String?,
    val username: String?,
    val banner: Banner?,
    val latestHanime: MutableList<HanimeInfo>,
    val latestRelease: MutableList<HanimeInfo>,
    /**
     * missav 首頁由 Recombee 推薦接口下發的區塊（「推薦給你」「中出」「巨乳」…），
     * 只有登入時才會填充。展示時排在靜態分節（新作上市/最近更新/無碼影片）之前。
     */
    val missavRecommended: MutableList<MissavSection> = mutableListOf(),
    /**
     * missav 首頁其餘 h2 區塊（「無碼影片」「隨機」等），
     * 按頁面順序保存，標題保留站點原文。
     */
    val missavSections: MutableList<MissavSection> = mutableListOf(),
    val ecchiAnime: MutableList<HanimeInfo>,
    val shortEpisodeAnime: MutableList<HanimeInfo>,
    val twoPointFiveDAnime: MutableList<HanimeInfo>,
    val threeDCG: MutableList<HanimeInfo>,
    val motionAnime: MutableList<HanimeInfo>,
    val twoDAnime: MutableList<HanimeInfo>,
    val aiGenerated: MutableList<HanimeInfo>,
    val mmd: MutableList<HanimeInfo>,
    val cosplay: MutableList<HanimeInfo>,
    val watchingNow: MutableList<HanimeInfo>,
    val newAnimeTrailer: MutableList<HanimeInfo>,
    val timestamp: Long = System.currentTimeMillis(),
    val userId: String
) {
    data class Banner(
        val title: String,
        val description: String?,
        val picUrl: String,
        // 目前网站的策略是，先你吗加载广告，然后再让你跳转
        // 我不敢保证他会把 videoCode 放在哪里，所以暂时可以为空
        val videoCode: String?,
    )

    /**
     * missav 首頁的一個內容區塊。
     *
     * @param title 區塊標題（站點原文，如「推薦給你」）。
     * @param videos 區塊內的視頻卡片。
     */
    data class MissavSection(
        val title: String,
        val videos: List<HanimeInfo>,
    )
}