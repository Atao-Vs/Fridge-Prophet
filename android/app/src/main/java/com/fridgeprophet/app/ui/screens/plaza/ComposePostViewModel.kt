package com.fridgeprophet.app.ui.screens.plaza

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fridgeprophet.app.core.ApiResult
import com.fridgeprophet.app.core.DataRefreshBus
import com.fridgeprophet.app.data.remote.dto.RecipeOut
import com.fridgeprophet.app.data.repository.RecipeRepository
import com.fridgeprophet.app.data.repository.SocialRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** 正文上限。和后端 `PostCreate.content` 的 max_length 保持一致。 */
const val POST_MAX_LENGTH = 1000

data class ComposePostUiState(
    val content: String = "",
    /** 已上传成功的配图 URL（后端返回的相对/绝对地址） */
    val imageUrl: String? = null,
    val uploading: Boolean = false,
    val publishing: Boolean = false,
    /** 可选的菜谱库列表，展开选择器时才拉 */
    val recipes: List<RecipeOut> = emptyList(),
    val loadingRecipes: Boolean = false,
    val showRecipePicker: Boolean = false,
    val selectedRecipe: RecipeOut? = null,
    val error: String? = null,
    /** 发布成功，界面据此退出 */
    val published: Boolean = false,
) {
    /** 空动态不允许发（后端也会拒），所以按钮的可用性由这里统一决定。 */
    val canPublish: Boolean
        get() = !publishing && !uploading && (content.isNotBlank() || imageUrl != null)
}

@HiltViewModel
class ComposePostViewModel @Inject constructor(
    private val socialRepository: SocialRepository,
    private val recipeRepository: RecipeRepository,
    private val refreshBus: DataRefreshBus,
) : ViewModel() {

    private val _state = MutableStateFlow(ComposePostUiState())
    val state: StateFlow<ComposePostUiState> = _state.asStateFlow()

    /** 超出长度就**截断**而不是报错：用户粘一大段进来时，拦着不让输入更烦人。 */
    fun onContentChange(text: String) {
        _state.update { it.copy(content = text.take(POST_MAX_LENGTH)) }
    }

    fun uploadImage(file: File, mimeType: String) {
        _state.update { it.copy(uploading = true, error = null) }

        viewModelScope.launch {
            when (val result = socialRepository.uploadImage(file, mimeType)) {
                is ApiResult.Success ->
                    _state.update { it.copy(uploading = false, imageUrl = result.data.url) }
                is ApiResult.Failure ->
                    _state.update { it.copy(uploading = false, error = result.message) }
            }
            // 临时文件用完就删。上传成功与否都要删，否则缓存目录会越攒越大。
            runCatching { file.delete() }
        }
    }

    fun clearImage() = _state.update { it.copy(imageUrl = null) }

    fun toggleRecipePicker() {
        val opening = !_state.value.showRecipePicker
        _state.update { it.copy(showRecipePicker = opening) }
        // 只在第一次展开时拉列表：菜谱库变化不频繁，
        // 每次展开都请求一遍是浪费，而且会让选择器闪一下。
        if (opening && _state.value.recipes.isEmpty()) loadRecipes()
    }

    private fun loadRecipes() {
        _state.update { it.copy(loadingRecipes = true) }
        viewModelScope.launch {
            when (val result = recipeRepository.list(limit = 30)) {
                is ApiResult.Success ->
                    _state.update { it.copy(loadingRecipes = false, recipes = result.data) }
                is ApiResult.Failure ->
                    _state.update { it.copy(loadingRecipes = false, error = result.message) }
            }
        }
    }

    /** 再点一次已选中的菜谱 = 取消关联，这样不用另做一个「取消选择」按钮。 */
    fun selectRecipe(recipe: RecipeOut) {
        _state.update {
            val same = it.selectedRecipe?.id == recipe.id
            it.copy(selectedRecipe = if (same) null else recipe)
        }
    }

    fun publish() {
        val current = _state.value
        if (!current.canPublish) return

        _state.update { it.copy(publishing = true, error = null) }

        viewModelScope.launch {
            val result = socialRepository.createPost(
                content = current.content.trim(),
                imageUrl = current.imageUrl,
                recipeId = current.selectedRecipe?.id,
            )
            when (result) {
                is ApiResult.Success -> {
                    _state.update { it.copy(publishing = false, published = true) }
                    // 广播给广场，让它重新拉第一页 ——
                    // 不做「本地插到列表最前面」：新帖的实际排序位置由服务端算法决定，
                    // 本地硬插会和服务端的排序结果不一致，用户下拉刷新时帖子会「跳走」。
                    refreshBus.notify(DataRefreshBus.Topic.SOCIAL)
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(publishing = false, error = result.message) }
            }
        }
    }
}
