package com.timelapse.camera

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.timelapse.camera.camera.CameraEnumerator
import com.timelapse.camera.databinding.ActivityMainBinding
import com.timelapse.camera.ui.gallery.GalleryFragment
import com.timelapse.camera.ui.preview.PreviewFragment
import com.timelapse.camera.ui.settings.SettingsFragment
import com.timelapse.camera.ui.status.StatusFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 主 Activity —— 底部导航 + 4 个 Fragment 切换。
 *
 * 架构说明：
 * - 单一 Activity 架构，所有页面都是 Fragment
 * - BottomNavigationView 驱动 Fragment 切换
 * - 默认显示「状态」Tab（用户最常看的）
 *
 * 教学要点：
 * - 底部导航配合 Fragment 的标准模式
 * - 单一 Activity 多 Fragment 的架构思路
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val statusFragment by lazy { StatusFragment.newInstance() }
    private val previewFragment by lazy { PreviewFragment.newInstance() }
    private val galleryFragment by lazy { GalleryFragment.newInstance() }
    private val settingsFragment by lazy { SettingsFragment.newInstance() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 默认显示状态页
        if (savedInstanceState == null) {
            switchFragment(statusFragment)
            binding.bottomNav.selectedItemId = R.id.nav_status

            // 启动时枚举所有摄像头，输出到日志（方便排查分辨率问题）
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { CameraEnumerator.enumerate(this@MainActivity) }
            }
        }

        // 底部导航点击事件
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_status -> switchFragment(statusFragment)
                R.id.nav_preview -> switchFragment(previewFragment)
                R.id.nav_gallery -> switchFragment(galleryFragment)
                R.id.nav_settings -> switchFragment(settingsFragment)
            }
            true
        }
    }

    /**
     * 切换 Fragment。
     * 使用 replace 而不是 hide/show：
     * - 代码更简洁，教学更清晰
     * - 切换时 Fragment 重建，保证每次进入页面数据都是最新的
     * - 代价是切换时会重建视图，但对这个 App 来说完全可接受
     */
    private fun switchFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }
}
