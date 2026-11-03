package com.yenaly.han1meviewer.ui.view.video

import cn.jzvd.JZDataSource
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.ResolutionLinkMap

class HanimeDataSource : JZDataSource {

    private val urlsList = mutableListOf<Map.Entry<Any?, Any?>>()

    @Suppress("UNCHECKED_CAST")
    constructor(title: String, resolutionLinkMap: ResolutionLinkMap) : this() {
        this.currentUrlIndex = 0
        urlsList.clear()
        this.urlsMap.also { map ->
            map.clear()
            resolutionLinkMap.mapValuesTo(map) { it.value.link }
            urlsList.addAll(map.entries as Set<Map.Entry<Any?, Any?>>)
        }
        this.title = title
        this.headerMap = hashMapOf()
        // missav 的影片源 (surrit.com) 要求带站点 Referer，否则会被 Cloudflare 拦截
        if (resolutionLinkMap.values.any { it.link.contains("surrit.") }) {
            this.headerMap["Referer"] = Preferences.baseUrl
        }
        // jable 的 m3u8 走第三方签名 CDN；带上站点 Referer 更接近站内播放器行为。
        if (Preferences.isJableSite) {
            this.headerMap["Referer"] = Preferences.baseUrl
        }
        this.looping = false
        this.objects = null
    }

    override fun getKeyFromDataSource(index: Int): String? {
        return urlsList.getOrNull(index)?.key?.toString()
    }

    override fun getValueFromLinkedMap(index: Int): Any? {
        return urlsList.getOrNull(index)?.value
    }

    private constructor() : super("")


}