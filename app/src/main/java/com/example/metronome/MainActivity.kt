package com.example.metronome

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.metronome.data.Language
import com.example.metronome.data.SettingsRepository
import com.example.metronome.ui.screens.MainScreen
import com.example.metronome.ui.theme.MetronomeTheme
import com.example.metronome.vm.MetronomeViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 应用入口 Activity。
 *
 * 关键点：
 * - 不使用 appcompat，直接继承 [ComponentActivity]，完全 Compose 化。
 * - [attachBaseContext] 读取持久化语言，通过 [createConfigurationContext] 覆盖 Locale，
 *   使 string.xml 资源按用户选择语言加载（中/英）。
 * - 启动时按需请求 POST_NOTIFICATIONS（API33+），以满足"状态栏通知暂停/继续"硬需求。
 * - 监听 ViewModel 的 recreate 信号：语言切换后重建以应用新 Locale。
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MetronomeViewModel by viewModels()

    // 通知权限请求：用户可同意/拒绝；拒绝仅影响通知按钮可见，不影响后台播放
    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 结果忽略：拒绝时引擎照常播放，仅通知不可见 */ }

    override fun attachBaseContext(newBase: Context) {
        // 读持久化语言并覆盖 Locale（无需 appcompat）
        val prefs = newBase.getSharedPreferences(
            SettingsRepository.PREFS_NAME, Context.MODE_PRIVATE
        )
        val langCode = prefs.getString(SettingsRepository.KEY_LANGUAGE, Language.ZH.code)
            ?: Language.ZH.code
        val locale = Locale(langCode)
        Locale.setDefault(locale)
        val cfg = Configuration(newBase.resources.configuration)
        cfg.setLocale(locale)
        super.attachBaseContext(newBase.createConfigurationContext(cfg))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // API33+ 请求通知权限（满足状态栏暂停/继续控制）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // 监听语言切换信号 → recreate 以重新走 attachBaseContext
        lifecycleScope.launch {
            viewModel.recreateSignal.collectLatest { recreate() }
        }

        setContent {
            MetronomeTheme {
                MainScreen(vm = viewModel)
            }
        }
    }
}
