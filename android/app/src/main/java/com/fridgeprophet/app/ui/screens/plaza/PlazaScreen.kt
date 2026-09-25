package com.fridgeprophet.app.ui.screens.plaza

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fridgeprophet.app.R
import com.fridgeprophet.app.ui.components.EmptyState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * 广场（社区）信息流。布局参照 X：顶部是「关注 / 发现」两个频道 + 排序切换，
 * 下面是无限滚动的动态列表，右下角一个发布按钮。
 *
 * ## 为什么「关注」做成频道而不是第三个排序
 *
 * 排序（最新/热门/综合）回答的是「同样这批内容怎么排」，
 * 而「关注」回答的是「看哪批内容」—— 两者是正交的。
 * 混成三个平级选项会出现「关注+热门」这种组合没法表达的情况。
 */
@Composable
fun PlazaScreen(
    onOpenPost: (Int) -> Unit,
    onOpenAuthor: (Int) -> Unit,
    onOpenRecipe: (Int) -> Unit,
    onCompose: () -> Unit,
    viewModel: PlazaViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    // 把「分享」交给系统面板。
    //
    // ViewModel 只负责把文案递出来（它拿不到 Context，也不该拿），
    // 这里调起面板后立刻把事件清掉 —— 否则旋转屏幕、从后台回来时
    // 会因为 state 里还留着这个请求而再弹一次。
    LaunchedEffect(state.shareRequest) {
        val request = state.shareRequest ?: return@LaunchedEffect
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, request.text)
        }
        context.startActivity(Intent.createChooser(intent, "分享到"))
        viewModel.consumeShareRequest()
    }

    // 快到底部时自动加载下一页。
    //
    // 用 snapshotFlow + derivedStateOf 而不是「最后一项可见就加载」：
    // 后者会在列表短到一屏放得下时反复触发，把 hasMore 拉成 false 之前
    // 连续打好几个请求。
    val shouldLoadMore by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= listState.layoutInfo.totalItemsCount - 3
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { shouldLoadMore }
            .distinctUntilChanged()
            .filter { it }
            .collect { viewModel.loadMore() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            PlazaHeader(
                onlyFollowing = state.onlyFollowing,
                sort = state.sort,
                sortOptions = state.sortOptions,
                onToggleChannel = { viewModel.toggleOnlyFollowing() },
                onSort = { viewModel.setSort(it) },
            )

            when {
                state.loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                state.posts.isEmpty() -> EmptyState(
                    title = if (state.onlyFollowing) "你关注的人还没发过动态" else "广场还很安静",
                    description = if (state.onlyFollowing) {
                        "去「发现」看看，关注几个感兴趣的人"
                    } else {
                        "做了一道好菜？发出来让大家看看"
                    },
                    actionText = "发布动态",
                    onAction = onCompose,
                )

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 12.dp, end = 12.dp, top = 4.dp, bottom = 88.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.posts, key = { it.id }) { post ->
                        PostCard(
                            post = post,
                            onLike = { viewModel.toggleLike(post) },
                            onComment = { onOpenPost(post.id) },
                            onShare = { viewModel.requestShare(post) },
                            onOpenAuthor = { onOpenAuthor(post.author.id) },
                            onOpenPost = { onOpenPost(post.id) },
                            onDelete = if (post.isMine) {
                                { viewModel.deletePost(post) }
                            } else {
                                null
                            },
                            onOpenRecipe = onOpenRecipe,
                        )
                    }

                    if (state.loadingMore) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center,
                            ) { CircularProgressIndicator(modifier = Modifier.size(24.dp)) }
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = onCompose,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_action_compose),
                contentDescription = "发布动态",
            )
        }
    }
}

@Composable
private fun PlazaHeader(
    onlyFollowing: Boolean,
    sort: String,
    sortOptions: List<com.fridgeprophet.app.data.remote.dto.SortOption>,
    onToggleChannel: () -> Unit,
    onSort: (String) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "广场", style = MaterialTheme.typography.headlineSmall)

            // 频道切换。只有两个，做成文字切换而不是 Tab：
            // 两个 Tab 的底部指示条在一屏里看着比文字重，反而更吵。
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChannelText("发现", selected = !onlyFollowing, onClick = onToggleChannel)
                Text("  ", style = MaterialTheme.typography.labelMedium)
                ChannelText("关注", selected = onlyFollowing, onClick = onToggleChannel)
            }
        }

        // 排序选项由后端下发。后端加一种排序，这里自动多一个 chip，不用发版。
        if (sortOptions.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sortOptions.forEach { option ->
                    FilterChip(
                        selected = option.value == sort,
                        onClick = { onSort(option.value) },
                        label = { Text(option.label) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelText(text: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
