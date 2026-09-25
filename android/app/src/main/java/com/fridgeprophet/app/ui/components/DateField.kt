package com.fridgeprophet.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 日期选择框。点了弹出系统日历，选完回填。
 *
 * 实现要点：M3 的 `OutlinedTextField` 没有「只读但可点」的官方姿势。
 * 直接 `readOnly = true` 点击不会触发回调；`enabled = false` 又会变灰。
 * 所以这里用「禁用态 + 主题色覆盖回正常色 + 透明覆盖层接管点击」——
 * 看着是可编辑的输入框，实际点击走日历，也不会弹出软键盘。
 *
 * 日期统一用 `LocalDate`（只到天），因为食材保质期不需要精确到分钟。
 * 和日期选择器交互时注意 DatePickerState 给的是 **UTC 零点毫秒**，
 * 必须用 ZoneOffset.UTC 反解，用本地时区会差一天。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    label: String,
    value: LocalDate?,
    onValueChange: (LocalDate?) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "未设置",
    supportingText: String? = null,
) {
    var showPicker by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value?.toString().orEmpty(),
            onValueChange = {},
            enabled = false,
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            supportingText = supportingText?.let { { Text(it) } },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                // 把禁用态的颜色改成正常态，否则整个框会变灰、像不可用
                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledBorderColor = MaterialTheme.colorScheme.outline,
            ),
        )

        // 透明层接管点击，盖住输入框本体
        Box(
            Modifier
                .matchParentSize()
                .clickable { showPicker = true }
        )
    }

    if (showPicker) {
        val initialMillis = remember(value) {
            value?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
        }
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)

        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        onValueChange(
                            Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        )
                    }
                    showPicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = {
                    onValueChange(null)
                    showPicker = false
                }) { Text("清除") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/** LocalDate → 后端要的 ISO 字符串（yyyy-MM-dd）。null 保持 null。 */
fun LocalDate?.toIsoDate(): String? = this?.toString()

/** 后端返回的 ISO 字符串 → LocalDate。解析不了就当没填，不让脏数据把界面搞崩。 */
fun String?.toLocalDateOrNull(): LocalDate? =
    this?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
