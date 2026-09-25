package com.fridgeprophet.app.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "fridge_prophet_prefs")

/**
 * 登录令牌的本地存储。
 *
 * 内存里缓存一份（[_cached]），因为 OkHttp 拦截器需要在非挂起上下文里同步取令牌；
 * 磁盘上用 DataStore 持久化，杀掉进程也不丢。
 */
class TokenStore(private val context: Context) {

    private object Keys {
        val TOKEN = stringPreferencesKey("access_token")
        val EMAIL = stringPreferencesKey("user_email")
        val NICKNAME = stringPreferencesKey("user_nickname")
        val ONBOARDED = stringPreferencesKey("user_onboarded")
    }

    @Volatile
    private var _cached: String? = null

    val tokenFlow: Flow<String?> = context.dataStore.data.map { it[Keys.TOKEN] }
    val nicknameFlow: Flow<String?> = context.dataStore.data.map { it[Keys.NICKNAME] }

    /** 拦截器用，同步读。首次会阻塞读一次磁盘，之后走内存。 */
    fun tokenBlocking(): String? {
        _cached?.let { return it }
        val value = runBlocking { context.dataStore.data.first()[Keys.TOKEN] }
        _cached = value
        return value
    }

    suspend fun save(token: String, email: String, nickname: String, onboarded: Boolean) {
        _cached = token
        context.dataStore.edit {
            it[Keys.TOKEN] = token
            it[Keys.EMAIL] = email
            it[Keys.NICKNAME] = nickname
            it[Keys.ONBOARDED] = onboarded.toString()
        }
    }

    suspend fun setOnboarded(onboarded: Boolean) {
        context.dataStore.edit { it[Keys.ONBOARDED] = onboarded.toString() }
    }

    suspend fun clear() {
        _cached = null
        context.dataStore.edit { it.clear() }
    }
}
