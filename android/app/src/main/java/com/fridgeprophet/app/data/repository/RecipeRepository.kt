package com.fridgeprophet.app.data.repository

import com.fridgeprophet.app.core.ApiResult
import com.fridgeprophet.app.core.safeApiCall
import com.fridgeprophet.app.data.remote.FridgeApi
import com.fridgeprophet.app.data.remote.dto.MealActionRequest
import com.fridgeprophet.app.data.remote.dto.RecipeGenerateRequest
import com.fridgeprophet.app.data.remote.dto.RecipeGenerateResponse
import com.fridgeprophet.app.data.remote.dto.RecipeOut
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecipeRepository @Inject constructor(private val api: FridgeApi) {

    /** 核心接口：后端拿库存 + 画像去生成菜谱 */
    suspend fun generate(
        count: Int = 3,
        maxTimeMinutes: Int? = null,
        prioritizeExpiring: Boolean = true,
        extraNotes: String? = null,
    ): ApiResult<RecipeGenerateResponse> = safeApiCall {
        api.generateRecipes(
            RecipeGenerateRequest(
                count = count,
                maxTimeMinutes = maxTimeMinutes,
                prioritizeExpiring = prioritizeExpiring,
                extraNotes = extraNotes,
            )
        )
    }

    suspend fun list(limit: Int = 20): ApiResult<List<RecipeOut>> =
        safeApiCall { api.listRecipes(limit) }

    suspend fun detail(id: Int): ApiResult<RecipeOut> = safeApiCall { api.getRecipe(id) }

    suspend fun remove(id: Int): ApiResult<Unit> = safeApiCall { api.deleteRecipe(id); Unit }

    /** 上报收藏 / 做过 / 跳过 / 评分，用于逐步修正用户画像 */
    suspend fun feedback(recipeId: Int, action: String, rating: Int? = null): ApiResult<Unit> =
        safeApiCall { api.submitFeedback(MealActionRequest(recipeId, action, rating)); Unit }
}
