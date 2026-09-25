package com.fridgeprophet.app.ui.components

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException

/**
 * 把相册返回的 `content://` Uri 复制成缓存目录里的真实文件。
 *
 * ## 为什么要复制，不能直接用 Uri
 *
 * OkHttp 上传需要 File 或 InputStream，而 Uri 只是「一个引用」：
 * 它指向的是相册应用的数据，授权可能随时失效，也可能指向云端还没下载下来的照片。
 * 在后台线程直接读它失败率不低，而且失败信息很难看懂。
 * 先落到自己的缓存目录，之后怎么读都稳。
 *
 * ## 为什么按真实 MIME 决定扩展名
 *
 * 后端按 `content_type` 做白名单校验，只放行 jpeg/png/webp。
 * 如果一律存成 `.jpg` 再报 `image/jpeg`，一张 PNG 会以 jpeg 的名义上传，
 * 后端和 Supabase 都可能按错误类型处理；反过来把 PNG 报成 jpeg 会被 415 拒掉。
 * 所以扩展名和 MIME 必须**成对**地来自同一个 `getType()` 结果。
 *
 * @param prefix 文件名前缀，便于在缓存目录里区分来源（avatar / post / ...）。
 * @return (文件, MIME)；读不到返回 null，调用方需要处理这种情况。
 */
fun uriToCacheFile(context: Context, uri: Uri, prefix: String): Pair<File, String>? {
    val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
    val ext = when {
        mime.contains("png") -> "png"
        mime.contains("webp") -> "webp"
        else -> "jpg"
    }
    val file = File(context.cacheDir, "${prefix}_${System.currentTimeMillis()}.$ext")

    return try {
        val stream = context.contentResolver.openInputStream(uri) ?: return null
        stream.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        file to mime
    } catch (e: IOException) {
        // 复制失败时把半截文件删掉，否则会在缓存目录里留下一个 0 字节的垃圾
        file.delete()
        null
    }
}
