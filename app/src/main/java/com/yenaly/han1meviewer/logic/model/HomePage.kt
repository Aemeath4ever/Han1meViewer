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
     * missav 首页由 Recombee 推荐接口下发的区块（「推荐给你」「中出」「巨乳」…），
     * 只有登入时才会填充。展示时排在静态分节（新作上市/最近更新/无码影片）之前。
     */
    val missavRecommended: MutableList<MissavSection> = mutableListOf(),
    /**
     * missav 首页其余 h2 区块（「无码影片」「随机」等），
     * 按页面顺序保存，标题保留站点原文。
     */
    val missavSections: MutableList<MissavSection> = mutableListOf(),
    /**
     * jable 首页分节（最近更新/全新上市/热门影片/他们在看…），标题保留站点原文。
     * KVS 站的分节都是同构的，这里按页面顺序原样展示。
     */
    val jableSections: MutableList<MissavSection> = mutableListOf(),
    /**
     * missav「新作上市」分节的「更多」列表页（站点上指向 `/cn/release`）。
     *
     * 这两个分节在首页被固定分类（见 `buildCategoryList`）接管，卡片列表舍弃了
     * [MissavSection]，所以「更多」连结单独保存，不然首页第一栏的「更多」会没有去处。
     */
    val missavReleaseMoreUrl: String? = null,
    /** missav「最近更新」分节的「更多」列表页（站点上指向 `/cn/new`）。 */
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
     * 首页的一个内容区块（missav 与 jable 共用）。
     *
     * @param title 区块标题（站点原文，如「推荐给你」）。
     * @param videos 区块内的视频卡片。
     * @param moreUrl 站点为该分节准备的「更多」列表页，例如 missav「无码影片」指向 `/cn/fc2`。
     * 站点没有提供列表页的分节（如「随机」）为 null，此时不显示「更多」。
     * @param recommId Recombee 推荐分节的推荐 id。站点的「载入更多」就是拿它再调一次
     * `RecommendNextItems` 续接下一批，所以带著它才能支援载入更多；非推荐分节为 null。
     */
    data class MissavSection(
        val title: String,
        val videos: List<HanimeInfo>,
        val moreUrl: String? = null,
        val recommId: String? = null,
    )
}