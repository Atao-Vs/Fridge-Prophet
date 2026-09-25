package com.fridgeprophet.app.ui.screens.plaza

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fridgeprophet.app.core.ApiResult
import com.fridgeprophet.app.core.DataRefreshBus
import com.fridgeprophet.app.data.remote.dto.CommentOut
import com.fridgeprophet.app.data.remote.dto.PostOut
import com.fridgeprophet.app.data.repository.SocialRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PostDetailUiState(
    val loading: Boolean = true,
    val post: PostOut? = null,
    val comments: List<CommentOut> = emptyList(),
    val draft: String = "",
    val sending: Boolean = false,
    val error: String? = null,
    val shareRequest: ShareRequest? = null,
    /** 动态被删掉后界面要退出去 */
    val deleted: Boolean = false,
)

@HiltViewModel
class PostDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val socialRepository: SocialRepository,
    private val refreshBus: DataRefreshBus,
) : ViewModel() {

    private val postId: Int = savedStateHandle.get<Int>("postId") ?: 0

    private val _state = MutableStateFlow(PostDetailUiState())
    val state: StateFlow<PostDetailUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        if (postId == 0) {
            _state.update { it.copy(loading = false, error = "动态 ID 无效") }
            return
        }
        _state.update { it.copy(loading = true, error = null) }

        viewModelScope.launch {
            val postResult = socialRepository.post(postId)
            val commentResult = socialRepository.comments(postId)

            val failure = listOf(postResult, commentResult)
                .filterIsInstance<ApiResult.Failure>()
                .firstOrNull()

            _state.update {
                it.copy(
                    loading = false,
                    post = (postResult as? ApiResult.Success)?.data ?: it.post,
                    comments = (commentResult as? ApiResult.Success)?.data?.items ?: it.comments,
                    error = failure?.message,
                )
            }
        }
    }

    fun onDraftChange(text: String) {
        // 评论上限和后端 CommentCreate.content 的 500 对齐
        _state.update { it.copy(draft = text.take(500)) }
    }

    fun sendComment() {
        val text = _state.value.draft.trim()
        if (text.isEmpty() || _state.value.sending) return

        _state.update { it.copy(sending = true, error = null) }

        viewModelScope.launch {
            when (val result = socialRepository.addComment(postId, text)) {
                is ApiResult.Success -> _state.update { st ->
                    st.copy(
                        sending = false,
                        draft = "",
                        // 追加到末尾：评论列表是按时间正序的，新评论就该在最后。
                        // 同时把动态上的评论数 +1，否则退出去看列表时数字对不上。
                        comments = st.comments + result.data,
                        post = st.post?.copy(commentCount = st.post.commentCount + 1),
                    )
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(sending = false, error = result.message) }
            }
        }
    }

    /**
     * 删除评论。**本人和动态作者都能删**（后端两条路都放行）——
     * 动态作者删别人评论是社区产品的基本要求，否则被刷广告只能干看着。
     */
    fun deleteComment(comment: CommentOut) {
        viewModelScope.launch {
            when (val result = socialRepository.deleteComment(comment.id)) {
                is ApiResult.Success -> _state.update { st ->
                    st.copy(
                        comments = st.comments.filterNot { it.id == comment.id },
                        post = st.post?.copy(
                            commentCount = (st.post.commentCount - 1).coerceAtLeast(0)
                        ),
                    )
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(error = result.message) }
            }
        }
    }

    fun toggleLike() {
        val post = _state.value.post ?: return
        viewModelScope.launch {
            val result = if (post.likedByMe) {
                socialRepository.unlike(post.id)
            } else {
                socialRepository.like(post.id)
            }
            if (result is ApiResult.Success) {
                _state.update { st ->
                    st.copy(
                        post = st.post?.copy(
                            likedByMe = result.data.liked,
                            likeCount = result.data.likeCount,
                        )
                    )
                }
            } else if (result is ApiResult.Failure) {
                _state.update { it.copy(error = result.message) }
            }
        }
    }

    fun requestShare() {
        val post = _state.value.post ?: return
        viewModelScope.launch {
            when (val result = socialRepository.share(post.id)) {
                is ApiResult.Success -> _state.update { st ->
                    st.copy(
                        post = st.post?.copy(shareCount = result.data.shareCount),
                        shareRequest = ShareRequest(post.id, result.data.shareText),
                    )
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(error = result.message) }
            }
        }
    }

    fun consumeShareRequest() = _state.update { it.copy(shareRequest = null) }

    fun deletePost() {
        viewModelScope.launch {
            when (val result = socialRepository.deletePost(postId)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(deleted = true) }
                    // 广场列表还留着这条，广播让它刷新
                    refreshBus.notify(DataRefreshBus.Topic.SOCIAL)
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(error = result.message) }
            }
        }
    }

    fun clearMessages() = _state.update { it.copy(error = null) }
}
