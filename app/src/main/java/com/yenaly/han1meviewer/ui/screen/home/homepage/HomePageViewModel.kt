package com.yenaly.han1meviewer.ui.screen.home.homepage

import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.database.FirebaseDatabase
import com.yenaly.han1meviewer.FIREBASE_REALTIME_DATABASE
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.logic.DatabaseRepo
import com.yenaly.han1meviewer.logic.NetworkRepo
import com.yenaly.han1meviewer.logic.entity.HKeyframeEntity
import com.yenaly.han1meviewer.logic.entity.WatchHistoryEntity
import com.yenaly.han1meviewer.logic.exception.LoginStateExpiredException
import com.yenaly.han1meviewer.logic.model.Announcement
import com.yenaly.han1meviewer.logic.network.MissavRecommender
import com.yenaly.han1meviewer.logic.state.PageState
import com.yenaly.han1meviewer.logic.state.WebsiteState
import com.yenaly.han1meviewer.logout
import com.yenaly.han1meviewer.ui.viewmodel.AppViewModel
import com.yenaly.yenaly_libs.utils.getSpValue
import com.yenaly.yenaly_libs.utils.putSpValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

class HomePageViewModel: ViewModel() {
    data class SessionExpiredMessage(
        val message: String?,
        @param:StringRes val fallbackResId: Int,
    )

    private val _homePageFlow = MutableStateFlow<PageState<HomeData>>(PageState.Loading)
    val homePageFlow = _homePageFlow.asStateFlow()

    private val _sectionMoreFlow = MutableStateFlow(SectionMoreState())
    val sectionMoreFlow = _sectionMoreFlow.asStateFlow()

    /**
     * 各推薦分類續接後的 recommId（分類 key → 當前推薦 id）。
     *
     * Recombee 的下一批要用「上一次續接返回的 recommId」，首頁資料裡的 id 只對第一批有效。
     */
    private val sectionRecommIds = mutableMapOf<String, String>()

    private val database = FirebaseDatabase.getInstance(FIREBASE_REALTIME_DATABASE)

    private val _sessionExpiredMessage = MutableSharedFlow<SessionExpiredMessage>()
    val sessionExpiredMessage = _sessionExpiredMessage

    private var homePageJob: Job? = null

    init {
        viewModelScope.launch {
            // 初始化默认已下载分组，防止[FOREIGN KEY constraint failed]
            DatabaseRepo.HanimeDownload.insertDefaultGroup()
        }
    }

    fun getHomePage(isRefresh: Boolean = false){
        homePageJob?.cancel()
        homePageJob = viewModelScope.launch {
            val current = _homePageFlow.value
            if (isRefresh && current is PageState.Success) {
                _homePageFlow.value = current.copy(isRefreshing = true)
            } else if (isRefresh && current is PageState.Error && current.cachedInfo != null) {
                _homePageFlow.value = PageState.Success(info = current.cachedInfo, isRefreshing = true)
            } else if (!isRefresh && current !is PageState.Success){
                _homePageFlow.value = PageState.Loading
            }
            val announcementsDeferred = async(Dispatchers.IO) {
                withTimeoutOrNull(ANNOUNCEMENTS_TIMEOUT_MILLIS.milliseconds) {
                    fetchAnnouncementsFromFirebase()
                }.orEmpty()
            }
            NetworkRepo.getHomePage().collect { networkState ->
                when (networkState){
                    is WebsiteState.Error -> {
                        announcementsDeferred.cancel()
                        if (networkState.throwable is LoginStateExpiredException) {
                            logout()
                            _sessionExpiredMessage.emit(
                                SessionExpiredMessage(
                                    message = networkState.throwable.message,
                                    fallbackResId = R.string.login_state_expired,
                                )
                            )
                        }
                        val previousData = (_homePageFlow.value as? PageState.Success)?.info
                        _homePageFlow.value = PageState.Error(networkState.throwable, cachedInfo = previousData)
                    }
                    is WebsiteState.Success -> {
                        val currentAnnouncements = announcementsDeferred.await()
                        AppViewModel.csrfToken = networkState.info.csrfToken
                        networkState.info.userId.takeIf { it.isNotEmpty() }?.let { userId ->
                            Preferences.savedUserId = userId
                        }
                        // 新一輪首頁數據的推薦 id 也換了，續接出來的卡片一併作廢
                        sectionRecommIds.clear()
                        _sectionMoreFlow.value = SectionMoreState()
                        val homeData = HomeData(page = networkState.info, announcements = currentAnnouncements)
                        _homePageFlow.value = PageState.Success(info = homeData, isRefreshing = false)
                    }
                    is WebsiteState.Loading -> { }
                }
            }
        }
    }

    /**
     * 推薦分類的「更多」：續接 Recombee 的下一批，追加到該行尾。
     *
     * @param categoryKey 分類 key（`missav_recommended_N`），用於把結果放回同一行
     * @param recommId 該行當前持有的推薦 id；續接過的行改用 [sectionRecommIds] 裡較新的那個
     */
    fun loadMoreRecommendedSection(categoryKey: String, recommId: String) {
        val current = _sectionMoreFlow.value
        if (categoryKey in current.loadingKeys) return
        val activeRecommId = sectionRecommIds[categoryKey] ?: recommId
        _sectionMoreFlow.value = current.copy(loadingKeys = current.loadingKeys + categoryKey)
        viewModelScope.launch {
            val next = MissavRecommender.fetchNext(activeRecommId)
            val state = _sectionMoreFlow.value
            // 请求期间首页刷新过（加载状态被清空）：这一批属于旧的首頁數據，直接丢弃
            if (categoryKey !in state.loadingKeys) return@launch
            if (next == null) {
                Log.d("homeMore", "load more failed: $categoryKey")
                _sectionMoreFlow.value = state.copy(loadingKeys = state.loadingKeys - categoryKey)
                return@launch
            }
            sectionRecommIds[categoryKey] = next.recommId
            _sectionMoreFlow.value = state.copy(
                extraItems = state.extraItems +
                        (categoryKey to (state.extraItems[categoryKey].orEmpty() + next.videos)),
                loadingKeys = state.loadingKeys - categoryKey,
            )
        }
    }

    private suspend fun fetchAnnouncementsFromFirebase(): List<Announcement> =
        suspendCancellableCoroutine { continuation ->
            val lastDismissTime = getSpValue("last_dismiss_time", 0L, "setting_pref")
            val shouldShowAnno = System.currentTimeMillis() - lastDismissTime > 24 * 60 * 60 * 1000L
            if (!shouldShowAnno) {
                continuation.resume(emptyList())
                return@suspendCancellableCoroutine
            }

            database.getReference("announcements").get()
                .addOnSuccessListener { snapshot ->
                    val list = mutableListOf<Announcement>()
                    if (snapshot.exists()) {
                        for (announceSnap in snapshot.children) {
                            val announcement = announceSnap.getValue(Announcement::class.java)
                            if (announcement != null && announcement.isActive) {
                                list.add(announcement)
                            }
                        }
                        if (continuation.isActive) {
                            continuation.resume(list.sortedBy { it.priority })
                        }
                    } else {
                        if (continuation.isActive) {
                            continuation.resume(emptyList())
                        }
                    }
                }.addOnFailureListener { e ->
                    Log.e("Announcement", "读取失败: ${e.message}")
                    if (continuation.isActive) {
                        continuation.resume(emptyList()) // 失败也容错返回空列表
                    }
                }
        }

    private companion object {
        const val ANNOUNCEMENTS_TIMEOUT_MILLIS = 5_000L
    }

    fun dismissAnnouncements(){
        putSpValue("last_dismiss_time", System.currentTimeMillis(), "setting_pref")
        val current = _homePageFlow.value
        if (current is PageState.Success) {
            _homePageFlow.value = current.copy(info = current.info.copy(announcements = emptyList()))
        }
    }

    fun deleteWatchHistory(history: WatchHistoryEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            DatabaseRepo.WatchHistory.delete(history)
            Log.d("delete_watch_hty", "$history DONE!")
        }
    }

    fun deleteAllWatchHistories() {
        viewModelScope.launch(Dispatchers.IO) {
            DatabaseRepo.WatchHistory.deleteAll()
            Log.d("del_all_watch_hty", "DONE!")
        }
    }

    fun loadAllWatchHistories() =
        DatabaseRepo.WatchHistory.loadAll()
            .catch { e -> e.printStackTrace() }
            .flowOn(Dispatchers.IO)
    private val _modifyHKeyframeFlow = MutableSharedFlow<Boolean>()
    fun removeHKeyframe(videoCode: String, hKeyframe: HKeyframeEntity.Keyframe) {
        viewModelScope.launch(Dispatchers.IO) {
            DatabaseRepo.HKeyframe.removeKeyframe(videoCode, hKeyframe)
            Log.d("HKeyframe", "removeHKeyframe:$hKeyframe DONE!")
            _modifyHKeyframeFlow.emit(true)
        }
    }
    fun modifyHKeyframe(
        videoCode: String,
        oldKeyframe: HKeyframeEntity.Keyframe, keyframe: HKeyframeEntity.Keyframe,
    ) {
        viewModelScope.launch {
            DatabaseRepo.HKeyframe.modifyKeyframe(videoCode, oldKeyframe, keyframe)
            Log.d("HKeyframe", "modifyHKeyframe:$keyframe DONE!")
            _modifyHKeyframeFlow.emit(true)
        }
    }
    fun deleteHKeyframes(entity: HKeyframeEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            DatabaseRepo.HKeyframe.delete(entity)
        }
    }

    fun updateHKeyframes(entity: HKeyframeEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            DatabaseRepo.HKeyframe.update(entity)
        }
    }
}
