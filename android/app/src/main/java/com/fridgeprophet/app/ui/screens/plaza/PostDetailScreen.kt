package com.fridgeprophet.app.ui.screens.plaza

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fridgeprophet.app.R
import com.fridgeprophet.app.data.remote.dto.CommentOut
import com.fridgeprophet.app.ui.components.AvatarImage
import com.fridgeprophet.app.ui.components.EmptyState
import com.fridgeprophet.app.ui.components.LoadingBox
import com.fridgeprophet.app.ui.theme.SemanticColors

/**
 * 动态详情：完整正文 + 配图 + 做法 + 评论区。
 *
 * 列表里只显示前两步做法，这里显示全部 —— 详情页就是「点进来认真看」的地方，
 * 再截断就失去意义了。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostDetailScreen(
    onBack: () -> Unit,
    onOpenAuthor: (Int) -> Unit,
    onOpenRecipe: (Int) -> Unit,
    viewModel: PostDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var confirmingDelete by remember { mutableStateOf(false) }

    LaunchedEffect(state.shareRequest) {
        val request = state.shareRequest ?: return@LaunchedEffect
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, request.text)
        }
        context.startActivity(Intent.createChooser(intent, "分享到"))
        viewModel.consumeShareRequest()
    }

    // 动态被删掉（本人删的）之后退出去，否则会停在一个已经不存在的内容上
    LaunchedEffect(state.deleted) {
        if (state.deleted) onBack()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("动态") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = "返回",
                        )
                    }
                },
                actions = {
                    if (state.post?.isMine == true) {
                        IconButton(onClick = { confirmingDelete = true }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_action_delete),
                                contentDescription = "删除动态",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            // 评论输入框固定在底部。放列表里的话，评论一多就得滚到底才能发言，
            // 而「看到一半想回一句」是最常见的场景。
            if (state.post != null) {
                CommentInput(
                    draft = state.draft,
                    sending = state.sending,
                    onChange = viewModel::onDraftChange,
                    onSend = { viewModel.sendComment() },
                )
            }
        },
    ) { padding ->
        when {
            state.loading -> LoadingBox(modifier = Modifier.padding(padding))

            state.post == null -> EmptyState(
                title = "动态不存在",
                description = state.error ?: "它可能已经被作者删除了",
                modifier = Modifier.padding(padding),
            )

            else -> {
                val post = state.post!!
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        PostCard(
                            post = post,
                            onLike = { viewModel.toggleLike() },
                            onComment = { /* 已经在详情页了，点评论不做事 */ },
                            onShare = { viewModel.requestShare() },
                            onOpenAuthor = { onOpenAuthor(post.author.id) },
                            onOpenPost = {},
                            onOpenRecipe = onOpenRecipe,
                        )
                    }

                    item {
                        Text(
                            text = if (state.comments.isEmpty()) {
                                "还没有评论"
                            } else {
                                "评论 ${state.comments.size}"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                        )
                    }

                    items(state.comments, key = { it.id }) { comment ->
                        CommentRow(
                            comment = comment,
                            canDelete = comment.isMine || post.isMine,
                            onDelete = { viewModel.deleteComment(comment) },
                            onOpenAuthor = { onOpenAuthor(comment.author.id) },
                        )
                    }
                }
            }
        }
    }

    if (confirmingDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("删除动态") },
            text = { Text("删掉之后，这条动态下的点赞和评论也会一起消失，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    viewModel.deletePost()
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun CommentRow(
    comment: CommentOut,
    canDelete: Boolean,
    onDelete: () -> Unit,
    onOpenAuthor: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(10.dp),
    ) {
        AvatarImage(
            avatarUrl = comment.author.avatarUrl,
            nickname = comment.author.nickname,
            size = 34,
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = comment.author.nickname,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable(onClick = onOpenAuthor),
                    )
                    Text(
                        text = " · ${relativeTime(comment.createdAt)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (canDelete) {
                    TextButton(onClick = onDelete) {
                        Text(
                            text = "删除",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            Text(text = comment.content, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun CommentInput(
    draft: String,
    sending: Boolean,
    onChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("说点什么…") },
            maxLines = 3,
            shape = RoundedCornerShape(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        TextButton(
            onClick = onSend,
            enabled = draft.isNotBlank() && !sending,
        ) {
            if (sending) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text("发送", color = if (draft.isNotBlank()) SemanticColors.fresh
                else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
