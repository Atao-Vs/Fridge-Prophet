package com.fridgeprophet.app.data.repository

import com.fridgeprophet.app.core.ApiResult
import com.fridgeprophet.app.core.safeApiCall
import com.fridgeprophet.app.data.remote.FridgeApi
import com.fridgeprophet.app.data.remote.dto.FamilyMemberIn
import com.fridgeprophet.app.data.remote.dto.FamilyMemberOut
import com.fridgeprophet.app.data.remote.dto.HealthPreferenceIn
import com.fridgeprophet.app.data.remote.dto.HealthPreferenceOut
import com.fridgeprophet.app.data.remote.dto.PreferenceInsights
import com.fridgeprophet.app.data.remote.dto.PrivacySettingIn
import com.fridgeprophet.app.data.remote.dto.PrivacySettingOut
import com.fridgeprophet.app.data.remote.dto.ProfileOut
import com.fridgeprophet.app.data.remote.dto.ProfileUpdate
import com.fridgeprophet.app.data.remote.dto.UserOut
import com.fridgeprophet.app.data.remote.dto.UserOptions
import com.fridgeprophet.app.data.remote.dto.UserPreferenceIn
import com.fridgeprophet.app.data.remote.dto.UserPreferenceOut
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileRepository @Inject constructor(private val api: FridgeApi) {

    suspend fun getProfile(): ApiResult<ProfileOut> = safeApiCall { api.getProfile() }

    suspend fun updateNickname(nickname: String): ApiResult<UserOut> =
        safeApiCall { api.updateProfile(ProfileUpdate(nickname = nickname)) }

    /**
     * 修改个性简介。
     *
     * 注意区分两种「空」：
     *   - 传 null → 不改动简介（后端 `if payload.bio is not None` 会跳过）
     *   - 传 "" → 清空简介
     * 所以「用户清空了输入框」必须传空串而不是 null，否则清不掉。
     */
    suspend fun updateBio(bio: String?): ApiResult<UserOut> =
        safeApiCall { api.updateProfile(ProfileUpdate(bio = bio)) }

    /** 一次改昵称 + 简介，省一次请求 */
    suspend fun updateProfile(nickname: String?, bio: String?): ApiResult<UserOut> =
        safeApiCall { api.updateProfile(ProfileUpdate(nickname = nickname, bio = bio)) }

    /**
     * 上传头像。
     *
     * mime 不能硬编码成 image/jpeg —— 后端会按 content_type 做白名单校验
     * （只放行 jpeg/png/webp），用 PNG 文件却报 jpeg 会被直接 415 拒掉。
     */
    suspend fun uploadAvatar(file: File, mimeType: String): ApiResult<UserOut> = safeApiCall {
        val body = file.asRequestBody(mimeType.toMediaTypeOrNull())
        api.uploadAvatar(MultipartBody.Part.createFormData("file", file.name, body))
    }

    /** 过敏原 / 忌口的可选项清单（后端维护，客户端只负责渲染） */
    suspend fun getOptions(): ApiResult<UserOptions> = safeApiCall { api.getUserOptions() }

    suspend fun savePreference(
        cuisine: String,
        taste: String,
        cookTimeMax: Int,
        dietGoal: String,
        dislikedFoods: List<String>,
        allergies: List<String>,
    ): ApiResult<UserPreferenceOut> = safeApiCall {
        api.savePreference(
            UserPreferenceIn(
                cuisine = cuisine,
                taste = taste,
                cookTimeMax = cookTimeMax,
                dietGoal = dietGoal,
                dislikedFoods = dislikedFoods,
                allergies = allergies,
            )
        )
    }

    suspend fun saveHealth(body: HealthPreferenceIn): ApiResult<HealthPreferenceOut> =
        safeApiCall { api.saveHealth(body) }

    /**
     * 公开性开关。
     *
     * `/users/profile` 已经带了 privacy 字段，正常流程不用单独调 getPrivacy()；
     * 这个接口留着是为了「个人页只刷隐私设置」这种局部刷新场景，
     * 以及排查问题时能在 /docs 里单独打一下。
     */
    suspend fun getPrivacy(): ApiResult<PrivacySettingOut> = safeApiCall { api.getPrivacy() }

    suspend fun savePrivacy(body: PrivacySettingIn): ApiResult<PrivacySettingOut> =
        safeApiCall { api.savePrivacy(body) }

    suspend fun listFamily(): ApiResult<List<FamilyMemberOut>> = safeApiCall { api.listFamily() }

    suspend fun addFamily(body: FamilyMemberIn): ApiResult<FamilyMemberOut> =
        safeApiCall { api.addFamily(body) }

    suspend fun deleteFamily(id: Int): ApiResult<Unit> =
        safeApiCall { api.deleteFamily(id); Unit }

    suspend fun preferenceInsights(): ApiResult<PreferenceInsights> =
        safeApiCall { api.preferenceInsights() }
}
