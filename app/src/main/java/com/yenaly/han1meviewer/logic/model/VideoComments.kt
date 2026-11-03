package com.yenaly.han1meviewer.logic.model

/**
 * @project Hanime1
 * @author Yenaly Liew
 * @time 2022/06/20 020 21:56
 */
data class VideoComments(
    val videoComment: MutableList<VideoComment>,
    val currentUserId: String? = null,
    val csrfToken: String? = null,
) {
    data class VideoComment(
        // 头像
        val avatar: String,
        // 作者
        val username: String,
        // 发布日期
        val date: String,
        // 内容
        val content: String,
        // 点赞数量，登入前不能凭借post获取
        var thumbUp: Int? = null,
        // 是否为子评论
        val isChildComment: Boolean,
        // 是否有更多回复
        val hasMoreReplies: Boolean = false,
        //更多回复数量
        val replyCount: Int? = 0,
        // 评论id，登入前不能凭借post获取
        val id: String? = "-1",
        // post 相关
        val post: POST,
        //举报相关
        val redirectUrl: String? = null,
        val reportableId: String? = null,
        val reportableType: String? = null
    ) {

        val replyTargetIdOrNull get() = post.foreignId ?: id
        val stableKey get() = replyTargetIdOrNull
            ?: reportableId
            ?: buildString {
                append(username)
                append('|')
                append(date)
                append('|')
                append(content.hashCode())
            }
        val realLikesCount get() = thumbUp
        fun incLikesCount(cancel: Boolean = false): VideoComment {
            return thumbUp?.let {
                copy(
                    thumbUp = it + if (cancel) -1 else 1,
                    post = post.copy(
                        likeCommentStatus = !cancel,
                        unlikeCommentStatus = false
                    )
                )
            } ?: this
        }

        fun decLikesCount(cancel: Boolean = false): VideoComment {
            return thumbUp?.let {
                copy(
                    thumbUp = it - if (cancel) -1 else 1,
                    post = post.copy(
                        likeCommentStatus = false,
                        unlikeCommentStatus = !cancel
                    )
                )
            } ?: this
        }

        data class POST(
            // 对方id
            val foreignId: String? = null,
            // 你点的
            var isPositive: Boolean = false,
            // 你的id
            val likeUserId: String? = null,
            var commentLikesCount: Int? = null,
            var commentLikesSum: Int? = null,
            // 你之前有没有点过赞
            var likeCommentStatus: Boolean = false,
            // 你之前有没有点过踩
            var unlikeCommentStatus: Boolean = false,
        )
    }
}

/**
 * 用于 评论交互 Flow 的返回值
 */
data class VideoCommentArgs(
    // 当前评论所处adapter位置
    val commentPosition: Int,
    // 你当前点击的是赞还是踩，和comment里的isPositive不一样
    val isPositive: Boolean,
    val comment: VideoComments.VideoComment,
)