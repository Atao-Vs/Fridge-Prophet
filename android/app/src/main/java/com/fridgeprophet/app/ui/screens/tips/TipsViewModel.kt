package com.fridgeprophet.app.ui.screens.tips

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fridgeprophet.app.core.ApiResult
import com.fridgeprophet.app.data.remote.dto.FoodTipDetail
import com.fridgeprophet.app.data.remote.dto.FoodTipSummary
import com.fridgeprophet.app.data.repository.TipsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TipsUiState(
    val loading: Boolean = true,
    val items: List<FoodTipSummary> = emptyList(),
    val categories: List<String> = emptyList(),
    val verdicts: List<String> = emptyList(),
    /** 当前选中的分类，null 表示「全部」 */
    val category: String? = null,
    /** 当前选中的结论，null 表示「全部」 */
    val verdict: String? = null,
    val error: String? = null,
)

@HiltViewModel
class TipsViewModel @Inject constructor(
    private val tipsRepository: TipsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(TipsUiState())
    val state: StateFlow<TipsUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = tipsRepository.list(
                category = _state.value.category,
                verdict = _state.value.verdict,
            )
            when (result) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        loading = false,
                        items = result.data.items,
                        // 分类/结论清单用**不带筛选**的那次结果填充，
                        // 否则筛完之后标签栏会越筛越少
                        categories = result.data.categories.ifEmpty { it.categories },
                        verdicts = result.data.verdicts.ifEmpty { it.verdicts },
                    )
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(loading = false, error = result.message) }
            }
        }
    }

    fun selectCategory(category: String?) {
        _state.update { it.copy(category = if (it.category == category) null else category) }
        load()
    }

    fun selectVerdict(verdict: String?) {
        _state.update { it.copy(verdict = if (it.verdict == verdict) null else verdict) }
        load()
    }
}

data class TipDetailUiState(
    val loading: Boolean = true,
    val tip: FoodTipDetail? = null,
    val error: String? = null,
)

@HiltViewModel
class TipDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val tipsRepository: TipsRepository,
) : ViewModel() {

    // 贴士 id 是字符串（如 crab-with-tomato），不是数字
    private val tipId: String = savedStateHandle.get<String>("tipId").orEmpty()

    private val _state = MutableStateFlow(TipDetailUiState())
    val state: StateFlow<TipDetailUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        if (tipId.isBlank()) {
            _state.update { it.copy(loading = false, error = "贴士 ID 无效") }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = tipsRepository.detail(tipId)) {
                is ApiResult.Success ->
                    _state.update { it.copy(loading = false, tip = result.data) }
                is ApiResult.Failure ->
                    _state.update { it.copy(loading = false, error = result.message) }
            }
        }
    }
}
