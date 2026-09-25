package com.fridgeprophet.app.ui.screens.tips

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fridgeprophet.app.R
import com.fridgeprophet.app.data.remote.dto.FoodTipDetail
import com.fridgeprophet.app.ui.components.EmptyState
import com.fridgeprophet.app.ui.components.LoadingBox
import com.fridgeprophet.app.ui.components.SectionCard
import com.fridgeprophet.app.ui.components.VerdictChip
import com.fridgeprophet.app.ui.components.verdictColor

/**
 * 贴士详情：把「结论 + 原因原理 + 依据来源」讲清楚。
 *
 * 版式刻意做成「先给结论，再讲道理」：
 * 大部分用户只想知道「能不能吃」，所以结论用整块彩色横幅放在最上面，
 * 一眼就能看到；想深究的人再往下读原理和出处。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TipDetailScreen(
    onBack: () -> Unit,
    viewModel: TipDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("贴士详情", style = MaterialTheme.typography.titleLarge) },
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
            val tip = state.tip
            when {
                state.loading -> LoadingBox(text = "正在取内容…")

                tip == null -> EmptyState(
                    title = "没找到这条贴士",
                    description = state.error ?: "可能已被移除",
                    actionText = "返回",
                    onAction = onBack,
                )

                else -> TipDetailContent(tip)
            }
        }
    }
}

@Composable
private fun TipDetailContent(tip: FoodTipDetail) {
    val accent = verdictColor(tip.verdict)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---------- 结论横幅：一眼看到「能不能吃」 ----------
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(
                    Brush.linearGradient(
                        listOf(accent.copy(alpha = 0.20f), accent.copy(alpha = 0.08f))
                    )
                )
                .padding(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = tip.category,
                        style = MaterialTheme.typography.labelMedium,
                        color = accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                    VerdictChip(tip.verdict)
                }

                Text(
                    text = tip.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )

                if (tip.summary.isNotBlank()) {
                    Text(
                        text = tip.summary,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ---------- 原因与原理 ----------
        if (tip.detail.isNotBlank()) {
            SectionCard {
                Text(
                    text = "为什么会这样",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = tip.detail,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }

        // ---------- 依据来源 ----------
        if (tip.source.isNotBlank()) {
            SectionCard {
                Text(
                    text = "依据来源",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = tip.source,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                Text(
                    text = "以上内容为一般性食品安全信息，不构成医学或营养学建议。" +
                        "有基础疾病、孕期或特殊饮食需求时，请以医生或注册营养师的意见为准。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}
