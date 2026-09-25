package com.fridgeprophet.app.ui.screens.recipes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fridgeprophet.app.core.ApiResult
import com.fridgeprophet.app.core.DataRefreshBus
import com.fridgeprophet.app.data.remote.dto.RecipeOut
import com.fridgeprophet.app.data.repository.RecipeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RecipesUiState(
    val loading: Boolean = true,
    val recipes: List<RecipeOut> = emptyList(),
    val generating: Boolean = false,
    val error: String? = null,
    val info: String? = null,
    /** 快捷筛选，见 filters */
    val filter: String = "全部",
)

@HiltViewModel
class RecipesViewModel @Inject constructor(
    private val repository: RecipeRepository,
    private val refreshBus: DataRefreshBus,
) : ViewModel() {

    private val _state = MutableStateFlow(RecipesUiState())
    val state: StateFlow<RecipesUiState> = _state.asStateFlow()

    val filters = listOf("全部", "待采购", "现在能做", "15 分钟内", "消耗临期")

    init {
        load()
        viewModelScope.launch {
            refreshBus.events.collect { topic ->
                if (topic == DataRefreshBus.Topic.ALL || topic == DataRefreshBus.Topic.RECIPES) {
                    load(silent = true)
                }
            }
        }
    }

    fun load(silent: Boolean = false) {
        if (!silent) _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.list()) {
                is ApiResult.Success ->
                    _state.update { it.copy(loading = false, recipes = result.data, error = null) }
                is ApiResult.Failure ->
                    _state.update { it.copy(loading = false, error = result.message) }
            }
        }
    }

    fun setFilter(filter: String) = _state.update { it.copy(filter = filter) }

    fun generate() {
        _state.update { it.copy(generating = true, error = null, info = null) }
        viewModelScope.launch {
            when (val result = repository.generate(count = 3)) {
                is ApiResult.Success -> {
                    _state.update {
                        it.copy(
                            generating = false,
                            info = if (result.data.recipes.isEmpty()) {
                                "没有生成出菜谱。冰箱为空时无法推荐，先去扫描一次。"
                            } else {
                                "已生成 ${result.data.recipes.size} 道菜（模型：${result.data.model}）"
                            },
                        )
                    }
                    load(silent = true)
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(generating = false, error = result.message) }
            }
        }
    }

    fun delete(recipe: RecipeOut) {
        val id = recipe.id ?: return
        viewModelScope.launch {
            when (val result = repository.remove(id)) {
                is ApiResult.Success -> load(silent = true)
                is ApiResult.Failure -> _state.update { it.copy(error = result.message) }
            }
        }
    }

    fun clearMessages() = _state.update { it.copy(error = null, info = null) }

    /**
     * 按当前筛选条件过滤。
     *
     * ⚠️ 这里**刻意不排序**。曾经把「已备齐」的菜排到最后，理由是
     * 「买完写回冰箱后它不该继续占最显眼的位置」——但那是把需求理解偏了：
     * 用户要弱化的是**采购清单里已勾选的条目**，不是菜谱。
     * 菜谱列表一沉底 + 降透明度，看着像「这些菜失效了」，反而不敢点。
     *
     * 现在保持接口返回的原始顺序（后端按创建时间倒序，最新在前），
     * 用卡片上的「已备齐 / 缺 N 样」标签做区分就够了。
     */
    fun visibleRecipes(): List<RecipeOut> = when (_state.value.filter) {
        // 「待采购」= 还缺东西的
        "待采购" -> _state.value.recipes.filter { !it.ready }
        // 「现在能做」= 食材已备齐，打开冰箱就能开火
        "现在能做" -> _state.value.recipes.filter { it.ready }
        "15 分钟内" -> _state.value.recipes.filter { it.timeMinutes <= 15 }
        "消耗临期" -> _state.value.recipes.filter { it.usesExpiring.isNotEmpty() }
        else -> _state.value.recipes
    }
}
