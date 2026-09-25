package com.fridgeprophet.app.ui.screens.plaza

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fridgeprophet.app.core.ApiResult
import com.fridgeprophet.app.core.DataRefreshBus
import com.fridgeprophet.app.data.remote.dto.PostOut
import com.fridgeprophet.app.data.remote.dto.PublicProfileOut
import com.fridgeprophet.app.data.repository.SocialRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class UserProfileUiState(
    val loading: Boolean = true,
    val profile: PublicProfileOut? = null,
    /** 这个人发过的动态。目前从广场流里筛出来，见 load() 的说明。 */
    val posts: List<PostOut> = emptyList(),
    val following: Boolean = false,
    val followerCount: Int = 0,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

@HiltViewModel
class UserProfileViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val socialRepository: SocialRepository,
    private val refreshBus: DataRefreshBus,
) : ViewModel() {

    private val userId: Int = savedStateHandle.get<Int>("userId") ?: 0

    private val _state = MutableStateFlow(UserProfileUiState())
    val state: StateFlow<UserProfileUiState> = _state.asStateFlow()

    init {
        load()
    }

    /**
     * 拉主页资料。
     *
     * ## 为什么动态列表是「从广场流里筛出来的」
     *
     * 后端目前没有「按用户查动态」的独立接口，而广场流的候选池是最近 500 条 ——
     * 对一个比赛演示规模的社区来说，这个池子已经覆盖了绝大多数内容。
     *
     * 这是一个**已知的取舍**，不是设计目标：真实产品里应该有一个
     * `GET /social/users/{id}/posts` 走索引查询。这里不假装它是完备的，
     * 界面上会说明「只显示最近发布的内容」。
     */
    fun load() {
        if (userId == 0) {
            _state.update { it.copy(loading = false, error = "用户 ID 无效") }
            return
        }
        _state.update { it.copy(loading = true, error = null) }

        viewModelScope.launch {
            val profileResult = socialRepository.publicProfile(userId)
            val feedResult = socialRepository.feed(sort = "latest", limit = 50)

            val failure = profileResult as? ApiResult.Failure
            val profile = (profileResult as? ApiResult.Success)?.data

            _state.update {
                it.copy(
                    loading = false,
                    profile = profile ?: it.profile,
                    posts = (feedResult as? ApiResult.Success)?.data?.items
                        ?.filter { p -> p.author.id == userId }
                        ?: it.posts,
                    following = profile?.followedByMe ?: it.following,
                    followerCount = profile?.followerCount ?: it.followerCount,
                    error = failure?.message,
                )
            }
        }
    }

    fun toggleFollow() {
        val profile = _state.value.profile ?: return
        // 看自己的主页时不该有关注按钮，这里再兜一层，防止界面漏判
        if (profile.isMe) return

        _state.update { it.copy(busy = true, error = null) }

        viewModelScope.launch {
            val result = if (_state.value.following) {
                socialRepository.unfollow(userId)
            } else {
                socialRepository.follow(userId)
            }
            when (result) {
                is ApiResult.Success -> {
                    _state.update {
                        it.copy(
                            busy = false,
                            following = result.data.following,
                            followerCount = result.data.followerCount,
                            message = if (result.data.following) {
                                "已关注，TA 的新动态会出现在「关注」频道"
                            } else {
                                "已取消关注"
                            },
                        )
                    }
                    // 关注关系变了，广场的「关注」频道要重算
                    refreshBus.notify(DataRefreshBus.Topic.SOCIAL)
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(busy = false, error = result.message) }
            }
        }
    }

    fun toggleLike(post: PostOut) {
        viewModelScope.launch {
            val result = if (post.likedByMe) {
                socialRepository.unlike(post.id)
            } else {
                socialRepository.like(post.id)
            }
            if (result is ApiResult.Success) {
                _state.update { st ->
                    st.copy(
                        posts = st.posts.map { p ->
                            if (p.id == post.id) {
                                p.copy(
                                    likedByMe = result.data.liked,
                                    likeCount = result.data.likeCount,
                                )
                            } else {
                                p
                            }
                        }
                    )
                }
            }
        }
    }

    fun clearMessages() = _state.update { it.copy(error = null, message = null) }
}
