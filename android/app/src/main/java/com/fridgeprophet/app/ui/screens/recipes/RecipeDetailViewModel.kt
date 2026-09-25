package com.fridgeprophet.app.ui.screens.recipes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fridgeprophet.app.core.ApiResult
import com.fridgeprophet.app.core.DataRefreshBus
import com.fridgeprophet.app.data.remote.dto.RecipeOut
import com.fridgeprophet.app.data.repository.RecipeRepository
import com.fridgeprophet.app.data.repository.ShoppingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RecipeDetailUiState(
    val loading: Boolean = true,
    val recipe: RecipeOut? = null,
    val error: String? = null,
    val message: String? = null,
    val buildingShopping: Boolean = false,
    val feedbackSent: String? = null,
)

@HiltViewModel
class RecipeDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val recipeRepository: RecipeRepository,
    private val shoppingRepository: ShoppingRepository,
    private val refreshBus: DataRefreshBus,
) : ViewModel() {

    private val recipeId: Int = savedStateHandle.get<Int>("recipeId") ?: 0

    private val _state = MutableStateFlow(RecipeDetailUiState())
    val state: StateFlow<RecipeDetailUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        if (recipeId == 0) {
            _state.update { it.copy(loading = false, error = "菜谱 ID 无效") }
            return
        }
        _state.update { it.copy(loading = true, error = null) }

        viewModelScope.launch {
            when (val result = recipeRepository.detail(recipeId)) {
                is ApiResult.Success ->
                    _state.update { it.copy(loading = false, recipe = result.data) }
                is ApiResult.Failure ->
                    _state.update { it.copy(loading = false, error = result.message) }
            }
        }
    }

    /** 用这道菜的缺料生成采购清单 */
    fun buildShoppingList(onSuccess: () -> Unit) {
        _state.update { it.copy(buildingShopping = true, error = null, message = null) }

        viewModelScope.launch {
            when (val result = shoppingRepository.build(
                recipeIds = listOf(recipeId),
                title = "为「${_state.value.recipe?.name ?: "这道菜"}」采购",
            )) {
                is ApiResult.Success -> {
                    _state.update {
                        it.copy(
                            buildingShopping = false,
                            message = "已生成采购清单，共 ${result.data.items.size} 项，预计 ¥${result.data.estimatedTotal}",
                        )
                    }
                    refreshBus.notify(DataRefreshBus.Topic.SHOPPING)
                    onSuccess()
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(buildingShopping = false, error = result.message) }
            }
        }
    }

    fun sendFeedback(action: String) {
        viewModelScope.launch {
            when (val result = recipeRepository.feedback(recipeId, action)) {
                is ApiResult.Success -> {
                    val label = when (action) {
                        "favorite" -> "已收藏，之后会优先推荐类似的菜"
                        "cook" -> "已记录「做过」。系统会据此逐步了解你的口味"
                        "skip" -> "已记录「不想吃」。连续跳过同一道菜会把它排除"
                        else -> "已记录"
                    }
                    _state.update { it.copy(feedbackSent = label, message = label) }
                    refreshBus.notify(DataRefreshBus.Topic.RECIPES)
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(error = result.message) }
            }
        }
    }

    fun clearMessages() = _state.update { it.copy(error = null, message = null) }
}
