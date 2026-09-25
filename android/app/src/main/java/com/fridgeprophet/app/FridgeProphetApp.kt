package com.fridgeprophet.app

import android.app.Application
import com.fridgeprophet.app.ui.components.ImageLoader
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class FridgeProphetApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 给图片加载器指一个磁盘缓存目录。放在这里而不是 UI 层，
        // 是因为它需要一个 Application 级 Context，而这里是最早能拿到它的地方。
        ImageLoader.install(this)
    }
}
