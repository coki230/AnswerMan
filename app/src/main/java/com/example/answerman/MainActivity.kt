package com.example.answerman

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CODE_SCREEN_CAPTURE = 1002
        private const val REQUEST_CODE_OVERLAY_PERMISSION = 1234
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 💡 注意：这里彻底删除了 setContentView(R.layout.activity_main)
        // 因为真实的界面现在由 FloatingService 挂载到全系统最上层

        // 1. 检查是不是悬浮窗组件（FloatingService）点击按钮后，发暗号让我们帮它申请录屏权限
        if (intent.getBooleanExtra("TRIGGER_CAPTURE", false)) {
            triggerScreenCapturePermission()
            return
        }

        // 2. 如果是常规从桌面图标打开 App：
        // 检查是否拥有悬浮窗（覆盖在其他应用上方）的特殊系统权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            // 没有权限，引导用户去系统设置页勾选
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, REQUEST_CODE_OVERLAY_PERMISSION)
        } else {
            // 已经有权限了，直接把真正的悬浮服务拉起来，然后自己迅速自毁
            startFloatingServiceAndFinish()
        }
    }

    /**
     * 启动真正承载半透明悬浮面板的后台常驻服务，并关闭当前的透明调度 Activity
     */
    private fun startFloatingServiceAndFinish() {
        val serviceIntent = Intent(this, FloatingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        // 🚀 核心：直接自毁，确保桌面上绝对不会有一个大实体界面挡住做题 App
        finish()
    }

    /**
     * 呼出系统标准的“应用将开始截取您的屏幕”的安全授权弹窗
     */
    private fun triggerScreenCapturePermission() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CODE_SCREEN_CAPTURE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // 判定 A：用户勾选完悬浮窗权限后返回
        if (requestCode == REQUEST_CODE_OVERLAY_PERMISSION) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                startFloatingServiceAndFinish()
            } else {
                // 如果用户拒绝了，这里可以弹一个普通的 Toast 提示，随后自毁
                android.widget.Toast.makeText(this, "需要悬浮窗权限才能显示结果框", android.widget.Toast.LENGTH_LONG).show()
                finish()
            }
            return
        }

        // 判定 B：用户点击了系统的“立即开始”录屏授权
        if (requestCode == REQUEST_CODE_SCREEN_CAPTURE) {
            if (resultCode == Activity.RESULT_OK && data != null) {

                // 💡 1. 把这次好不容易申请到的凭证，死死存在保管箱里，供后面无数次静默复用！
                CaptureResultBridge.savedCaptureIntent = data

                // 💡 2. 权限一旦到手，立刻把这个闪现出来的 Activity 推到最后台，让目标题目界面暴露出来
                moveTaskToBack(true)

                // 💡 3. 彻底抛弃旧的 MediaProjectionService，直接把凭证用“ACTION_INIT_CHANNELS”发给 FloatingService 建立长连接
                val channelIntent = Intent(this, FloatingService::class.java).apply {
                    action = "ACTION_INIT_CHANNELS"
                    putExtra("RESULT_CODE", resultCode)
                    putExtra("RESULT_DATA", data) // 注入新鲜的授权钥匙
                }

                // 启动前台服务（保证 Android 14+ 兼容性）
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(channelIntent)
                } else {
                    startService(channelIntent)
                }

                // 💡 4. 触发你原本的悬浮窗复原回调（把之前 removeView 移除的悬浮窗重新加回来）
                CaptureResultBridge.onCaptureActionDone?.invoke()
            } else {
                // 如果用户点拒绝了，也通知悬浮窗复原（否则悬浮窗就消失了）
                CaptureResultBridge.onCaptureActionDone?.invoke()
            }

            // 录屏中转闪现结束，自毁，将不粘手的舞台彻底交回给悬浮面板和做题 App
            finish()
        }
    }

}