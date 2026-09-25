package com.fridgeprophet.app.ui.screens.splash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fridgeprophet.app.core.ApiResult
import com.fridgeprophet.app.data.repository.AuthRepository
import com.fridgeprophet.app.data.repository.ProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 启动时的三种去向 */
enum class SplashDestination { UNDECIDED, LOGIN, ONBOARDING, MAIN }

@HiltViewModel
class SplashViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    private val _destination = MutableStateFlow(SplashDestination.UNDECIDED)
    val destination: StateFlow<SplashDestination> = _destination.asStateFlow()

    init {
        viewModelScope.launch {
            if (!authRepository.hasToken()) {
                _destination.value = SplashDestination.LOGIN
                return@launch
            }
            when (val result = profileRepository.getProfile()) {
                is ApiResult.Success ->
                    _destination.value = if (result.data.preference.onboarded) {
                        SplashDestination.MAIN
                    } else {
                        SplashDestination.ONBOARDING
                    }
                // 令牌失效或网络不通，都退回登录页，避免卡在启动页
                is ApiResult.Failure -> _destination.value = SplashDestination.LOGIN
            }
        }
    }
}

@Composable
fun SplashScreen(
    onGoLogin: () -> Unit,
    onGoMain: () -> Unit,
    onGoOnboarding: () -> Unit,
    viewModel: SplashViewModel = hiltViewModel(),
) {
    val destination by viewModel.destination.collectAsStateWithLifecycle()

    LaunchedEffect(destination) {
        when (destination) {
            SplashDestination.LOGIN -> onGoLogin()
            SplashDestination.MAIN -> onGoMain()
            SplashDestination.ONBOARDING -> onGoOnboarding()
            SplashDestination.UNDECIDED -> Unit
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Text(
                text = "冰箱先知",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "拍一张冰箱，今天吃什么就知道了",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(top = 16.dp)
                    .size(28.dp),
                strokeWidth = 3.dp,
            )
        }
    }
}
