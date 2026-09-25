package com.fridgeprophet.app.ui.screens.plaza

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fridgeprophet.app.R
import com.fridgeprophet.app.data.remote.dto.PublicHealth
import com.fridgeprophet.app.data.remote.dto.PublicProfileOut
import com.fridgeprophet.app.data.remote.dto.PublicPreference
import com.fridgeprophet.app.ui.components.AvatarImage
import com.fridgeprophet.app.ui.components.EmptyState
import com.fridgeprophet.app.ui.components.LoadingBox
import com.fridgeprophet.app.ui.components.Pill
import com.fridgeprophet.app.ui.components.SectionCard
import com.fridgeprophet.app.ui.theme.SemanticColors

/**
 * 别人的主页（从广场点进来）。
 *
 * ## 「未公开」必须显式显示出来
 *
 * 后端把未公开的区块返回 `null`。界面**不能**当成「没数据」跳过 ——
 * 那样用户看到一片空白，会以为是加载失败或者对方什么都没填。
 * 正确做法是显示一行「TA 未公开」的灰字，让「没公开」和「没填」
 * 在视觉上可区分。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileScreen(
    onBack: () -> Unit,
    onOpenPost: (Int) -> Unit,
    viewModel: UserProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(state.profile?.user?.nickname ?: "主页") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = "返回",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        when {
            state.loading -> LoadingBox(modifier = Modifier.padding(padding))

            state.profile == null -> EmptyState(
                title = "看不到这个主页",
                description = state.error ?: "用户可能已经注销",
                modifier = Modifier.padding(padding),
            )

            else -> {
                val profile = state.profile!!
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item { ProfileHeader(profile) }

                    if (!profile.isMe) {
                        item {
                            FollowButton(
                                following = state.following,
                                busy = state.busy,
                                onClick = { viewModel.toggleFollow() },
                            )
                        }
                    }

                    item { PublicSections(profile) }

                    item {
                        Text(
                            text = if (state.posts.isEmpty()) {
                                "还没有发布过动态"
                            } else {
                                "TA 的动态（只显示最近发布的内容）"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }

                    items(state.posts, key = { it.id }) { post ->
                        PostCard(
                            post = post,
                            onLike = { viewModel.toggleLike(post) },
                            onComment = { onOpenPost(post.id) },
                            onShare = { /* 主页里不做分享，进详情页再分享 */ },
                            onOpenAuthor = { /* 已经在这个人的主页上了 */ },
                            onOpenPost = { onOpenPost(post.id) },
                        )
                    }

                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ProfileHeader(profile: PublicProfileOut) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AvatarImage(
                avatarUrl = profile.user.avatarUrl,
                nickname = profile.user.nickname,
                size = 64,
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.user.nickname,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                profile.user.bio?.takeIf { it.isNotBlank() }?.let { bio ->
                    Text(
                        text = bio,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        Row(
            modifier = Modifier.padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            StatColumn("动态", profile.postCount)
            StatColumn("粉丝", profile.followerCount)
            StatColumn("关注", profile.followingCount)
            profile.cookedCount?.let { StatColumn("做过的菜", it) }
        }
    }
}

@Composable
private fun StatColumn(label: String, value: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FollowButton(following: Boolean, busy: Boolean, onClick: () -> Unit) {
    if (following) {
        // 已关注用描边按钮：X 的做法是 hover 才变「取消关注」，
        // 触屏没有 hover，所以直接写清楚状态和动作。
        OutlinedButton(
            onClick = onClick,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_action_check),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text("  已关注")
            }
        }
    } else {
        Button(
            onClick = onClick,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_action_person_add),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text("  关注")
            }
        }
    }
}

/**
 * 公开信息区。
 *
 * 三个区块各自独立判断「有没有公开」—— 对方可能公开了口味但没公开身体数据，
 * 所以不能用一个总的判断把三块一起显示或一起隐藏。
 */
@Composable
private fun PublicSections(profile: PublicProfileOut) {
    PublicBlock(
        title = "饮食偏好",
        isPublic = profile.visibility["preference"] == true,
        hasContent = profile.preference != null,
        emptyText = "TA 没有设置饮食偏好",
    ) {
        profile.preference?.let { PreferenceContent(it) }
    }

    PublicBlock(
        title = "健康偏好",
        isPublic = profile.visibility["health"] == true,
        hasContent = profile.health != null && profile.health.goals.isNotEmpty(),
        emptyText = "TA 没有设置健康目标",
    ) {
        profile.health?.let { HealthContent(it) }
    }

    PublicBlock(
        title = "家庭成员",
        isPublic = profile.visibility["family"] == true,
        hasContent = !profile.familyMembers.isNullOrEmpty(),
        emptyText = "TA 还没有添加家庭成员",
    ) {
        profile.familyMembers?.forEach { member ->
            Row(modifier = Modifier.padding(top = 6.dp)) {
                Text(
                    text = buildString {
                        append(member.name)
                        if (member.relation.isNotBlank()) append("（${member.relation}）")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "  ${member.dietGoal} · 口味${member.taste}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 一个公开区块的三种状态：未公开 / 公开但空 / 有内容。
 *
 * 抽出来是为了保证三块的处理完全一致 —— 之前踩过的坑是
 * 「未公开」和「公开但没填」被渲染成同一个样子，用户分不清。
 */
@Composable
private fun PublicBlock(
    title: String,
    isPublic: Boolean,
    hasContent: Boolean,
    emptyText: String,
    content: @Composable () -> Unit,
) {
    SectionCard {
        Text(text = title, style = MaterialTheme.typography.titleMedium)

        if (!isPublic) {
            Text(
                text = "TA 未公开这项信息",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            return@SectionCard
        }

        if (!hasContent) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            return@SectionCard
        }

        Column(modifier = Modifier.padding(top = 6.dp)) { content() }
    }
}

@Composable
private fun PreferenceContent(pref: PublicPreference) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Pill(text = pref.cuisine, color = SemanticColors.fresh)
        Pill(text = pref.taste, color = SemanticColors.normal)
        Pill(text = "${pref.cookTimeMax} 分钟内", color = SemanticColors.soon)
        Pill(text = pref.dietGoal, color = SemanticColors.fresh)
    }
    if (pref.dislikedFoods.isNotEmpty()) {
        Text(
            text = "忌口：${pref.dislikedFoods.joinToString("、")}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    // 过敏信息属于个人健康数据，广场上**不展示**，只说明「已设置」
    if (pref.allergies.isNotEmpty()) {
        Text(
            text = "已登记 ${pref.allergies.size} 项过敏原（不公开展示）",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HealthContent(health: PublicHealth) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        health.goals.forEach { goal ->
            Pill(text = goal.label, color = SemanticColors.fresh)
        }
    }

    health.body?.let { body ->
        Text(
            text = buildString {
                append(body.heightCm?.let { "${it.toInt()}cm" } ?: "未填身高")
                append(" · ")
                append(body.weightKg?.let { "${it.toInt()}kg" } ?: "未填体重")
                append(" · ")
                append(body.age?.let { "${it}岁" } ?: "未填年龄")
                body.activityLevel?.let { append(" · $it") }
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }

    if (health.disclaimer.isNotBlank()) {
        Text(
            text = health.disclaimer,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
