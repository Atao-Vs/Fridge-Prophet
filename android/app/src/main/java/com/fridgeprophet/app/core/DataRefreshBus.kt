package com.fridgeprophet.app.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 跨页面的数据刷新信号。
 *
 * 场景：用户在「扫描」页确认食材入库后回到首页，首页必须重新拉库存。
 * 如果每个页面各自在 onResume 里无条件刷新，会造成大量无谓请求；
 * 用这个总线按主题通知，谁关心谁刷新。
 */
@Singleton
class DataRefreshBus @Inject constructor() {

    object Topic {
        const val INVENTORY = "inventory"
        const val RECIPES = "recipes"
        const val SHOPPING = "shopping"
        /** 广场动态被发布/删除/编辑后，信息流要重新拉 */
        const val SOCIAL = "social"
        const val ALL = "all"
    }

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events

    fun notify(topic: String = Topic.ALL) {
        _events.tryEmit(topic)
    }
}
