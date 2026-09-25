package com.fridgeprophet.app.ui.screens.fridge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fridgeprophet.app.core.ApiResult
import com.fridgeprophet.app.core.DataRefreshBus
import com.fridgeprophet.app.data.remote.dto.InventoryCreate
import com.fridgeprophet.app.data.remote.dto.InventoryOut
import com.fridgeprophet.app.data.remote.dto.InventoryUpdate
import com.fridgeprophet.app.data.repository.InventoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class FridgeUiState(
    val loading: Boolean = true,
    val items: List<InventoryOut> = emptyList(),
    val locationFilter: String? = null,
    val keyword: String = "",
    val error: String? = null,
    val busy: Boolean = false,
)

@HiltViewModel
class FridgeViewModel @Inject constructor(
    private val repository: InventoryRepository,
    private val refreshBus: DataRefreshBus,
) : ViewModel() {

    private val _state = MutableStateFlow(FridgeUiState())
    val state: StateFlow<FridgeUiState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch {
            refreshBus.events.collect { topic ->
                if (topic == DataRefreshBus.Topic.ALL ||
                    topic == DataRefreshBus.Topic.INVENTORY
                ) {
                    load(silent = true)
                }
            }
        }
    }

    fun load(silent: Boolean = false) {
        if (!silent) _state.update { it.copy(loading = true, error = null) }
        val s = _state.value

        viewModelScope.launch {
            when (val result = repository.list(
                storageLocation = s.locationFilter,
                keyword = s.keyword.ifBlank { null },
            )) {
                is ApiResult.Success ->
                    _state.update { it.copy(loading = false, items = result.data, error = null) }
                is ApiResult.Failure ->
                    _state.update { it.copy(loading = false, error = result.message) }
            }
        }
    }

    fun setLocationFilter(location: String?) {
        _state.update { it.copy(locationFilter = location) }
        load()
    }

    fun setKeyword(keyword: String) {
        _state.update { it.copy(keyword = keyword) }
        load()
    }

    /**
     * 手动添加食材。
     *
     * 购买日期和保质期**都是可选的**，不是必填：
     *   - purchaseDate 留空 → 后端按今天算
     *   - shelfLifeDays 留空 → 后端按食材名查默认保质期（比如叶菜 3 天、冷冻肉 90 天）
     * 用户只知道「这盒牛奶买了 3 天了」时，就填购买日期、不填保质期，
     * 后端会拿 3 天前的日期 + 默认保质期算出过期日。
     */
    fun addItem(
        name: String,
        quantity: Double,
        unit: String,
        location: String,
        purchaseDate: LocalDate? = null,
        shelfLifeDays: Int? = null,
    ) {
        if (name.isBlank()) {
            _state.update { it.copy(error = "请填写食材名称") }
            return
        }
        _state.update { it.copy(busy = true) }

        viewModelScope.launch {
            val result = repository.add(
                InventoryCreate(
                    foodName = name.trim(),
                    quantity = quantity,
                    unit = unit,
                    purchaseDate = purchaseDate?.toString(),
                    shelfLifeDays = shelfLifeDays,
                    storageLocation = location,
                )
            )
            _state.update { it.copy(busy = false) }
            when (result) {
                is ApiResult.Success -> {
                    refreshBus.notify(DataRefreshBus.Topic.INVENTORY)
                    load(silent = true)
                }
                is ApiResult.Failure -> _state.update { it.copy(error = result.message) }
            }
        }
    }

    fun updateQuantity(item: InventoryOut, newQuantity: Double) {
        if (newQuantity < 0) return
        _state.update { it.copy(busy = true) }

        viewModelScope.launch {
            val result = repository.update(item.id, InventoryUpdate(quantity = newQuantity))
            _state.update { it.copy(busy = false) }
            when (result) {
                is ApiResult.Success -> load(silent = true)
                is ApiResult.Failure -> _state.update { it.copy(error = result.message) }
            }
        }
    }

    /**
     * 修改已有食材：数量 + 购买日期 + 过期日期。
     *
     * 日期传 null 表示**清空这一项**（后端会把字段置空），
     * 所以调用方要传用户真正想要的最终状态，而不是「不改的项」。
     */
    fun updateItem(
        item: InventoryOut,
        quantity: Double,
        purchaseDate: LocalDate?,
        expiryDate: LocalDate?,
    ) {
        if (quantity < 0) return
        if (purchaseDate != null && expiryDate != null && expiryDate < purchaseDate) {
            _state.update { it.copy(error = "过期日期不能早于购买日期") }
            return
        }
        _state.update { it.copy(busy = true) }

        viewModelScope.launch {
            val result = repository.update(
                item.id,
                InventoryUpdate(
                    quantity = quantity,
                    purchaseDate = purchaseDate?.toString(),
                    expiryDate = expiryDate?.toString(),
                ),
            )
            _state.update { it.copy(busy = false) }
            when (result) {
                is ApiResult.Success -> {
                    refreshBus.notify(DataRefreshBus.Topic.INVENTORY)
                    load(silent = true)
                }
                is ApiResult.Failure -> _state.update { it.copy(error = result.message) }
            }
        }
    }

    fun delete(item: InventoryOut) {
        _state.update { it.copy(busy = true) }

        viewModelScope.launch {
            val result = repository.remove(item.id)
            _state.update { it.copy(busy = false) }
            when (result) {
                is ApiResult.Success -> {
                    refreshBus.notify(DataRefreshBus.Topic.INVENTORY)
                    load(silent = true)
                }
                is ApiResult.Failure -> _state.update { it.copy(error = result.message) }
            }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }
}
