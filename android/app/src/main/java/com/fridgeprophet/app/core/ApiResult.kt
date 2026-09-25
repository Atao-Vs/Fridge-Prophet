package com.fridgeprophet.app.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 统一的接口调用结果。
 *
 * 把各种异常翻译成用户看得懂的中文提示，
 * 避免每个界面各写一遍 try-catch 和错误文案。
 */
sealed interface ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>
    data class Failure(val message: String, val code: Int = 0) : ApiResult<Nothing>
}

private val errorJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** 从后端返回的错误体里抠出 detail 字段 */
private fun parseDetail(body: String?): String? {
    if (body.isNullOrBlank()) return null
    return runCatching {
        errorJson.decodeFromString<Map<String, String>>(body)["detail"]
    }.getOrNull()
}

suspend fun <T> safeApiCall(block: suspend () -> T): ApiResult<T> = withContext(Dispatchers.IO) {
    try {
        ApiResult.Success(block())
    } catch (e: HttpException) {
        val detail = parseDetail(e.response()?.errorBody()?.string())
        val message = when (e.code()) {
            400 -> detail ?: "请求参数有误"
            401 -> detail ?: "登录已过期，请重新登录"
            403 -> detail ?: "没有权限执行该操作"
            404 -> detail ?: "数据不存在"
            409 -> detail ?: "数据冲突"
            413 -> detail ?: "图片太大了，请压缩后重试"
            415 -> detail ?: "不支持的图片格式"
            422 -> detail ?: "数据格式不正确"
            500 -> detail ?: "服务器出错了，请稍后重试"
            502, 503, 504 -> "服务器暂时不可用，请稍后重试"
            else -> detail ?: "请求失败（${e.code()}）"
        }
        ApiResult.Failure(message, e.code())
    } catch (e: SocketTimeoutException) {
        ApiResult.Failure("请求超时。AI 识别较慢，请检查网络后重试")
    } catch (e: UnknownHostException) {
        ApiResult.Failure("连不上服务器。请确认后端已启动，且手机与电脑在同一网络")
    } catch (e: IOException) {
        ApiResult.Failure("网络异常：${e.message ?: "连接中断"}")
    } catch (e: Exception) {
        ApiResult.Failure("出错了：${e.message ?: e::class.simpleName}")
    }
}
