package com.fridgeprophet.app.ui.screens.plaza

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fridgeprophet.app.core.ApiResult
import com.fridgeprophet.app.core.DataRefreshBus
import com.fridgeprophet.app.data.remote.dto.PostOut
import com.fridgeprophet.app.data.remote.dto.SortOption
import com.fridgeprophet.app.data.repository.SocialRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 一页拉多少条。和后端 `limit` 的上限（50）留了余量。 */
private const val PAGE_SIZE = 20

/**
 * 分享请求。
 *
 * 做成 state 里的一次性事件而不是直接在这里调 `startActivity`：
 * ViewModel 拿不到 Context（拿到了就容易泄漏 Activity）。
 * 由界面观察到它、调起系统分享面板，再回来把事件清掉。
 */
data class ShareRequest(val postId: Int, val text: String)

data class PlazaUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val posts: List<PostOut> = emptyList(),
    val sort: String = "composite",
    val sortOptions: List<SortOption> = emptyList(),
    /** 只看我关注的人 */
    val onlyFollowing: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val shareRequest: ShareRequest? = null,
)

@HiltViewModel
class PlazaViewModel @Inject constructor(
    private val socialRepository: SocialRepository,
    private val refreshBus: DataRefreshBus,
) : ViewModel() {

    private val _state = MutableStateFlow(PlazaUiState())
    val state: StateFlow<PlazaUiState> = _state.asStateFlow()

    init {
        refresh()
        // 发布 / 删除动态后广播，广场据此刷新
        viewModelScope.launch {
            refreshBus.events.collect { topic ->
                if (topic == DataRefreshBus.Topic.SOCIAL) refresh()
            }
        }
    }

    /**
     * 重新拉第一页。
     *
     * `refreshing` 和 `loading` 分开：下拉刷新时列表还在屏幕上，
     * 这时候把整页换成转圈会让内容闪一下。只有首次加载才显示整页 loading。
     */
    fun refresh(fromPull: Boolean = false) {
        if (fromPull) {
            _state.update { it.copy(refreshing = true, error = null) }
        } else if (_state.value.posts.isEmpty()) {
            _state.update { it.copy(loading = true, error = null) }
        }

        viewModelScope.launch {
            val result = socialRepository.feed(
                sort = _state.value.sort,
                limit = PAGE_SIZE,
                offset = 0,
                onlyFollowing = _state.value.onlyFollowing,
            )
            when (result) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        posts = result.data.items,
                        sortOptions = result.data.sortOptions.ifEmpty { it.sortOptions },
                        hasMore = result.data.hasMore,
                        error = null,
                    )
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(loading = false, refreshing = false, error = result.message)
                }
            }
        }
    }

    /** 切换排序。会重置分页 —— 换了排序还接着上次的 offset 会漏掉/重复内容。 */
    fun setSort(sort: String) {
        if (sort == _state.value.sort) return
        _state.update { it.copy(sort = sort, posts = emptyList(), loading = true) }
        refresh()
    }

    fun toggleOnlyFollowing() {
        _state.update {
            it.copy(onlyFollowing = !it.onlyFollowing, posts = emptyList(), loading = true)
        }
        refresh()
    }

    fun loadMore() {
        val current = _state.value
        if (current.loadingMore || !current.hasMore || current.loading) return

        _state.update { it.copy(loadingMore = true) }

        viewModelScope.launch {
            val result = socialRepository.feed(
                sort = current.sort,
                limit = PAGE_SIZE,
                offset = current.posts.size,
                onlyFollowing = current.onlyFollowing,
            )
            when (result) {
                is ApiResult.Success -> _state.update {
                    // 追加而不是替换。去重是必要的：排序分可能因为别人点赞而变化，
                    // 导致同一条帖子在第 1 页和第 2 页都出现 —— 界面上会看到重复卡片。
                    val known = it.posts.map { p -> p.id }.toSet()
                    val appended = result.data.items.filter { p -> p.id !in known }
                    it.copy(
                        loadingMore = false,
                        posts = it.posts + appended,
                        hasMore = result.data.hasMore,
                    )
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(loadingMore = false, error = result.message) }
            }
        }
    }

    /**
     * 点赞 / 取消点赞。
     *
     * 用**服务端返回的计数**覆盖本地，不做本地 +1 猜测。
     * 理由见 SocialRepository 的说明。
     */
    fun toggleLike(post: PostOut) {
        viewModelScope.launch {
            val result = if (post.likedByMe) {
                socialRepository.unlike(post.id)
            } else {
                socialRepository.like(post.id)
            }
            when (result) {
                is ApiResult.Success -> _state.update { st ->
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
                is ApiResult.Failure ->
                    _state.update { it.copy(error = result.message) }
            }
        }
    }

    /**
     * 请求分享。这里只累加服务端计数并把文案交给界面，
     * **不自己调系统分享** —— 见 [ShareRequest] 的说明。
     */
    fun requestShare(post: PostOut) {
        viewModelScope.launch {
            when (val result = socialRepository.share(post.id)) {
                is ApiResult.Success -> _state.update { st ->
                    st.copy(
                        posts = st.posts.map { p ->
                            if (p.id == post.id) p.copy(shareCount = result.data.shareCount) else p
                        },
                        shareRequest = ShareRequest(post.id, result.data.shareText),
                    )
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(error = result.message) }
            }
        }
    }

    /** 界面调起分享面板之后调用，避免旋转屏幕时又弹一次。 */
    fun consumeShareRequest() = _state.update { it.copy(shareRequest = null) }

    fun deletePost(post: PostOut) {
        viewModelScope.launch {
            when (val result = socialRepository.deletePost(post.id)) {
                is ApiResult.Success -> {
                    _state.update { st ->
                        st.copy(
                            posts = st.posts.filterNot { it.id == post.id },
                            message = "已删除",
                        )
                    }
                    refreshBus.notify(DataRefreshBus.Topic.SOCIAL)
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(error = result.message) }
            }
        }
    }

    fun clearMessages() = _state.update { it.copy(error = null, message = null) }
}
