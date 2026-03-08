package com.example.flowpilot.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Base64
import android.util.DisplayMetrics
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ScreenCaptureManager(private val context: Context) {
    suspend fun captureScreenshotDataUrl(resultCode: Int, data: Intent): String {
        val serviceIntent = Intent(context, MediaProjectionForegroundService::class.java)
        ContextCompat.startForegroundService(context, serviceIntent)
        waitForProjectionForegroundService()

        val mediaProjectionManager =
            context.getSystemService(MediaProjectionManager::class.java)
                ?: throw IllegalStateException("MediaProjectionManager unavailable")

        val mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException("Failed to obtain MediaProjection")

        val displayMetrics: DisplayMetrics = context.resources.displayMetrics
        val width = displayMetrics.widthPixels.coerceAtLeast(1)
        val height = displayMetrics.heightPixels.coerceAtLeast(1)
        val densityDpi = displayMetrics.densityDpi

        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val handlerThread = HandlerThread("flowpilot-capture")
        handlerThread.start()
        val handler = Handler(handlerThread.looper)

        var projectionCallback: MediaProjection.Callback? = null
        var virtualDisplay: VirtualDisplay? = null

        val cleaned = AtomicBoolean(false)
        fun cleanup() {
            if (!cleaned.compareAndSet(false, true)) return
            try {
                projectionCallback?.let { mediaProjection.unregisterCallback(it) }
            } catch (_: Exception) {
            }
            try {
                virtualDisplay?.release()
            } catch (_: Exception) {
            }
            try {
                imageReader.close()
            } catch (_: Exception) {
            }
            try {
                mediaProjection.stop()
            } catch (_: Exception) {
            }
            try {
                handlerThread.quitSafely()
            } catch (_: Exception) {
            }
            try {
                context.stopService(serviceIntent)
            } catch (_: Exception) {
            }
        }

        projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                cleanup()
            }
        }
        mediaProjection.registerCallback(projectionCallback, handler)

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "FlowPilotCapture",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            handler
        ) ?: throw IllegalStateException("Failed to create VirtualDisplay")

        return suspendCancellableCoroutine { continuation ->
            val timeoutHandler = Handler(context.mainLooper)
            val timeoutRunnable = Runnable {
                if (continuation.isActive) {
                    cleanup()
                    continuation.resumeWithException(
                        IllegalStateException("Timed out waiting for screenshot")
                    )
                }
            }
            timeoutHandler.postDelayed(timeoutRunnable, 8_000)

            imageReader.setOnImageAvailableListener({ reader ->
                val image: Image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val bitmap = image.toBitmap(width, height)
                    val pngBytes = ByteArrayOutputStream().use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        out.toByteArray()
                    }
                    bitmap.recycle()
                    val encoded = Base64.encodeToString(pngBytes, Base64.NO_WRAP)

                    timeoutHandler.removeCallbacks(timeoutRunnable)
                    cleanup()
                    if (continuation.isActive) {
                        continuation.resume("data:image/png;base64,$encoded")
                    }
                } catch (error: Exception) {
                    timeoutHandler.removeCallbacks(timeoutRunnable)
                    cleanup()
                    if (continuation.isActive) {
                        continuation.resumeWithException(error)
                    }
                } finally {
                    image.close()
                }
            }, handler)

            continuation.invokeOnCancellation {
                timeoutHandler.removeCallbacks(timeoutRunnable)
                cleanup()
            }
        }
    }

    private fun waitForProjectionForegroundService() {
        val deadline = SystemClock.elapsedRealtime() + 4_000
        while (!MediaProjectionForegroundService.isRunning && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(50)
        }

        if (!MediaProjectionForegroundService.isRunning) {
            throw IllegalStateException("Screen capture service did not enter foreground state in time")
        }
    }
}

private fun Image.toBitmap(width: Int, height: Int): Bitmap {
    val plane = planes[0]
    val buffer = plane.buffer
    val pixelStride = plane.pixelStride
    val rowStride = plane.rowStride
    val rowPadding = rowStride - pixelStride * width

    val bitmap = Bitmap.createBitmap(
        width + rowPadding / pixelStride,
        height,
        Bitmap.Config.ARGB_8888
    )
    bitmap.copyPixelsFromBuffer(buffer)

    return Bitmap.createBitmap(bitmap, 0, 0, width, height).also {
        bitmap.recycle()
    }
}
