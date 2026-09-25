package com.fridgeprophet.app.ui.screens.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fridgeprophet.app.core.ApiResult
import com.fridgeprophet.app.core.DataRefreshBus
import com.fridgeprophet.app.core.TokenStore
import com.fridgeprophet.app.data.remote.dto.FamilyMemberIn
import com.fridgeprophet.app.data.remote.dto.HealthPreferenceIn
import com.fridgeprophet.app.data.remote.dto.PreferenceInsights
import com.fridgeprophet.app.data.remote.dto.PrivacySettingIn
import com.fridgeprophet.app.data.remote.dto.PrivacySettingOut
import com.fridgeprophet.app.data.remote.dto.ProfileOut
import com.fridgeprophet.app.data.remote.dto.UserOptions
import com.fridgeprophet.app.data.repository.ProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class ProfileUiState(
    val loading: Boolean = true,
    val profile: ProfileOut? = null,
    val insights: PreferenceInsights? = null,
    /** 过敏原 / 忌口的可选项清单，从后端拉，用于编辑画像时的快捷标签 */
    val options: UserOptions? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val tokenStore: TokenStore,
    private val refreshBus: DataRefreshBus,
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    init {
        load()
        loadOptions()
    }

    /**
     * 拉取可选项清单。
     *
     * 拿不到就不显示快捷标签、退回纯手动输入 —— 不报错，
     * 因为这只是「省打字」的辅助功能，不该拦住用户填画像。
     */
    fun loadOptions() {
        viewModelScope.launch {
            val result = profileRepository.getOptions()
            if (result is ApiResult.Success) {
                _state.update { it.copy(options = result.data) }
            }
        }
    }

    fun load(silent: Boolean = false) {
        if (!silent) _state.update { it.copy(loading = true, error = null) }

        viewModelScope.launch {
            val profileResult = profileRepository.getProfile()
            val insightsResult = profileRepository.preferenceInsights()

            val failure = profileResult as? ApiResult.Failure

            _state.update {
                it.copy(
                    loading = false,
                    profile = (profileResult as? ApiResult.Success)?.data ?: it.profile,
                    insights = (insightsResult as? ApiResult.Success)?.data ?: it.insights,
                    error = failure?.message,
                )
            }
        }
    }

    fun updateNickname(nickname: String) {
        if (nickname.isBlank()) {
            _state.update { it.copy(error = "昵称不能为空") }
            return
        }
        _state.update { it.copy(busy = true, error = null, message = null) }

        viewModelScope.launch {
            when (val result = profileRepository.updateNickname(nickname.trim())) {
                is ApiResult.Success -> {
                    _state.update {
                        it.copy(busy = false, message = "昵称已更新")
                    }
                    load(silent = true)
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(busy = false, error = result.message) }
            }
        }
    }

    /**
     * 上传头像。
     *
     * 文件转换（Uri → 临时 File）在界面层做，因为 ViewModel 不该持有 Context。
     * 这里只负责把 File 交给仓库层。
     */
    fun updateAvatar(file: File, mimeType: String) {
        _state.update { it.copy(busy = true, error = null, message = null) }

        viewModelScope.launch {
            when (val result = profileRepository.uploadAvatar(file, mimeType)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(busy = false, message = "头像已更新") }
                    load(silent = true)
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(busy = false, error = result.message) }
            }
            // 临时文件用完就删，别占用户存储
            runCatching { file.delete() }
        }
    }

    /**
     * 修改个性简介。
     *
     * 注意这里**不做非空校验**：简介本来就允许留空，
     * 传空字符串表示「清空」，后端会把它存成 null。
     */
    fun updateBio(bio: String) {
        _state.update { it.copy(busy = true, error = null, message = null) }

        viewModelScope.launch {
            when (val result = profileRepository.updateBio(bio.trim())) {
                is ApiResult.Success -> {
                    _state.update {
                        it.copy(
                            busy = false,
                            message = if (bio.isBlank()) "简介已清空" else "简介已更新",
                        )
                    }
                    load(silent = true)
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(busy = false, error = result.message) }
            }
        }
    }

    fun savePreference(
        cuisine: String,
        taste: String,
        cookTimeMax: Int,
        dietGoal: String,
        dislikedFoods: List<String>,
        allergies: List<String>,
    ) {
        _state.update { it.copy(busy = true, error = null, message = null) }

        viewModelScope.launch {
            when (val result = profileRepository.savePreference(
                cuisine = cuisine,
                taste = taste,
                cookTimeMax = cookTimeMax,
                dietGoal = dietGoal,
                dislikedFoods = dislikedFoods,
                allergies = allergies,
            )) {
                is ApiResult.Success -> {
                    _state.update { it.copy(busy = false, message = "偏好已保存，下次生成菜谱会按新偏好来") }
                    refreshBus.notify(DataRefreshBus.Topic.RECIPES)
                    load(silent = true)
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(busy = false, error = result.message) }
            }
        }
    }

    fun saveHealth(body: HealthPreferenceIn) {
        _state.update { it.copy(busy = true, error = null, message = null) }

        viewModelScope.launch {
            when (val result = profileRepository.saveHealth(body)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(busy = false, message = "健康偏好已保存") }
                    refreshBus.notify(DataRefreshBus.Topic.RECIPES)
                    load(silent = true)
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(busy = false, error = result.message) }
            }
        }
    }

    /**
     * 切换「我的主页在广场里公开什么」。
     *
     * ⚠️ 提交的是**整个对象**，不是被改的那一项。
     * 后端 PUT 是全量覆盖（`for field, value in payload`），
     * 如果只传改动的字段，其余字段会按默认值 false 被写回去，
     * 表现就是「我打开了 A，B 自己关了」。
     * 这和健康偏好那边是同一个坑，所以调用方要用 `toInput()` 带上全部 5 项。
     */
    fun savePrivacy(body: PrivacySettingIn) {
        _state.update { it.copy(busy = true, error = null, message = null) }

        viewModelScope.launch {
            when (val result = profileRepository.savePrivacy(body)) {
                is ApiResult.Success -> {
                    // 用返回值就地更新，而不是再 load() 一次：
                    // 重新拉整个画像要等一次网络往返，开关会先回弹再跳过去，很难看。
                    _state.update { st ->
                        st.copy(
                            busy = false,
                            profile = st.profile?.copy(privacy = result.data),
                            message = privacyMessage(result.data),
                        )
                    }
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(busy = false, error = result.message) }
            }
        }
    }

    fun addFamily(body: FamilyMemberIn) {
        if (body.name.isBlank()) {
            _state.update { it.copy(error = "请填写称呼") }
            return
        }
        _state.update { it.copy(busy = true, error = null, message = null) }

        viewModelScope.launch {
            when (val result = profileRepository.addFamily(body)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(busy = false, message = "已添加家庭成员") }
                    load(silent = true)
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(busy = false, error = result.message) }
            }
        }
    }

    fun deleteFamily(id: Int) {
        _state.update { it.copy(busy = true) }

        viewModelScope.launch {
            when (val result = profileRepository.deleteFamily(id)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(busy = false) }
                    load(silent = true)
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(busy = false, error = result.message) }
            }
        }
    }

    fun clearMessages() = _state.update { it.copy(error = null, message = null) }

    /** 退出登录：清掉本地令牌再交给上层做导航 */
    fun logout(onDone: () -> Unit) {
        viewModelScope.launch {
            tokenStore.clear()
            onDone()
        }
    }
}

/**
 * 把读出来的设置原样转成提交用的对象。
 *
 * 存在的意义是**避免手写 5 个字段**：以后新增第 6 个开关时，
 * 忘了在这里补一行，就会重演「改了 A，B 自己关了」那个 bug。
 * 用这个函数，新增字段只需要改 DTO，调用点不用动。
 */
fun PrivacySettingOut.toInput(
    sharePreference: Boolean = this.sharePreference,
    shareHealth: Boolean = this.shareHealth,
    shareBody: Boolean = this.shareBody,
    shareFamily: Boolean = this.shareFamily,
    shareStats: Boolean = this.shareStats,
): PrivacySettingIn = PrivacySettingIn(
    sharePreference = sharePreference,
    shareHealth = shareHealth,
    shareBody = shareBody,
    shareFamily = shareFamily,
    shareStats = shareStats,
)

/** 开关变动后给一句人话反馈，而不是干巴巴的「已保存」。 */
private fun privacyMessage(setting: PrivacySettingOut): String {
    val opened = buildList {
        if (setting.sharePreference) add("饮食偏好")
        if (setting.shareHealth) add("健康偏好")
        if (setting.shareBody) add("身体数据")
        if (setting.shareFamily) add("家庭成员")
        if (setting.shareStats) add("做菜统计")
    }
    return if (opened.isEmpty()) {
        "已全部设为不公开，广场上别人看不到你的这些信息"
    } else {
        "已公开：${opened.joinToString("、")}"
    }
}
