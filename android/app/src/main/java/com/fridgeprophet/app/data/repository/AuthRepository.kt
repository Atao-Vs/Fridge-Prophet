package com.fridgeprophet.app.data.repository

import com.fridgeprophet.app.core.ApiResult
import com.fridgeprophet.app.core.TokenStore
import com.fridgeprophet.app.core.safeApiCall
import com.fridgeprophet.app.data.remote.FridgeApi
import com.fridgeprophet.app.data.remote.dto.LoginRequest
import com.fridgeprophet.app.data.remote.dto.RegisterRequest
import com.fridgeprophet.app.data.remote.dto.TokenResponse
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val api: FridgeApi,
    private val tokenStore: TokenStore,
) {

    suspend fun register(email: String, password: String, nickname: String): ApiResult<TokenResponse> {
        val result = safeApiCall { api.register(RegisterRequest(email, password, nickname)) }
        if (result is ApiResult.Success) {
            tokenStore.save(
                token = result.data.accessToken,
                email = result.data.user.email,
                nickname = result.data.user.nickname,
                onboarded = result.data.user.onboarded,
            )
        }
        return result
    }

    suspend fun login(email: String, password: String): ApiResult<TokenResponse> {
        val result = safeApiCall { api.login(LoginRequest(email, password)) }
        if (result is ApiResult.Success) {
            tokenStore.save(
                token = result.data.accessToken,
                email = result.data.user.email,
                nickname = result.data.user.nickname,
                onboarded = result.data.user.onboarded,
            )
        }
        return result
    }

    suspend fun logout() {
        tokenStore.clear()
    }

    /** 启动时判断该去登录页还是首页 */
    suspend fun hasToken(): Boolean = !tokenStore.tokenBlocking().isNullOrBlank()
}
