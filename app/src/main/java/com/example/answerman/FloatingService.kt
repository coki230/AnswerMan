package com.example.answerman

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream

class FloatingService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private lateinit var layoutParams: WindowManager.LayoutParams
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isPanelExpanded = false
    private var ballSize = 0
    private var panelDefaultHeight = 0 // 记录用户拉伸后的完美高度，方便最小化复原

    // 控件全局绑定引用
    private var layoutBall: View? = null
    private var layoutPanel: View? = null
    private var tvResultDisplay: TextView? = null
    private var scrollResultContainer: View? = null

    // =================== 长连接静默截图全局变量 ===================
    private var mMediaProjectionManager: MediaProjectionManager? = null
    private var mMediaProjection: MediaProjection? = null
    private var mVirtualDisplay: VirtualDisplay? = null
    private var mImageReader: ImageReader? = null
    private var isChannelInitialized = false // 标记通道是否已经建立
    // =============================================================

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
//        startForegroundNotification()

        floatingView = LayoutInflater.from(this).inflate(R.layout.activity_main, null)

        val density = resources.displayMetrics.density
        ballSize = (55 * density).toInt()

        val targetWindowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            ballSize,
            ballSize,
            targetWindowType
        )

        layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        layoutParams.format = PixelFormat.TRANSLUCENT
        layoutParams.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        layoutParams.type = targetWindowType

        try {
            windowManager.addView(floatingView, layoutParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        layoutBall = floatingView?.findViewById<View>(R.id.layoutBall)
        layoutPanel = floatingView?.findViewById<View>(R.id.layoutPanel)
        val btnCapture = floatingView?.findViewById<Button>(R.id.btnCaptureAndUpload)
        val btnMinimize = floatingView?.findViewById<Button>(R.id.btnMinimize)
        val btnClose = floatingView?.findViewById<Button>(R.id.btnCloseFloat)
        tvResultDisplay = floatingView?.findViewById<TextView>(R.id.tvResultDisplay)
        scrollResultContainer = floatingView?.findViewById<View>(R.id.scrollResultContainer)
        val layoutResizeHandle = floatingView?.findViewById<View>(R.id.layoutResizeHandle)

        // 点击小悬浮球 -> 展开
        layoutBall?.setOnClickListener {
            isPanelExpanded = true
            layoutBall?.visibility = View.GONE
            layoutPanel?.visibility = View.VISIBLE

            layoutParams.width = WindowManager.LayoutParams.MATCH_PARENT
            // 💡 恢复之前用户辛苦拖拽出来的完美高度
            if (panelDefaultHeight > 0) {
                layoutParams.height = panelDefaultHeight
            } else {
                layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT
            }
            layoutParams.gravity = Gravity.BOTTOM
            windowManager.updateViewLayout(floatingView, layoutParams)
        }

        // 点击收起 -> 变小球
        btnMinimize?.setOnClickListener {
            // 收起前，记录当前展开的实际高度
            if (layoutParams.height != WindowManager.LayoutParams.WRAP_CONTENT && layoutParams.height > 0) {
                panelDefaultHeight = layoutParams.height
            }

            isPanelExpanded = false
            layoutPanel?.visibility = View.GONE
            layoutBall?.visibility = View.VISIBLE

            layoutParams.width = ballSize
            layoutParams.height = ballSize
            layoutParams.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            windowManager.updateViewLayout(floatingView, layoutParams)
        }

        // 点击截图（安全防御升级版逻辑）
        btnCapture?.setOnClickListener {
            layoutPanel?.visibility = View.GONE

            if (isChannelInitialized) {
                // 情况 A：流通道已经建立完毕，非常安全，直接抓取当前帧
                mainHandler.postDelayed({
                    triggerSilentCapture()
                }, 400)
            } else {
                // 情况 B：长连接未初始化。为了防止旧 Token 凭证失效导致 SecurityException，
                // 只要未初始化过，一律清空悬浮窗并强制回炉 MainActivity 重新拉起系统合法的“立即开始”弹窗授权。
                if (floatingView != null && floatingView?.parent != null) {
                    try { windowManager.removeView(floatingView) } catch (e: Exception) {}
                }
                mainHandler.postDelayed({
                    val intent = Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra("TRIGGER_CAPTURE", true)
                    }
                    startActivity(intent)
                }, 50)
            }
        }

        btnClose?.setOnClickListener { stopSelf() }

        CaptureResultBridge.onCaptureActionDone = {
            mainHandler.post {
                if (floatingView != null && floatingView?.parent == null) {
                    try { windowManager.addView(floatingView, layoutParams) } catch (e: Exception) {}
                }
            }
        }

        // =================== 🌟 核心控制：手势向上拉高/向下拉矮界面（完美符合顶部上边界逻辑） ===================
        var initialY = 0f
        var initialHeight = 0

        layoutResizeHandle?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialY = event.rawY
                    initialHeight = layoutParams.height

                    if (initialHeight == WindowManager.LayoutParams.WRAP_CONTENT) {
                        initialHeight = floatingView?.height ?: 450
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // 面板卡在屏幕底边。手指向推（rawY变小，deltaY为负数），界面要向上“变高”
                    val deltaY = initialY - event.rawY
                    var newHeight = (initialHeight + deltaY).toInt()

                    // 安全边界限定：最矮 350 像素，最大不能高过整个手机屏幕的 80%
                    val maxHeight = (resources.displayMetrics.heightPixels * 0.8f).toInt()
                    if (newHeight < 350) newHeight = 350
                    if (newHeight > maxHeight) newHeight = maxHeight

                    // 1. 改变悬浮窗整体 Window 物理参数高度
                    layoutParams.height = newHeight
                    windowManager.updateViewLayout(floatingView, layoutParams)

                    // 2. 联动内部滚动文本容器区域，使其等比例拉伸
                    scrollResultContainer?.let {
                        val scrollParams = it.layoutParams
                        // 扣除掉上方控制按钮组和手柄大约占去的像素开销（约 130dp）
                        val targetScrollHeight = newHeight - (130 * resources.displayMetrics.density).toInt()
                        if (targetScrollHeight > 150) {
                            scrollParams.height = targetScrollHeight
                            it.layoutParams = scrollParams
                        }
                    }
                    true
                }
                else -> false
            }
        }
        // ===============================================================================================
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_INIT_CHANNELS") {
            val resultCode = intent.getIntExtra("RESULT_CODE", 0)
            val resultData = intent.getParcelableExtra<Intent>("RESULT_DATA")
            if (resultCode != 0 && resultData != null) {
                initCaptureChannel(resultCode, resultData)
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    // =================== 创建永不断开的视频流通道 ===================
    private fun initCaptureChannel(resultCode: Int, resultData: Intent) {
        startMediaProjectionForegroundNotification()
        releaseChannels()

        mMediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mMediaProjection = mMediaProjectionManager?.getMediaProjection(resultCode, resultData)

        // 🌟 规避 Android 14 崩溃：强行注入安全物理断开 Callback
        mMediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                releaseChannels()
            }
        }, mainHandler)

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val dpi = metrics.densityDpi

        mImageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        mVirtualDisplay = mMediaProjection?.createVirtualDisplay(
            "SilentCaptureChannel", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            mImageReader?.surface, null, null
        )

        isChannelInitialized = true
        layoutPanel?.visibility = View.VISIBLE
        triggerSilentCapture()
    }

    // =================== 静默长流抓帧提取 ===================
    private fun triggerSilentCapture() {
        val image = mImageReader?.acquireLatestImage()
        if (image != null) {
            var bitmap: Bitmap? = null
            try {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * image.width

                bitmap = Bitmap.createBitmap(
                    image.width + rowPadding / pixelStride,
                    image.height,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)

                layoutPanel?.visibility = View.VISIBLE
                tvResultDisplay?.text = "🚀 正在无痕静默截屏，并调用 Gemini 引擎深度解析中..."

                startGeminiAnalysis(bitmap)

            } catch (e: Exception) {
                e.printStackTrace()
                layoutPanel?.visibility = View.VISIBLE
                tvResultDisplay?.text = "❌ 画面截取发生了异常: ${e.localizedMessage}"
            } finally {
                image.close()
            }
        } else {
            val nextImage = mImageReader?.acquireNextImage()
            nextImage?.close()
            layoutPanel?.visibility = View.VISIBLE
            tvResultDisplay?.text = "💡 界面未发生实质变化，请点击屏幕任何地方再试一次"
        }
    }

    // =================== 标准树状 JSONObject 剥离解析（全量返回防截断） ===================
    private fun startGeminiAnalysis(bitmap: Bitmap) {
        Thread {
            try {
                val outputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
                val bytes = outputStream.toByteArray()
                val base64Data = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

                val apiKey = "AIzaSyDYFkrxsImXsO6GKBQITnAyJW_Le6m5iyM"
                val urlStr = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey"

                val jsonRequestBody = """
                    {
                        "contents": [{
                            "parts": [
                                {
                                    "inline_data": {
                                        "mime_type": "image/jpeg",
                                        "data": "$base64Data"
                                    }
                                },
                                {
                                    "text": "你是一个做题专家。请精准识别图片中的题目，并给出详细的解题思路和最终答案。"
                                }
                            ]
                        }]
                    }
                """.trimIndent()

                val url = java.net.URL(urlStr)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 15000
                conn.readTimeout = 15000

                conn.outputStream.use { os ->
                    val input = jsonRequestBody.toByteArray(charset("utf-8"))
                    os.write(input, 0, input.size)
                }

                val responseCode = conn.responseCode
                val responseText = if (responseCode == java.net.HttpURLConnection.HTTP_OK) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else {
                    conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "请求失败，错误码: $responseCode"
                }

                val finalResult = if (responseCode == java.net.HttpURLConnection.HTTP_OK) {
                    try {
                        val rootJson = org.json.JSONObject(responseText)
                        val candidates = rootJson.getJSONArray("candidates")
                        if (candidates.length() > 0) {
                            val content = candidates.getJSONObject(0).getJSONObject("content")
                            val parts = content.getJSONArray("parts")
                            if (parts.length() > 0) {
                                parts.getJSONObject(0).getString("text")
                            } else "未能解析出具体文字内容"
                        } else "未能获取有效的解题候选节点"
                    } catch (e: Exception) {
                        "数据已完整接收但JSON转换异常，结构可能有变，原始报文：\n$responseText"
                    }
                } else {
                    "服务器返回报错:\n$responseText"
                }

                mainHandler.post {
                    tvResultDisplay?.text = "【Gemini 智能题库解析】\n\n$finalResult"
                }

            } catch (e: Exception) {
                e.printStackTrace()
                mainHandler.post {
                    tvResultDisplay?.text = "❌ 请求执行阶段发生故障:\n${e.localizedMessage}"
                }
            } finally {
                bitmap.recycle()
            }
        }.start()
    }

    // 🌟 新增：让服务刚启动时以普通服务形态存活，完美绕过 API 36 的刚性录屏权限强杀
    private fun startNormalForegroundNotification() {
        val channelId = "float_ch_normal"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(channelId, "悬浮助手后台流", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(chan)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("答题悬浮助手已就绪")
            .setContentText("等待捕获屏幕...")
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .build()

        // 💡 重点：这里不传任何特殊的 foregroundServiceType，就是个纯洁的普通前台服务
        startForeground(201, notification)
    }

    // 🌟 修改：只有在 initCaptureChannel 真正拿到凭证的那一刻，才调用此方法将服务“动态升级”
    private fun startMediaProjectionForegroundNotification() {
        val channelId = "float_ch_media"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(channelId, "悬浮助手录屏流", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(chan)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("答题悬浮助手运行中")
            .setContentText("正在实时捕获屏幕数据")
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 💡 此时此刻，因为我们已经万事俱备（有了 Data 凭证），再升职为录屏前台服务，系统挑不出任何毛病
            startForeground(201, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(201, notification)
        }
    }

    private fun releaseChannels() {
        try { mVirtualDisplay?.release() } catch (e: Exception) {}
        try { mImageReader?.close() } catch (e: Exception) {}
        try { mMediaProjection?.stop() } catch (e: Exception) {}
        mVirtualDisplay = null
        mImageReader = null
        mMediaProjection = null
        isChannelInitialized = false
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        releaseChannels()
        if (floatingView != null && floatingView?.parent != null) {
            try { windowManager.removeView(floatingView) } catch (e: Exception) { e.printStackTrace() }
        }
    }
}