package com.fridgeprophet.app.ui.screens.shopping

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fridgeprophet.app.core.ApiResult
import com.fridgeprophet.app.core.DataRefreshBus
import com.fridgeprophet.app.data.remote.dto.RecipeOut
import com.fridgeprophet.app.data.remote.dto.ShoppingItemOut
import com.fridgeprophet.app.data.remote.dto.ShoppingListOut
import com.fridgeprophet.app.data.repository.RecipeRepository
import com.fridgeprophet.app.data.repository.ShoppingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ShoppingUiState(
    val loading: Boolean = true,
    val lists: List<ShoppingListOut> = emptyList(),
    /** 供「按菜谱生成清单」时挑选 */
    val recipes: List<RecipeOut> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

@HiltViewModel
class ShoppingViewModel @Inject constructor(
    private val shoppingRepository: ShoppingRepository,
    private val recipeRepository: RecipeRepository,
    private val refreshBus: DataRefreshBus,
) : ViewModel() {

    private val _state = MutableStateFlow(ShoppingUiState())
    val state: StateFlow<ShoppingUiState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch {
            refreshBus.events.collect { topic ->
                if (topic == DataRefreshBus.Topic.ALL || topic == DataRefreshBus.Topic.SHOPPING) {
                    load(silent = true)
                }
            }
        }
    }

    fun load(silent: Boolean = false) {
        if (!silent) _state.update { it.copy(loading = true, error = null) }

        viewModelScope.launch {
            val listsResult = shoppingRepository.list()
            // 菜谱列表只为「新建清单」服务，拉失败不影响主流程
            val recipesResult = recipeRepository.list(limit = 30)

            val failure = listsResult as? ApiResult.Failure

            _state.update {
                it.copy(
                    loading = false,
                    lists = (listsResult as? ApiResult.Success)?.data ?: it.lists,
                    recipes = (recipesResult as? ApiResult.Success)?.data ?: it.recipes,
                    error = failure?.message,
                )
            }
        }
    }

    /**
     * 勾选 / 取消勾选。
     *
     * 这里做乐观更新：先在本地翻转，再发请求。勾选是个高频动作，
     * 每次都等一个网络往返会让界面发顿；失败了再拉一次回滚。
     */
    fun toggleItem(item: ShoppingItemOut) {
        val target = !item.checked
        patchItem(item.id) { it.copy(checked = target) }

        viewModelScope.launch {
            when (val result = shoppingRepository.updateItem(item.id, checked = target)) {
                is ApiResult.Success -> Unit
                is ApiResult.Failure -> {
                    _state.update { it.copy(error = result.message) }
                    load(silent = true)
                }
            }
        }
    }

    fun updateItemQuantity(item: ShoppingItemOut, quantity: Double) {
        if (quantity <= 0) return
        patchItem(item.id) { it.copy(quantity = quantity) }

        viewModelScope.launch {
            when (val result = shoppingRepository.updateItem(item.id, quantity = quantity)) {
                is ApiResult.Success -> Unit
                is ApiResult.Failure -> {
                    _state.update { it.copy(error = result.message) }
                    load(silent = true)
                }
            }
        }
    }

    fun deleteItem(item: ShoppingItemOut) {
        _state.update { st ->
            st.copy(lists = st.lists.map { l ->
                l.copy(items = l.items.filterNot { it.id == item.id })
            })
        }

        viewModelScope.launch {
            when (val result = shoppingRepository.deleteItem(item.id)) {
                is ApiResult.Success -> {
                    refreshBus.notify(DataRefreshBus.Topic.SHOPPING)
                    load(silent = true)
                }
                is ApiResult.Failure -> {
                    _state.update { it.copy(error = result.message) }
                    load(silent = true)
                }
            }
        }
    }

    /** 买完了：把清单里的东西写回冰箱库存，同时更新保质期 */
    fun applyToList(list: ShoppingListOut, onlyChecked: Boolean = true) {
        _state.update { it.copy(busy = true, error = null, message = null) }

        viewModelScope.launch {
            when (val result = shoppingRepository.applyToList(list.id, onlyChecked)) {
                is ApiResult.Success -> {
                    val applied = result.data.items.count { it.appliedToInventory }
                    _state.update {
                        it.copy(
                            busy = false,
                            message = if (applied == 0) {
                                "没有可入库的条目。先勾选你买到的东西，再点一次"
                            } else {
                                "已把 $applied 样食材写回冰箱，保质期按默认天数估算"
                            },
                        )
                    }
                    refreshBus.notify(DataRefreshBus.Topic.INVENTORY)
                    refreshBus.notify(DataRefreshBus.Topic.SHOPPING)
                    load(silent = true)
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(busy = false, error = result.message) }
            }
        }
    }

    fun deleteList(list: ShoppingListOut) {
        _state.update { it.copy(busy = true) }

        viewModelScope.launch {
            when (val result = shoppingRepository.deleteList(list.id)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(busy = false) }
                    refreshBus.notify(DataRefreshBus.Topic.SHOPPING)
                    load(silent = true)
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(busy = false, error = result.message) }
            }
        }
    }

    /**
     * 按选中的菜谱生成清单。
     *
     * 「需要多少 − 冰箱里有多少 = 要买多少」这个差集完全由后端算，
     * 客户端只负责把菜谱 id 传过去。这是刻意的：算术不该经过模型。
     */
    fun buildFrom(recipeIds: List<Int>, title: String?) {
        if (recipeIds.isEmpty()) {
            _state.update { it.copy(error = "先选至少一道菜") }
            return
        }
        _state.update { it.copy(busy = true, error = null, message = null) }

        viewModelScope.launch {
            when (val result = shoppingRepository.build(
                recipeIds = recipeIds,
                title = title ?: "按 ${recipeIds.size} 道菜采购",
            )) {
                is ApiResult.Success -> {
                    val count = result.data.items.size
                    _state.update {
                        it.copy(
                            busy = false,
                            message = if (count == 0) {
                                "这些菜的食材你冰箱里都有了，不用买"
                            } else {
                                "已生成清单：${count} 项，预计 ¥%.1f".format(result.data.estimatedTotal)
                            },
                        )
                    }
                    refreshBus.notify(DataRefreshBus.Topic.SHOPPING)
                    load(silent = true)
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(busy = false, error = result.message) }
            }
        }
    }

    fun clearMessages() = _state.update { it.copy(error = null, message = null) }

    /** 在本地把所有清单里 id 匹配的条目改掉（乐观更新的落地） */
    private fun patchItem(itemId: Int, transform: (ShoppingItemOut) -> ShoppingItemOut) {
        _state.update { st ->
            st.copy(lists = st.lists.map { list ->
                list.copy(items = list.items.map { if (it.id == itemId) transform(it) else it })
            })
        }
    }
}
