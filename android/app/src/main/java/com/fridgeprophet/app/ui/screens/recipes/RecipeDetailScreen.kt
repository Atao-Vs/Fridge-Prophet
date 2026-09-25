package com.fridgeprophet.app.ui.screens.recipes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import com.fridgeprophet.app.data.remote.dto.NutritionEstimate
import com.fridgeprophet.app.data.remote.dto.RecipeIngredientOut
import com.fridgeprophet.app.data.remote.dto.RecipeOut
import com.fridgeprophet.app.ui.components.EmptyState
import com.fridgeprophet.app.ui.components.FoodImage
import com.fridgeprophet.app.ui.components.LabeledRow
import com.fridgeprophet.app.ui.components.LoadingBox
import com.fridgeprophet.app.ui.components.Pill
import com.fridgeprophet.app.ui.components.SectionCard
import com.fridgeprophet.app.ui.components.approx
import com.fridgeprophet.app.ui.screens.home.formatQuantity
import com.fridgeprophet.app.ui.theme.SemanticColors

private const val DEFAULT_NUTRITION_DISCLAIMER =
    "营养数据由 AI 按食材和用量估算，不是称重实测值，仅供参考。" +
        "有慢性病、孕期或特殊饮食需求时，请遵医嘱，不要仅凭这里的数字做决定。"

/**
 * 菜谱详情。
 *
 * 页面的信息层级刻意按「做饭时的实际顺序」排：
 * 先看缺什么 → 再看怎么做 → 最后才看营养。营养放最后，因为它是参考信息而非行动指引。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeDetailScreen(
    recipeId: Int,
    onBack: () -> Unit,
    viewModel: RecipeDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.recipe?.name ?: "菜谱详情",
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
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
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val recipe = state.recipe
            when {
                state.loading -> LoadingBox(text = "正在取菜谱…")

                recipe == null -> EmptyState(
                    title = "没找到这道菜",
                    description = state.error ?: "可能已经被删除了",
                    actionText = "返回",
                    onAction = onBack,
                )

                else -> RecipeDetailContent(
                    recipe = recipe,
                    buildingShopping = state.buildingShopping,
                    message = state.message,
                    error = state.error,
                    feedbackSent = state.feedbackSent,
                    onDismissMessage = viewModel::clearMessages,
                    onBuildShopping = { viewModel.buildShoppingList(onSuccess = {}) },
                    onFeedback = viewModel::sendFeedback,
                )
            }
        }
    }
}

@Composable
private fun RecipeDetailContent(
    recipe: RecipeOut,
    buildingShopping: Boolean,
    message: String?,
    error: String?,
    feedbackSent: String?,
    onDismissMessage: () -> Unit,
    onBuildShopping: () -> Unit,
    onFeedback: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---------- 配图 ----------
        // 放在最顶上做「主视觉」，让用户先对成品有个印象再看细节。
        // 没有配图时 FoodImage 会自动画一块按菜名配色的占位图，不会留空白。
        FoodImage(
            imageUrl = recipe.imageUrl,
            name = recipe.name,
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(18.dp)),
        )

        // ---------- 头部概览 ----------
        SectionCard {
            Text(text = recipe.name, style = MaterialTheme.typography.headlineSmall)

            if (recipe.description.isNotBlank()) {
                Text(
                    text = recipe.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            Row(
                modifier = Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Pill(text = "${recipe.timeMinutes} 分钟", color = MaterialTheme.colorScheme.primary)
                Pill(text = difficultyLabel(recipe.difficulty), color = SemanticColors.normal)
                if (recipe.usesExpiring.isNotEmpty()) {
                    Pill(text = "消耗临期食材", color = SemanticColors.soon)
                }
            }

            if (recipe.tags.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    recipe.tags.forEach { tag ->
                        Pill(text = tag, color = SemanticColors.neutral)
                    }
                }
            }

            if (recipe.usesExpiring.isNotEmpty()) {
                Text(
                    text = "这道菜会用到你冰箱里快过期的：" +
                        recipe.usesExpiring.joinToString("、"),
                    style = MaterialTheme.typography.labelMedium,
                    color = SemanticColors.soon,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }

        // ---------- 提示条 ----------
        (error ?: message)?.let { text ->
            Surface(
                color = if (error != null) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                onClick = onDismissMessage,
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (error != null) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                    modifier = Modifier.padding(14.dp),
                )
            }
        }

        // ---------- 食材清单 ----------
        SectionCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "需要什么", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "共 ${recipe.ingredients.size} 样",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "打勾的是你冰箱里已经有的（后端按真实库存核对过）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )

            Column(modifier = Modifier.padding(top = 8.dp)) {
                recipe.ingredients.forEach { ingredient ->
                    IngredientRow(ingredient)
                }
            }

            if (recipe.missingIngredients.isNotEmpty()) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 10.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                Text(
                    text = "还差这几样",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Column(modifier = Modifier.padding(top = 6.dp)) {
                    recipe.missingIngredients.forEach { missing ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = missing.name,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = buildString {
                                    append(formatQuantity(missing.quantity, missing.unit))
                                    missing.estimatedPrice?.let { append(" · 约 ¥%.1f".format(it)) }
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // ---------- 烹饪步骤 ----------
        if (recipe.steps.isNotEmpty()) {
            SectionCard {
                Text(text = "怎么做", style = MaterialTheme.typography.titleMedium)
                Column(
                    modifier = Modifier.padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    recipe.steps.forEachIndexed { index, step ->
                        Row(verticalAlignment = Alignment.Top) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "${index + 1}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Text(
                                text = step,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 12.dp),
                            )
                        }
                    }
                }
            }
        }

        // ---------- 营养估算 ----------
        SectionCard {
            Text(text = "营养估算（每份）", style = MaterialTheme.typography.titleMedium)
            NutritionBlock(recipe.nutrition)
        }

        // ---------- 行为反馈 ----------
        SectionCard {
            Text(text = "这道菜怎么样？", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "你的选择会用来修正推荐偏好。跳过次数多了，这道菜就不会再出现。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onFeedback("favorite") },
                    modifier = Modifier.weight(1f),
                ) { Text("收藏") }

                OutlinedButton(
                    onClick = { onFeedback("cook") },
                    modifier = Modifier.weight(1f),
                ) { Text("做过") }

                OutlinedButton(
                    onClick = { onFeedback("skip") },
                    modifier = Modifier.weight(1f),
                ) { Text("不想吃") }
            }

            feedbackSent?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = SemanticColors.fresh,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }

        // ---------- 采购入口 ----------
        if (recipe.missingIngredients.isNotEmpty()) {
            Button(
                onClick = onBuildShopping,
                enabled = !buildingShopping,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ),
            ) {
                if (buildingShopping) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSecondary,
                    )
                } else {
                    Text(
                        text = "把缺的 ${recipe.missingIngredients.size} 样加进采购清单",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun IngredientRow(ingredient: RecipeIngredientOut) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(
                    if (ingredient.available) {
                        SemanticColors.fresh.copy(alpha = 0.16f)
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (ingredient.available) "✓" else "缺",
                style = MaterialTheme.typography.labelMedium,
                color = if (ingredient.available) {
                    SemanticColors.fresh
                } else {
                    MaterialTheme.colorScheme.error
                },
                fontWeight = FontWeight.SemiBold,
            )
        }

        Text(
            text = ingredient.name,
            style = MaterialTheme.typography.bodyLarge,
            color = if (ingredient.available) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp),
        )

        if (ingredient.optional) {
            Pill(
                text = "可选",
                color = SemanticColors.neutral,
                modifier = Modifier.padding(end = 8.dp),
            )
        }

        Text(
            text = formatQuantity(ingredient.quantity, ingredient.unit),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NutritionBlock(nutrition: NutritionEstimate) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        LabeledRow("热量", approx(nutrition.caloriesKcal, " 千卡"))
        Spacer(Modifier.height(6.dp))
        LabeledRow("蛋白质", approx(nutrition.proteinG, " g"))
        Spacer(Modifier.height(6.dp))
        LabeledRow("碳水", approx(nutrition.carbsG, " g"))
        Spacer(Modifier.height(6.dp))
        LabeledRow("脂肪", approx(nutrition.fatG, " g"))
        Spacer(Modifier.height(6.dp))
        LabeledRow("膳食纤维", approx(nutrition.fiberG, " g"))
        Spacer(Modifier.height(6.dp))
        LabeledRow("钠", approx(nutrition.sodiumMg, " mg"))

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 10.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Text(
            text = nutrition.disclaimer.ifBlank { DEFAULT_NUTRITION_DISCLAIMER },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun difficultyLabel(difficulty: String): String = when (difficulty) {
    "easy" -> "简单"
    "medium" -> "中等"
    else -> "较难"
}
