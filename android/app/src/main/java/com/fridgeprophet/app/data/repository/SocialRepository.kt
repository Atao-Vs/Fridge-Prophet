package com.fridgeprophet.app.data.repository

import com.fridgeprophet.app.core.ApiResult
import com.fridgeprophet.app.core.safeApiCall
import com.fridgeprophet.app.data.remote.FridgeApi
import com.fridgeprophet.app.data.remote.dto.CommentCreateRequest
import com.fridgeprophet.app.data.remote.dto.CommentListOut
import com.fridgeprophet.app.data.remote.dto.CommentOut
import com.fridgeprophet.app.data.remote.dto.FeedOut
import com.fridgeprophet.app.data.remote.dto.FollowResultOut
import com.fridgeprophet.app.data.remote.dto.FollowUserOut
import com.fridgeprophet.app.data.remote.dto.ImageUploadOut
import com.fridgeprophet.app.data.remote.dto.LikeResultOut
import com.fridgeprophet.app.data.remote.dto.PostCreateRequest
import com.fridgeprophet.app.data.remote.dto.PostOut
import com.fridgeprophet.app.data.remote.dto.PublicProfileOut
import com.fridgeprophet.app.data.remote.dto.ShareResultOut
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 广场（社区）。
 *
 * ## 点赞 / 关注为什么不做本地乐观更新
 *
 * 这两类操作的返回值里带着**权威计数**（`like_count` / `follower_count`）。
 * 如果先在本地 +1 再等服务器返回，中间会出现「我 +1 了、服务器返回 5、界面跳回 5」
 * 的抖动；而且一旦请求失败，本地那个 +1 就得回滚，回滚逻辑比不做还容易错。
 * 所以统一「等服务端返回再改界面」，代价是几十毫秒的延迟，
 * 换来的是计数永远和服务器一致。
 *
 * ## 点赞是幂等的
 *
 * 后端用「存在就不加」实现，所以重复调用不会把计数刷上去。
 * 这让「网络超时后重试」是安全的，不需要客户端做去重。
 */
@Singleton
class SocialRepository @Inject constructor(private val api: FridgeApi) {

    suspend fun feed(
        sort: String = "composite",
        limit: Int = 20,
        offset: Int = 0,
        onlyFollowing: Boolean = false,
    ): ApiResult<FeedOut> = safeApiCall {
        api.getFeed(sort = sort, limit = limit, offset = offset, onlyFollowing = onlyFollowing)
    }

    suspend fun createPost(
        content: String,
        imageUrl: String?,
        recipeId: Int?,
        tags: List<String> = emptyList(),
    ): ApiResult<PostOut> = safeApiCall {
        api.createPost(
            PostCreateRequest(
                content = content,
                imageUrl = imageUrl,
                recipeId = recipeId,
                tags = tags,
            )
        )
    }

    suspend fun post(id: Int): ApiResult<PostOut> = safeApiCall { api.getPost(id) }

    suspend fun deletePost(id: Int): ApiResult<Unit> = safeApiCall { api.deletePost(id) }

    suspend fun like(id: Int): ApiResult<LikeResultOut> = safeApiCall { api.likePost(id) }

    suspend fun unlike(id: Int): ApiResult<LikeResultOut> = safeApiCall { api.unlikePost(id) }

    suspend fun share(id: Int): ApiResult<ShareResultOut> = safeApiCall { api.sharePost(id) }

    suspend fun comments(postId: Int): ApiResult<CommentListOut> =
        safeApiCall { api.listComments(postId) }

    suspend fun addComment(postId: Int, content: String): ApiResult<CommentOut> =
        safeApiCall { api.addComment(postId, CommentCreateRequest(content)) }

    suspend fun deleteComment(id: Int): ApiResult<Unit> = safeApiCall { api.deleteComment(id) }

    /**
     * 上传动态配图。
     *
     * mime 必须来自真实文件类型：后端按 content_type 做白名单，
     * 把 PNG 报成 jpeg 会被 415 直接拒掉。
     */
    suspend fun uploadImage(file: File, mimeType: String): ApiResult<ImageUploadOut> =
        safeApiCall {
            val body = file.asRequestBody(mimeType.toMediaTypeOrNull())
            api.uploadPostImage(MultipartBody.Part.createFormData("file", file.name, body))
        }

    suspend fun searchUsers(keyword: String): ApiResult<List<FollowUserOut>> =
        safeApiCall { api.searchUsers(keyword) }

    /** 我关注的人的 id 集合。进广场时拉一次，用来决定按钮显示「关注」还是「已关注」。 */
    suspend fun myFollowingIds(): ApiResult<List<Int>> = safeApiCall { api.myFollowingIds() }

    suspend fun publicProfile(userId: Int): ApiResult<PublicProfileOut> =
        safeApiCall { api.getPublicProfile(userId) }

    suspend fun follow(userId: Int): ApiResult<FollowResultOut> =
        safeApiCall { api.followUser(userId) }

    suspend fun unfollow(userId: Int): ApiResult<FollowResultOut> =
        safeApiCall { api.unfollowUser(userId) }

    suspend fun followers(userId: Int): ApiResult<List<FollowUserOut>> =
        safeApiCall { api.listFollowers(userId) }

    suspend fun following(userId: Int): ApiResult<List<FollowUserOut>> =
        safeApiCall { api.listFollowing(userId) }
}
