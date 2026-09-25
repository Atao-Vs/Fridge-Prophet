package com.fridgeprophet.app.di

import android.content.Context
import com.fridgeprophet.app.core.SessionEvents
import com.fridgeprophet.app.core.TokenStore
import com.fridgeprophet.app.data.remote.ApiClient
import com.fridgeprophet.app.data.remote.FridgeApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideTokenStore(@ApplicationContext context: Context): TokenStore = TokenStore(context)

    @Provides
    @Singleton
    fun provideFridgeApi(
        tokenStore: TokenStore,
        sessionEvents: SessionEvents,
    ): FridgeApi {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return ApiClient.create(
            tokenStore = tokenStore,
            onUnauthorized = {
                // 令牌失效：清本地登录态 + 通知导航跳登录页
                scope.launch { tokenStore.clear() }
                sessionEvents.notifyExpired()
            },
        )
    }
}
