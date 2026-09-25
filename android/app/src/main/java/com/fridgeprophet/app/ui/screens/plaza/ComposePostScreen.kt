package com.fridgeprophet.app.ui.screens.plaza

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fridgeprophet.app.R
import com.fridgeprophet.app.data.remote.ApiClient
import com.fridgeprophet.app.data.remote.dto.RecipeOut
import com.fridgeprophet.app.ui.components.FoodImage
import com.fridgeprophet.app.ui.components.RemoteImage
import com.fridgeprophet.app.ui.components.uriToCacheFile

/**
 * 发布动态。
 *
 * ## 为什么配图先上传再发布，而不是「发布时一起传」
 *
 * 先传图能立刻给用户看到成品图预览（用的就是最终会显示的那张），
 * 发布时只提交一个 URL，请求体很小、很快。
 * 如果做成一起传，用户点了发布之后要等整张图上传完才知道结果，
 * 中途失败还得重传整条动态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposePostScreen(
    onBack: () -> Unit,
    onPublished: () -> Unit,
    viewModel: ComposePostViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 系统相册选图。PickVisualMedia 在 Android 13+ 走系统照片选择器，
    // 不需要申请存储权限；低版本自动退回兼容实现。
    val pickImage = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val prepared = uriToCacheFile(context, uri, "post")
        if (prepared == null) {
            return@rememberLauncherForActivityResult
        }
        viewModel.uploadImage(prepared.first, prepared.second)
    }

    // 发布成功后退出这一页。用 LaunchedEffect 而不是在 onClick 里直接调，
    // 因为「发布成功」这个状态可能来自重试、旋转屏幕后恢复等路径，
    // 挂在状态上比挂在点击回调上更可靠。
    LaunchedEffect(state.published) {
        if (state.published) onPublished()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("发布动态") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = "返回",
                        )
                    }
                },
                actions = {
                    Button(
                        onClick = { viewModel.publish() },
                        enabled = state.canPublish,
                        modifier = Modifier.padding(end = 12.dp),
                    ) {
                        if (state.publishing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("发布")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            OutlinedTextField(
                value = state.content,
                onValueChange = viewModel::onContentChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp),
                placeholder = { Text("今天做了什么菜？说说做法和心得…") },
                supportingText = {
                    Text("${state.content.length} / $POST_MAX_LENGTH")
                },
            )

            Spacer(Modifier.height(12.dp))

            // ---------- 配图 ----------
            if (state.imageUrl != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(4f / 3f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    RemoteImage(
                        url = ApiClient.absoluteUrl(state.imageUrl).orEmpty(),
                        contentDescription = "已选配图",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                        loading = {},
                        error = {},
                    )
                    TextButton(
                        onClick = { viewModel.clearImage() },
                        modifier = Modifier.align(Alignment.TopEnd),
                    ) { Text("移除") }
                }
                Spacer(Modifier.height(8.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        pickImage.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    enabled = !state.uploading,
                ) {
                    if (state.uploading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Text("  上传中…")
                    } else {
                        Text(if (state.imageUrl == null) "添加成品图" else "换一张")
                    }
                }
                OutlinedButton(onClick = { viewModel.toggleRecipePicker() }) {
                    Text(if (state.showRecipePicker) "收起菜谱" else "关联菜谱")
                }
            }

            // ---------- 菜谱选择器 ----------
            if (state.showRecipePicker) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "选一道你菜谱库里的菜，菜名和做法会一起带出去。再点一次可取消选择。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when {
                    state.loadingRecipes -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator(modifier = Modifier.size(22.dp)) }

                    state.recipes.isEmpty() -> Text(
                        text = "菜谱库还是空的，先去「菜谱」页生成几道菜",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )

                    else -> Column {
                        state.recipes.forEach { recipe ->
                            RecipePickRow(
                                recipe = recipe,
                                selected = state.selectedRecipe?.id == recipe.id,
                                onClick = { viewModel.selectRecipe(recipe) },
                            )
                        }
                    }
                }
            }

            // ---------- 已关联的菜谱预览 ----------
            state.selectedRecipe?.let { recipe ->
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FoodImage(
                        imageUrl = recipe.imageUrl,
                        name = recipe.name,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(10.dp)),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = recipe.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "${recipe.steps.size} 个步骤会一起发布",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun RecipePickRow(recipe: RecipeOut, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.surface
            )
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FoodImage(
            imageUrl = recipe.imageUrl,
            name = recipe.name,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = recipe.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                text = "${recipe.timeMinutes} 分钟 · ${recipe.steps.size} 步",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Icon(
                painter = painterResource(R.drawable.ic_action_check),
                contentDescription = "已选择",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
