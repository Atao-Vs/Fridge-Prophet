package com.fridgeprophet.app.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fridgeprophet.app.core.ApiResult
import com.fridgeprophet.app.data.repository.ProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OnboardingUiState(
    val step: Int = 0,
    val cuisine: String = "家常菜",
    val taste: String = "正常",
    val cookTimeMax: Int = 30,
    val dietGoal: String = "正常饮食",
    val dislikedFoods: Set<String> = emptySet(),
    val allergies: Set<String> = emptySet(),
    // 健康管理模式（不做疾病诊断，只做饮食约束）
    val lowCarb: Boolean = false,
    val lowSodium: Boolean = false,
    val lowFat: Boolean = false,
    val highProtein: Boolean = false,
    val highFiber: Boolean = false,
    val vegetarian: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
)

object OnboardingOptions {
    val cuisines = listOf("家常菜", "中餐", "西餐", "日料", "都可以")
    val tastes = listOf("清淡", "正常", "重口", "辣")
    val cookTimes = listOf(10 to "10 分钟以内", 20 to "10-20 分钟", 40 to "20-40 分钟", 90 to "40 分钟以上")
    val dietGoals = listOf("正常饮食", "控制体重", "增肌", "高蛋白", "低盐", "低糖/控制碳水", "素食")
    val disliked = listOf("香菜", "芹菜", "辣椒", "葱", "姜", "蒜", "胡萝卜", "苦瓜")
    val allergens = listOf("海鲜", "花生", "坚果", "乳制品", "鸡蛋", "麸质", "大豆", "牛肉")

    const val TOTAL_STEPS = 5
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    fun next() = _state.update { it.copy(step = (it.step + 1).coerceAtMost(OnboardingOptions.TOTAL_STEPS - 1)) }
    fun back() = _state.update { it.copy(step = (it.step - 1).coerceAtLeast(0)) }

    fun setCuisine(v: String) = _state.update { it.copy(cuisine = v) }
    fun setTaste(v: String) = _state.update { it.copy(taste = v) }
    fun setCookTime(v: Int) = _state.update { it.copy(cookTimeMax = v) }
    fun setDietGoal(v: String) = _state.update { it.copy(dietGoal = v) }

    fun toggleDisliked(v: String) = _state.update {
        it.copy(dislikedFoods = if (v in it.dislikedFoods) it.dislikedFoods - v else it.dislikedFoods + v)
    }

    fun toggleAllergy(v: String) = _state.update {
        it.copy(allergies = if (v in it.allergies) it.allergies - v else it.allergies + v)
    }

    fun setHealth(
        lowCarb: Boolean = _state.value.lowCarb,
        lowSodium: Boolean = _state.value.lowSodium,
        lowFat: Boolean = _state.value.lowFat,
        highProtein: Boolean = _state.value.highProtein,
        highFiber: Boolean = _state.value.highFiber,
        vegetarian: Boolean = _state.value.vegetarian,
    ) = _state.update {
        it.copy(
            lowCarb = lowCarb,
            lowSodium = lowSodium,
            lowFat = lowFat,
            highProtein = highProtein,
            highFiber = highFiber,
            vegetarian = vegetarian,
        )
    }

    /** 先存饮食偏好，再存健康设置。两步都成功才算完成。 */
    fun save(onDone: () -> Unit) {
        val s = _state.value
        _state.update { it.copy(saving = true, error = null) }

        viewModelScope.launch {
            val prefResult = profileRepository.savePreference(
                cuisine = s.cuisine,
                taste = s.taste,
                cookTimeMax = s.cookTimeMax,
                dietGoal = s.dietGoal,
                dislikedFoods = s.dislikedFoods.toList(),
                allergies = s.allergies.toList(),
            )
            if (prefResult is ApiResult.Failure) {
                _state.update { it.copy(saving = false, error = prefResult.message) }
                return@launch
            }

            val healthResult = profileRepository.saveHealth(
                com.fridgeprophet.app.data.remote.dto.HealthPreferenceIn(
                    lowCarb = s.lowCarb,
                    lowSodium = s.lowSodium,
                    lowFat = s.lowFat,
                    highProtein = s.highProtein,
                    highFiber = s.highFiber,
                    vegetarian = s.vegetarian,
                )
            )
            when (healthResult) {
                is ApiResult.Success -> {
                    _state.update { it.copy(saving = false) }
                    onDone()
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(saving = false, error = healthResult.message) }
            }
        }
    }
}
