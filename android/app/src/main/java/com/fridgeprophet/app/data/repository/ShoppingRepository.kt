package com.fridgeprophet.app.data.repository

import com.fridgeprophet.app.core.ApiResult
import com.fridgeprophet.app.core.safeApiCall
import com.fridgeprophet.app.data.remote.FridgeApi
import com.fridgeprophet.app.data.remote.dto.ShoppingApplyRequest
import com.fridgeprophet.app.data.remote.dto.ShoppingBuildRequest
import com.fridgeprophet.app.data.remote.dto.ShoppingItemOut
import com.fridgeprophet.app.data.remote.dto.ShoppingItemUpdate
import com.fridgeprophet.app.data.remote.dto.ShoppingListOut
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShoppingRepository @Inject constructor(private val api: FridgeApi) {

    /** 后端算「需要 − 现有 = 缺多少」，自动合并重复食材 */
    suspend fun build(recipeIds: List<Int> = emptyList(), days: Int = 1, title: String? = null): ApiResult<ShoppingListOut> =
        safeApiCall { api.buildShoppingList(ShoppingBuildRequest(recipeIds, days, title)) }

    suspend fun list(): ApiResult<List<ShoppingListOut>> = safeApiCall { api.listShopping() }

    suspend fun updateItem(
        itemId: Int,
        quantity: Double? = null,
        checked: Boolean? = null,
    ): ApiResult<ShoppingItemOut> = safeApiCall {
        api.updateShoppingItem(itemId, ShoppingItemUpdate(quantity = quantity, checked = checked))
    }

    suspend fun deleteItem(itemId: Int): ApiResult<Unit> =
        safeApiCall { api.deleteShoppingItem(itemId); Unit }

    /** 买完了，一键写回冰箱库存 */
    suspend fun applyToList(listId: Int, onlyChecked: Boolean = true): ApiResult<ShoppingListOut> =
        safeApiCall { api.applyShoppingList(listId, ShoppingApplyRequest(onlyChecked = onlyChecked)) }

    suspend fun deleteList(listId: Int): ApiResult<Unit> =
        safeApiCall { api.deleteShoppingList(listId); Unit }
}
