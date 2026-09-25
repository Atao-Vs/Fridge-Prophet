package com.fridgeprophet.app.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fridgeprophet.app.core.ApiResult
import com.fridgeprophet.app.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuthUiState(
    val isRegisterMode: Boolean = false,
    val email: String = "",
    val password: String = "",
    val nickname: String = "",
    val loading: Boolean = false,
    val error: String? = null,
) {
    val canSubmit: Boolean
        get() = email.contains("@") && password.length >= 6 && !loading
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    fun toggleMode() {
        _state.update { it.copy(isRegisterMode = !it.isRegisterMode, error = null) }
    }

    fun onEmailChange(value: String) = _state.update { it.copy(email = value.trim(), error = null) }
    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }
    fun onNicknameChange(value: String) = _state.update { it.copy(nickname = value, error = null) }

    fun submit(onSuccess: (needsOnboarding: Boolean) -> Unit) {
        val current = _state.value
        if (!current.canSubmit) {
            _state.update { it.copy(error = "请填写邮箱和至少 6 位密码") }
            return
        }
        _state.update { it.copy(loading = true, error = null) }

        viewModelScope.launch {
            val result = if (current.isRegisterMode) {
                authRepository.register(current.email, current.password, current.nickname)
            } else {
                authRepository.login(current.email, current.password)
            }

            when (result) {
                is ApiResult.Success -> {
                    _state.update { it.copy(loading = false) }
                    onSuccess(!result.data.user.onboarded)
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(loading = false, error = result.message) }
            }
        }
    }
}
