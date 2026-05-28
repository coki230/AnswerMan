package com.example.answerman

import android.content.Intent

object CaptureResultBridge {
    // 监听截图成功（带路径）
    var onCaptureSuccess: ((String) -> Unit)? = null

    // 💡 新增：专门用来通知悬浮窗“截图动作已完成，可以恢复显示了”
    var onCaptureActionDone: (() -> Unit)? = null

    // 缓存的录屏凭证
    var savedCaptureIntent: Intent? = null
}