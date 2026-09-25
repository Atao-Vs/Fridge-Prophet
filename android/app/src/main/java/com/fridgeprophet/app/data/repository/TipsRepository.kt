package com.fridgeprophet.app.data.repository

import com.fridgeprophet.app.core.ApiResult
import com.fridgeprophet.app.core.safeApiCall
import com.fridgeprophet.app.data.remote.FridgeApi
import com.fridgeprophet.app.data.remote.dto.FoodTipDetail
import com.fridgeprophet.app.data.remote.dto.FoodTipList
import com.fridgeprophet.app.data.remote.dto.FoodTipRandom
import com.fridgeprophet.app.data.remote.dto.FoodTipSummary
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 食品安全小贴士。
 *
 * 这三个接口不需要登录，所以登录页也能用。
 *
 * 这里带了一层**内存缓存**：把最近一次成功拿到的贴士列表留住，
 * 网络断了 / 比赛现场没网时，首页标语位还能显示上次的内容，
 * 而不是整块消失。缓存只在内存里，进程重启就没了 —— 不需要持久化，
 * 因为它只是「锦上添花」，不是核心数据。
 */
@Singleton
class TipsRepository @Inject constructor(private val api: FridgeApi) {

    /** 最近一次成功拉到的完整列表，失败时用来兜底 */
    private var cachedList: List<FoodTipSummary> = emptyList()

    suspend fun list(
        category: String? = null,
        verdict: String? = null,
        limit: Int? = null,
    ): ApiResult<FoodTipList> {
        val result = safeApiCall { api.listTips(category, verdict, limit) }
        if (result is ApiResult.Success && category == null && verdict == null) {
            // 只在「不带筛选的全量请求」时更新缓存，避免筛选结果污染缓存
            cachedList = result.data.items
        }
        return result
    }

    /**
     * 随机取一条，给首页标语位用。
     *
     * `excludeId` 是当前正显示的那条 —— 首页每次被切进来都会调这个方法，
     * 传上一条的 id 才能保证「换了一条」，否则纯随机有 1/N 的概率抽到原样，
     * 用户看到的就是「切了半天没变」。
     *
     * 后端 `/tips/random` 挂了的时候不报错，而是从缓存里挑一条**不同的** ——
     * 标语位是装饰性的，不该因为网络问题给用户弹错误提示。
     */
    suspend fun random(count: Int = 1, excludeId: String? = null): ApiResult<FoodTipRandom> {
        val result = safeApiCall { api.randomTips(count, excludeId) }
        if (result is ApiResult.Success) {
            if (cachedList.isEmpty()) cachedList = result.data.items
            return result
        }
        if (cachedList.isNotEmpty()) {
            // 离线兜底也要避开上一条，否则断网时标语位会「卡住不动」
            val candidates = cachedList.filter { it.id != excludeId }
                .ifEmpty { cachedList }
            return ApiResult.Success(
                FoodTipRandom(count = 1, items = candidates.shuffled().take(1))
            )
        }
        return result
    }

    suspend fun detail(id: String): ApiResult<FoodTipDetail> = safeApiCall { api.getTip(id) }
}
