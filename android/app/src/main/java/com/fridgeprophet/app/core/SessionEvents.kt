package com.fridgeprophet.app.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 登录态失效事件。
 *
 * 当任意接口返回 401 时，拦截器发出这个事件，
 * 上层导航监听到就跳回登录页——不用每个界面各写一遍判断。
 */
@Singleton
class SessionEvents @Inject constructor() {

    private val _expired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val expired: SharedFlow<Unit> = _expired

    fun notifyExpired() {
        _expired.tryEmit(Unit)
    }
}
