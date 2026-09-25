package com.fridgeprophet.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fridgeprophet.app.data.remote.ApiClient
import kotlin.math.abs

/**
 * 菜品配图。
 *
 * 两个关键设计：
 *
 * 1. **后端只给相对路径**（`/static/recipes/tomato-egg.jpg`），
 *    由 [ApiClient.absoluteUrl] 拼上当前正在用的服务器域名。
 *    这样同一份数据在模拟器（10.0.2.2）、局域网真机、公网域名下都能用。
 *
 * 2. **没有图也给一块好看的占位**，绝不出现空白框或裂图。
 *    占位底色由菜名哈希决定，所以「番茄炒蛋」和「红烧肉」的占位色不一样，
 *    列表里看着是有区分度的，而不是一排一模一样的灰块。
 */
@Composable
fun FoodImage(
    imageUrl: String?,
    name: String,
    modifier: Modifier = Modifier,
) {
    val absolute = remember(imageUrl) { ApiClient.absoluteUrl(imageUrl) }

    if (absolute == null) {
        FoodImagePlaceholder(name = name, modifier = modifier)
        return
    }

    RemoteImage(
        url = absolute,
        contentDescription = name,
        contentScale = ContentScale.Crop,
        modifier = modifier,
        loading = { FoodImagePlaceholder(name = name, modifier = Modifier.fillMaxSize()) },
        error = { FoodImagePlaceholder(name = name, modifier = Modifier.fillMaxSize()) },
    )
}

/**
 * 占位图：由菜名哈希出一个色相，做一层柔和渐变，中间放菜名首字。
 *
 * 为什么不放个统一的「图片」图标：一排图标看着像坏了，
 * 而带颜色的首字块看起来是「有意设计」，观感差别很大。
 */
@Composable
fun FoodImagePlaceholder(name: String, modifier: Modifier = Modifier) {
    // 用菜名哈希挑一个色相。加 1 避免 abs(Int.MIN_VALUE) 溢出。
    val hue = remember(name) { (abs(name.hashCode() + 1) % 360).toFloat() }
    val top = Color.hsl(hue, 0.32f, 0.72f)
    val bottom = Color.hsl((hue + 28f) % 360f, 0.38f, 0.58f)

    Box(
        modifier = modifier.background(Brush.verticalGradient(listOf(top, bottom))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.take(1),
            color = Color.White.copy(alpha = 0.92f),
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * 圆形头像。没上传过头像时显示昵称首字，配色同样由昵称哈希决定。
 */
@Composable
fun AvatarImage(
    avatarUrl: String?,
    nickname: String,
    size: Int = 64,
    modifier: Modifier = Modifier,
) {
    val absolute = remember(avatarUrl) { ApiClient.absoluteUrl(avatarUrl) }
    val shape = CircleShape
    val base = modifier.size(size.dp).clip(shape)

    if (absolute == null) {
        AvatarPlaceholder(nickname = nickname, modifier = base)
        return
    }

    RemoteImage(
        url = absolute,
        contentDescription = "$nickname 的头像",
        contentScale = ContentScale.Crop,
        modifier = base,
        // 头像最多显示 64dp，3x 屏也就 192px，按 256 长边解码足够清晰
        maxEdgePx = 256,
        loading = { AvatarPlaceholder(nickname = nickname, modifier = Modifier.fillMaxSize()) },
        error = { AvatarPlaceholder(nickname = nickname, modifier = Modifier.fillMaxSize()) },
    )
}

@Composable
private fun AvatarPlaceholder(nickname: String, modifier: Modifier = Modifier) {
    val initial = nickname.trim().take(1).ifBlank { "?" }
    val hue = remember(nickname) { (abs(nickname.hashCode() + 7) % 360).toFloat() }

    Box(
        modifier = modifier.background(
            Brush.linearGradient(
                listOf(
                    Color.hsl(hue, 0.42f, 0.66f),
                    Color.hsl((hue + 34f) % 360f, 0.46f, 0.52f),
                )
            )
        ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}
