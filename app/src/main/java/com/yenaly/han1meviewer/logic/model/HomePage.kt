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
    /**
     * jable 首頁分節（最近更新/全新上市/熱門影片/他們在看…），標題保留站點原文。
     * KVS 站的分節都是同構的，這裡按頁面順序原樣展示。
     */
    val jableSections: MutableList<MissavSection> = mutableListOf(),
    /**
     * missav「新作上市」分節的「更多」列表頁（站點上指向 `/cn/release`）。
     *
     * 這兩個分節在首頁被固定分類（見 `buildCategoryList`）接管，卡片列表捨棄了
     * [MissavSection]，所以「更多」連結單獨保存，不然首頁第一欄的「更多」會沒有去處。
     */
    val missavReleaseMoreUrl: String? = null,
    /** missav「最近更新」分節的「更多」列表頁（站點上指向 `/cn/new`）。 */
    val missavUpdateMoreUrl: String? = null,
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
     * 首頁的一個內容區塊（missav 與 jable 共用）。
     *
     * @param title 區塊標題（站點原文，如「推薦給你」）。
     * @param videos 區塊內的視頻卡片。
     * @param moreUrl 站點為該分節準備的「更多」列表頁，例如 missav「無碼影片」指向 `/cn/fc2`。
     * 站點沒有提供列表頁的分節（如「隨機」）為 null，此時不顯示「更多」。
     * @param recommId Recombee 推薦分節的推薦 id。站點的「載入更多」就是拿它再調一次
     * `RecommendNextItems` 續接下一批，所以帶著它才能支援載入更多；非推薦分節為 null。
     */
    data class MissavSection(
        val title: String,
        val videos: List<HanimeInfo>,
        val moreUrl: String? = null,
        val recommId: String? = null,
    )
}