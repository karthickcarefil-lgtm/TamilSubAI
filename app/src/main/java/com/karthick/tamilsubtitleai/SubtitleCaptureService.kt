package com.karthick.tamilsubtitleai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean

class SubtitleCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var overlay: TextView? = null
    private var windowManager: WindowManager? = null
    private var translator: Translator? = null
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private lateinit var worker: Handler
    private lateinit var thread: HandlerThread
    private val processing = AtomicBoolean(false)
    private var lastText = ""
    private var lastProcessedAt = 0L

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("subtitle-ocr").also { it.start() }
        worker = Handler(thread.looper)
        createNotificationChannel()
        showOverlay()
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.TAMIL)
            .build()
        translator = Translation.getClient(options)
        translator?.downloadModelIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(7, notification())
        val resultCode = intent?.getIntExtra("resultCode", 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= 33)
            intent?.getParcelableExtra("projectionData", Intent::class.java)
        else @Suppress("DEPRECATION") (intent?.getParcelableExtra("projectionData") as? Intent)
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, data)
        startCapture()
        return START_NOT_STICKY
    }

    private fun startCapture() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader?.setOnImageAvailableListener({ imageReader ->
            val now = System.currentTimeMillis()
            if (now - lastProcessedAt < 850 || !processing.compareAndSet(false, true)) {
                imageReader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            val image = imageReader.acquireLatestImage()
            if (image == null) { processing.set(false); return@setOnImageAvailableListener }
            lastProcessedAt = now
            processImage(image)
        }, worker)
        display = projection?.createVirtualDisplay(
            "TamilSubtitleAI", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader?.surface, null, worker
        )
    }

    private fun processImage(image: Image) {
        try {
            val bitmap = ImageUtils.imageToBitmap(image)
            image.close()
            // Scan only the lower portion where most OTT subtitle lines appear.
            val cropTop = (bitmap.height * 0.58f).toInt()
            val cropped = android.graphics.Bitmap.createBitmap(
                bitmap, 0, cropTop, bitmap.width, bitmap.height - cropTop
            )
            val input = InputImage.fromBitmap(cropped, 0)
            recognizer.process(input)
                .addOnSuccessListener { result ->
                    val candidate = result.textBlocks
                        .map { it.text.trim() }
                        .filter { it.length >= 3 }
                        .joinToString(" ")
                    if (candidate.isNotBlank() && candidate != lastText) {
                        lastText = candidate
                        translator?.translate(candidate)
                            ?.addOnSuccessListener { translated ->
                                if (translated.isNotBlank()) updateOverlay(translated)
                            }
                            ?.addOnFailureListener { updateOverlay("Translation unavailable. Check internet/model download.") }
                    }
                }
                .addOnFailureListener { }
                .addOnCompleteListener {
                    cropped.recycle()
                    bitmap.recycle()
                    processing.set(false)
                }
        } catch (_: Exception) {
            try { image.close() } catch (_: Exception) {}
            processing.set(false)
        }
    }

    private fun showOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        overlay = TextView(this).apply {
            text = "Tamil subtitles will appear here"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xDD111827.toInt())
            setPadding(18, 12, 18, 12)
            gravity = Gravity.CENTER
        }
        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") (WindowManager.LayoutParams.TYPE_PHONE)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 75
        }
        try { windowManager?.addView(overlay, params) } catch (_: Exception) {}
    }

    private fun updateOverlay(text: String) {
        worker.post {
            overlay?.text = text
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel("subtitle_live", "Live subtitle translation",
                NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(): Notification =
        NotificationCompat.Builder(this, "subtitle_live")
            .setContentTitle("Tamil Subtitle AI is running")
            .setContentText("Scanning visible subtitles and translating to Tamil")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()

    override fun onDestroy() {
        display?.release()
        reader?.close()
        projection?.stop()
        translator?.close()
        recognizer.close()
        try { overlay?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        thread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
