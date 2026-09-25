package com.fridgeprophet.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fridgeprophet.app.data.remote.dto.FoodTipSummary
import com.fridgeprophet.app.ui.theme.SemanticColors

/**
 * 贴士结论对应的语义色。
 *
 * 这里刻意和「新鲜度」用同一套语义色，让用户形成一致的心理映射：
 *   绿 = 放心 ｜ 橙 = 留意 ｜ 红 = 危险
 *
 * 注意「谣言」用**绿色**：它的意思是「这事是假的，可以放心」。
 * 反过来如果给谣言标红，用户会以为「这条很危险」，正好理解反了。
 */
fun verdictColor(verdict: String): Color = when (verdict) {
    "谣言" -> SemanticColors.fresh
    "部分属实" -> SemanticColors.soon
    "属实" -> SemanticColors.expired
    "注意" -> SemanticColors.soon
    else -> SemanticColors.neutral
}

/** 结论小标签，列表和详情页共用 */
@Composable
fun VerdictChip(verdict: String, modifier: Modifier = Modifier) {
    if (verdict.isBlank()) return
    Pill(text = verdict, color = verdictColor(verdict), modifier = modifier)
}

/**
 * 首页标语位。
 *
 * 设计取向是「安静但有点击欲」——不抢主功能（扫描按钮）的视觉重心：
 *   - 只有一条，一行标题 + 一行摘要，高度控制在 80dp 左右
 *   - 左侧一条 3dp 的竖色条提示结论，不做大面积填色
 *   - 整体可点，右侧给一个明确的「看原理 ›」
 *
 * tip 为 null 时整块不渲染（网络挂了 / 后端没数据），
 * 不显示骨架屏也不显示错误 —— 它是装饰性内容，不该打断主流程。
 */
@Composable
fun TipBar(
    tip: FoodTipSummary?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tip == null) return

    val accent = verdictColor(tip.verdict)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(
                        accent.copy(alpha = 0.13f),
                        accent.copy(alpha = 0.05f),
                    )
                )
            )
            .clickable(onClick = onClick),
    ) {
        // 用 IntrinsicSize.Min 让整行高度等于内容高度，
        // 这样左侧竖色条和右侧「看原理」才能 fillMaxHeight 撑满整行。
        // 直接给 fillMaxHeight 会撑到父容器的最大高度（可能是一整屏）。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
        ) {
            // 左侧竖色条
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(accent)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "食品安全小贴士",
                        style = MaterialTheme.typography.labelMedium,
                        color = accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                    VerdictChip(tip.verdict)
                }
                Text(
                    text = tip.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                if (tip.summary.isNotBlank()) {
                    Text(
                        text = tip.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(end = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "看原理 ›",
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}
