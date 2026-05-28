package com.example.answerman

import android.app.Activity
import android.app.Notification
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
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream

class MediaProjectionService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private fun promoteToForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "screen_capture_channel", "屏幕截取服务", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, "screen_capture_channel")
            .setContentTitle("屏幕解析助手")
            .setContentText("屏幕安全通道保护中...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(102, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(102, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        val resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
        val data: Intent? = intent.getParcelableExtra("data")

        // 💡 从 Intent 中直接取出 Activity 传过来的分辨率
        val screenWidth = intent.getIntExtra("screenWidth", 1080)
        val screenHeight = intent.getIntExtra("screenHeight", 1920)
        val screenDensity = intent.getIntExtra("screenDensity", 420)

        if (resultCode == Activity.RESULT_OK && data != null) {
            promoteToForeground()

            Handler(Looper.getMainLooper()).postDelayed({
                // 💡 把分辨率直接喂给截屏方法
                startScreenCapture(resultCode, data, screenWidth, screenHeight, screenDensity)
            }, 100)
        }

        return START_NOT_STICKY
    }

    private fun startScreenCapture(resultCode: Int, data: Intent, screenWidth: Int, screenHeight: Int, screenDensity: Int) {
        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)

            // 💡 核心修复：Android 14 强制要求在 createVirtualDisplay 之前注册 Callback
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    // 如果用户在系统层主动取消了投影授权，这里可以做一些资源释放
                }
            }, Handler(Looper.getMainLooper())) // 绑定在主线程 Handler 上

            // 💡 纯粹的资源创建，再无任何隐式 Display 调用
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ServiceCapture", screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface, null, null
            )

            Handler(Looper.getMainLooper()).postDelayed({
                captureAndSave(screenWidth, screenHeight)
            }, 300)

        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
    }


    private fun captureAndSave(screenWidth: Int, screenHeight: Int) {
        try {
            val image = imageReader?.acquireLatestImage() ?: return
            // 💡 在你保存 Bitmap 或创建图片成功的地方加入这一行：
            CaptureResultBridge.onCaptureActionDone?.invoke()

            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            var bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
            image.close()

            // 彻底释放录屏资源
            virtualDisplay?.release()
            mediaProjection?.stop()

            // 落地保存图片
            val file = File(getExternalFilesDir(null), "upload_cache.png")
            val fos = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, fos)
            fos.flush()
            fos.close()

            // 🚀 通知广播或者直接启动 Activity 回传数据。由于我们要刷新 Activity 的结果，发送一条本地广播
            val resultIntent = Intent("com.example.answerman.SCREEN_CAPTURE_SUCCESS")
            resultIntent.putExtra("imagePath", file.absolutePath)
            CaptureResultBridge.onCaptureSuccess?.invoke(file.absolutePath)

        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            stopSelf() // 活干完了，自觉关闭前台服务
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        virtualDisplay?.release()
        mediaProjection?.stop()
    }
}