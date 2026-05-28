package com.example.answerman

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity // 确保引入了此包以支持 registerForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

class ScreenshotRequestActivity : ComponentActivity() {

    private val requestMediaProjection = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            // 🌟 核心：把首次授权拿到的关键凭证，发送给 FloatingService
            val serviceIntent = Intent(this, FloatingService::class.java).apply {
                action = "ACTION_INIT_CHANNELS"
                putExtra("RESULT_CODE", result.resultCode)
                putExtra("RESULT_DATA", result.data)
            }
            startService(serviceIntent)
        }
        // 授权结束（无论成功失败），立刻关闭这个透明Activity
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 一启动就立马弹出系统录屏授权框
        val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        requestMediaProjection.launch(mediaProjectionManager.createScreenCaptureIntent())
    }
}