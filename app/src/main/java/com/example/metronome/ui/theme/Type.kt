package com.example.metronome.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// 默认 Material3 排版（保持简洁）。
val MetronomeTypography = Typography()

// 大字体 BPM 显示样式：粗大、居中。
val BpmDisplay = TextStyle(
    fontSize = 112.sp,
    fontWeight = FontWeight.Black,
    letterSpacing = (-2).sp,
)
