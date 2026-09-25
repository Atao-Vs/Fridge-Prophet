package com.fridgeprophet.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 极简网络图片加载器 —— **不依赖任何第三方图片库**。
 *
 * ## 为什么自己写，而不是用 Coil / Glide
 *
 * 试过 Coil 3.3.0，它会让 Gradle 8.14.5 在序列化依赖图时直接抛：
 * ```
 * Corrupt serialized resolution result.
 *   Cannot find selected component (4630) for constraint
 *   platform-runtime -> org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.8.0
 * ```
 * 根因是 Coil 3 是 KMP 库，其 `-jvm` 变体多带了一个 `platform-runtime` 约束，
 * 与本项目被三条约束锁死的构建链（AGP 8.13.2 / Kotlin 2.2.21 / Gradle 8.14.5）
 * 合不来。而本项目**本来就有 OkHttp**，写这 100 行比调整整条构建链便宜得多，
 * 也不给后面留一个「一升 Gradle 就炸」的隐患。
 *
 * ## 它做了哪四件事
 *
 * 1. **内存 LRU 缓存**：默认取堆内存的 1/8，滚动列表来回滑不会反复解码。
 * 2. **磁盘 HTTP 缓存**（24 MB）：后端 StaticFiles 会发 `etag` / `last-modified`，
 *    所以第二次打开 App 时命中缓存只需一次 304 往返，流量几乎为零。
 *    部署到公网后这条特别值钱。
 * 3. **降采样解码**：先读图片头拿原始宽高，再按需要的边长算 `inSampleSize`。
 *    一张 4000×3000 的手机原图直接解码要 48 MB，降到 1080 长边后只要 2.3 MB。
 * 4. **在途请求合并**：同一张图被多个卡片同时请求时只下载一次。
 *
 * ## 内存里的图为什么用 RGB_565
 *
 * 菜品照和头像都是不透明照片，没有透明通道。ARGB_8888 每个像素 4 字节，
 * RGB_565 只要 2 字节 —— 缓存能多装一倍，而肉眼几乎看不出差别。
 */
object ImageLoader {

    /** 磁盘缓存上限。菜品图一张一两百 KB，24 MB 足够装下整个图片库还有富余。 */
    private const val DISK_CACHE_BYTES = 24L * 1024 * 1024

    /** 内存缓存取堆上限的 1/8 —— 这是 Android 官方推荐的经验值。 */
    private const val MEMORY_CACHE_FRACTION = 8

    /** 兜底下限 / 上限，避免在小内存机器或异常堆配置下取到离谱的值。 */
    private const val MEMORY_CACHE_MIN_BYTES = 4L * 1024 * 1024
    private const val MEMORY_CACHE_MAX_BYTES = 48L * 1024 * 1024

    private val memoryCache: LruCache<String, Bitmap> = LruCache(
        (Runtime.getRuntime().maxMemory() / MEMORY_CACHE_FRACTION)
            .coerceIn(MEMORY_CACHE_MIN_BYTES, MEMORY_CACHE_MAX_BYTES)
            .toInt()
    )

    /** 下载任务跑在这个作用域里，而不是调用方的协程 —— 这样某个卡片滑出屏幕被取消时，
     *  已经发出去的下载不会被腰斩，其他还在等同一张图的卡片仍能拿到结果。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val inFlight = ConcurrentHashMap<String, Deferred<Bitmap?>>()

    @Volatile
    private var diskCacheDir: File? = null

    @Volatile
    private var client: OkHttpClient? = null

    /**
     * 在 `Application.onCreate` 里调一次即可。
     *
     * 只做「记下缓存目录」这一件事，真正的 OkHttpClient 等第一次真正要加载图片时才建，
     * 免得冷启动时白白起一堆线程。
     */
    fun install(context: Context) {
        diskCacheDir = File(context.cacheDir, "image_cache")
    }

    /** 同步取缓存里的图。命中时界面**第一帧**就能画出图，不会先闪一下占位图。 */
    fun peek(url: String): Bitmap? = memoryCache.get(url)

    /**
     * 加载一张图。失败（断网 / 404 / 解码不出来 / 内存不足）统一返回 null，
     * 由调用方显示占位图 —— 图片加载失败不该让任何界面报错。
     */
    suspend fun load(url: String, maxEdgePx: Int): Bitmap? {
        peek(url)?.let { return it }

        // 同一张图并发请求时复用同一个下载任务
        val job = inFlight.getOrPut(url) {
            scope.async { fetch(url, maxEdgePx) }
        }

        return try {
            job.await()
        } finally {
            // 无条件移除。哪怕这个调用方被取消（比如卡片滑走了），其他已经拿到
            // 同一个 Deferred 的调用方依然能正常拿到结果；而后来者会重新发起一次
            // 下载，最多浪费一点流量，绝不会拿到一个永远失败的陈旧任务。
            inFlight.remove(url, job)
        }
    }

    private fun http(): OkHttpClient {
        client?.let { return it }
        synchronized(this) {
            client?.let { return it }

            val builder = OkHttpClient.Builder()
                // 图片不需要 120 秒的 AI 超时，给短一点，加载不出来就赶紧退回占位图
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)

            diskCacheDir?.let { dir ->
                runCatching { builder.cache(Cache(dir, DISK_CACHE_BYTES)) }
            }

            return builder.build().also { client = it }
        }
    }

    private suspend fun fetch(url: String, maxEdgePx: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).build()
                http().newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val body = response.body ?: return@withContext null
                    val bytes = body.bytes()
                    decode(bytes, maxEdgePx)?.also { memoryCache.put(url, it) }
                }
            } catch (e: IOException) {
                null
            } catch (e: OutOfMemoryError) {
                // 内存实在不够就当加载失败，退回占位图 —— 总比整个 App 崩掉好
                null
            }
        }

    /** 两步解码：先只读图片头拿原始尺寸，再按目标边长算降采样倍数做真正的解码。 */
    private fun decode(bytes: ByteArray, maxEdgePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxEdgePx)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /**
     * 算出最接近且不小于 1 的 2 的幂次降采样倍数。
     *
     * 只接受 2 的幂是因为 BitmapFactory 对非 2 的幂会向下取到最近的 2 的幂，
     * 传 3 和传 2 效果一样，不如自己算清楚。
     * 条件是 `longest / 2 >= maxEdgePx` 而不是 `longest > maxEdgePx`，
     * 保证降采样后**不会小于**目标尺寸（宁可多留一点像素，也不要糊）。
     */
    private fun sampleSize(width: Int, height: Int, maxEdgePx: Int): Int {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= maxEdgePx) {
            longest /= 2
            sample *= 2
        }
        return sample
    }
}

/** 默认按 1080 长边解码：够 3x 屏的整屏大图用，单张 RGB_565 只占约 2.3 MB。 */
private const val DEFAULT_MAX_EDGE_PX = 1080

/** 加载状态。用 sealed interface 而不是三个布尔，避免出现「既在加载又出错了」这种非法组合。 */
private sealed interface ImageState {
    data object Loading : ImageState
    data object Error : ImageState
    data class Success(val bitmap: Bitmap) : ImageState
}

/**
 * 通用网络图片。
 *
 * 三个槽位（[loading] / [error]）由调用方决定长什么样 —— 图片组件不该替业务
 * 决定「加载中」该显示什么，那是占位图自己的事。
 *
 * 注意 [url] 必须是**绝对地址**。后端返回的是相对路径，
 * 请先过一遍 `ApiClient.absoluteUrl(...)`（`FoodImage` 已经这么做了）。
 */
@Composable
fun RemoteImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    maxEdgePx: Int = DEFAULT_MAX_EDGE_PX,
    loading: @Composable () -> Unit,
    error: @Composable () -> Unit,
) {
    // 初始值直接查一次缓存：命中时首帧就是图，不会先闪一下占位图再切成图。
    var state by remember(url, maxEdgePx) {
        mutableStateOf(
            ImageLoader.peek(url)?.let { ImageState.Success(it) } ?: ImageState.Loading
        )
    }

    LaunchedEffect(url, maxEdgePx) {
        if (state is ImageState.Success) return@LaunchedEffect
        val bitmap = ImageLoader.load(url, maxEdgePx)
        state = if (bitmap != null) ImageState.Success(bitmap) else ImageState.Error
    }

    when (val current = state) {
        is ImageState.Success -> Image(
            bitmap = current.bitmap.asImageBitmap(),
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier,
        )

        // 槽位自己不接收 modifier，所以外面套一层 Box 把尺寸传下去，
        // 占位图里的 fillMaxSize() 才能正确铺满。
        is ImageState.Loading -> Box(modifier) { loading() }
        is ImageState.Error -> Box(modifier) { error() }
    }
}
