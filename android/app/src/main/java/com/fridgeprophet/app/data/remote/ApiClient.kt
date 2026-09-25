package com.fridgeprophet.app.data.remote

import com.fridgeprophet.app.BuildConfig
import com.fridgeprophet.app.core.TokenStore
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/**
 * Retrofit 客户端。
 *
 * 三个关键点：
 * 1. ignoreUnknownKeys = true —— 后端加字段不会让客户端崩，前后端可以独立升级
 * 2. 超时给到 120 秒 —— AI 识别一张冰箱照片可能跑几十秒，默认 10 秒必然超时
 * 3. 拦截器自动带 Bearer 令牌，401 时清空本地登录态
 */
object ApiClient {

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    fun create(tokenStore: TokenStore, onUnauthorized: () -> Unit): FridgeApi {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        val authInterceptor = okhttp3.Interceptor { chain ->
            val original = chain.request()
            val token = tokenStore.tokenBlocking()
            val request = if (token.isNullOrBlank()) {
                original
            } else {
                original.newBuilder()
                    .header("Authorization", "Bearer $token")
                    .build()
            }
            val response = chain.proceed(request)
            if (response.code == 401) {
                onUnauthorized()
            }
            response
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        return Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(FridgeApi::class.java)
    }

    /**
     * 把后端返回的**相对路径**拼成可加载的绝对 URL。
     *
     * 为什么后端不直接给绝对地址：同一份数据要在模拟器（10.0.2.2）、
     * 局域网真机（192.168.x.x）、公网域名三种环境下都能用，
     * 后端不可能知道客户端从哪个地址访问它。所以后端只给
     * `/static/recipes/tomato-egg.jpg`，由客户端补上自己正在用的域名。
     *
     * - 传进来的已经是 http/https 开头 → 原样返回（比如以后换成 Supabase CDN）
     * - 传 null 或空串 → 返回 null，调用方显示占位图
     */
    fun absoluteUrl(path: String?): String? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("http://") || path.startsWith("https://")) return path

        val base = BuildConfig.API_BASE_URL.toHttpUrlOrNull() ?: return null
        // base 以 "/" 结尾，相对路径以 "/" 开头，resolve 会正确处理
        return base.resolve(path.removePrefix("/"))?.toString()
    }
}
