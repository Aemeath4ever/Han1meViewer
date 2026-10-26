package com.yenaly.han1meviewer.logic.network.service

import com.yenaly.han1meviewer.logic.model.github.CommitComparison
import com.yenaly.han1meviewer.logic.model.github.Release
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Streaming
import retrofit2.http.Url

/**
 * @project Han1meViewer
 * @author Yenaly Liew
 * @time 2022/09/09 009 20:04
 */
interface HGitHubService {
    /**
     * 最新 Release。
     *
     * 返回[Response]而非实体类型是刻意为之：只有拿到[Response]才能读取状态码、错误响应体
     * 与 `x-ratelimit-*` 响应头，从而区分「限额耗尽 / Token 失效 / 权限不足 / 服务端故障」
     * 并给出对应提示。
     */
    @GET("releases/latest")
    suspend fun getLatestVersion(): Response<Release>

    /**
     * 指定 tag 的 Release。
     *
     * CI 更新通道读取滚动预发布 tag（`debug-latest`）：每次工作流都用 `--clobber` 覆盖同名
     * asset，因此这里拿到的永远是最近一次 CI 构建的安装包。
     */
    @GET("releases/tags/{tag}")
    suspend fun getReleaseByTag(
        @Path("tag") tag: String,
    ): Response<Release>

    /**
     * What is Commit Comparison?
     *
     * Compare two commits in a repository. The response will include a comparison of the two commits. The response can
     * include difference in various aspects such as files, commits, and comments.
     */
    @GET("compare/{curSha}...{latestSha}")
    suspend fun getCommitComparison(
        @Path("curSha") curSha: String,
        @Path("latestSha") latestSha: String,
    ): Response<CommitComparison>

    /**
     * Typical request
     *
     * 下载更新包专用。调用方必须先检查[Response.isSuccessful]：非 2xx 时
     * [Response.errorBody] 里是 GitHub 的错误 JSON，直接写进文件会得到
     * 一个「能存下来但装不上」的假安装包。
     */
    @GET
    @Streaming
    suspend fun request(
        @Url url: String,
    ): Response<ResponseBody>
}
