package com.example.padcombo.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
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
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import android.app.Service
import com.example.padcombo.R
import com.example.padcombo.core.Orb
import com.example.padcombo.core.OrbRecognizer
import com.example.padcombo.core.PDBoard
import com.example.padcombo.core.PDBoardRect
import com.example.padcombo.core.PDRoute
import com.example.padcombo.core.PDSolver
import com.example.padcombo.overlay.RouteBoardView
import com.example.padcombo.overlay.CalibrationFrameView
import com.example.padcombo.overlay.ColorKeyView
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * One foreground service does everything the iOS version needed two processes
 * for: MediaProjection grabs frames, the recognizer reads the 6x5 board, the SA
 * solver computes a route, and a floating overlay draws it above the game.
 */
class PadService : Service() {

    interface Listener {
        fun onBoard(board: PDBoard, route: PDRoute)
    }

    @Volatile
    private var listener: Listener? = null

    fun setListener(l: Listener?) {
        listener = l
    }

    private lateinit var windowManager: WindowManager
    private var overlay: RouteBoardView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var rootView: FrameLayout? = null
    private var controlBar: FrameLayout? = null
    private var colorKey: ColorKeyView? = null
    private var colorKeyBar: FrameLayout? = null
    private var calibrationFrame: CalibrationFrameView? = null
    private var alignMode = false

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var solverExecutor = Executors.newSingleThreadExecutor()
    private var lastCells: IntArray? = null
    private var lockedCells: IntArray? = null
    private var lockedRoute: PDRoute? = null
    private var forceResolve = false
    private var lastAnalysis = 0L

    private val analyzeTask = object : Runnable {
        override fun run() {
            analyze()
            mainHandler.postDelayed(this, ANALYZE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Drop any leftover overlay/state from a previous run so restarting does
        // not stack a second board on top of the old still-visible one.
        resetBeforeStart()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(), FOREGROUND_TYPE)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, buildNotification())
        }

        if (resultCode != 0 && data != null && projection == null) {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    teardown()
                }
            }, mainHandler)
            startCapture()
            addOverlay()
            // Static frame mode: no continuous polling. The user taps 分析 to
            // grab one frame, recognise it and draw the route once.
        }
        return START_NOT_STICKY
    }

    private fun startCapture() {
        val metrics: DisplayMetrics = resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection?.createVirtualDisplay(
            "padcombo-capture",
            w, h, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null,
        )
    }

    /**
     * Static frame mode: grab ONE latest frame, recognise it, solve and draw the
     * route once. No continuous polling, no lock loop. The fixed FALLBACK box
     * covers the whole bottom board so no manual alignment is needed.
     */
    private fun analyze() {
        val reader = imageReader ?: return
        val image = reader.acquireLatestImage() ?: return

        val bitmap = try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val padded = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride, image.height,
                Bitmap.Config.ARGB_8888,
            )
            padded.copyPixelsFromBuffer(buffer)
            val scale = min(1.0, 900.0 / padded.width)
            if (scale < 1.0) {
                Bitmap.createScaledBitmap(
                    padded, (padded.width * scale).toInt(),
                    (padded.height * scale).toInt(), true,
                ).also { if (it != padded) padded.recycle() }
            } else padded
        } catch (e: Exception) {
            null
        } finally {
            image.close()
        } ?: return

        // Use the saved calibration box if the user has aligned it; otherwise
        // fall back to an auto-fit square 6x5 grid.
        val sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val rect = if (sp.getBoolean("calibrated", false)) {
            loadRect()
        } else {
            PDBoardRect.fit(bitmap.width.toDouble(), bitmap.height.toDouble())
        }
        overlay?.boardRect = rect

        val board = OrbRecognizer.classify(bitmap, rect)
        bitmap.recycle()
        if (board == null) return

        // Guard: not enough recognised orbs means the frame does not show a full board.
        val filled = board.cells.count { it != Orb.EMPTY }
        if (filled < 24) return

        colorKey?.setBoard(board)

        solverExecutor.execute {
            val route = PDSolver.solve(board)
            mainHandler.post {
                overlay?.show(board, route)
                listener?.onBoard(board, route)
            }
        }
    }

    // ------------------------------------------------------------- overlay

    private fun addOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // --- Full-screen transparent route layer, touch-through so orbs stay
        // draggable. The grid maps onto the fixed FALLBACK full-board rectangle.
        val routeRoot = FrameLayout(this)
        val view = RouteBoardView(this)
        view.showOrbs = false
        view.routeAlpha = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_ALPHA, 245)
        routeRoot.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        // Draggable/resizable calibration frame; hidden until 对准 is tapped.
        val frame = CalibrationFrameView(this)
        frame.visibility = View.GONE
        frame.onChanged = { r ->
            saveRect(r)
            overlay?.boardRect = r
        }
        routeRoot.addView(
            frame,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        calibrationFrame = frame

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        )
        windowManager.addView(routeRoot, params)

        // --- Small control window (touchable), top-right: 对准 aligns the box,
        // 分析 grabs one frame; the slider sets route opacity.
        val bar = FrameLayout(this)
        val col = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        val alignBtn = Button(this).apply {
            text = "对准"
            textSize = 14f
            setPadding(24, 8, 24, 8)
            setOnClickListener { toggleAlign() }
        }
        val analyzeBtn = Button(this).apply {
            text = "分析"
            textSize = 14f
            setPadding(28, 8, 28,  8)
            setOnClickListener { analyze() }
        }
        // Stack buttons vertically: a horizontal row pushed 分析 off-screen right.
        val btnW = android.widget.LinearLayout.LayoutParams(
            150, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
        col.addView(alignBtn, btnW)
        col.addView(analyzeBtn, btnW)

        // Opacity slider: 80 (light) .. 255 (solid). Persisted and applied live.
        val sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val alphaBar = android.widget.SeekBar(this).apply {
            max = 175
            progress = sp.getInt(KEY_ALPHA, 245) - 80
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                    val a = p + 80
                    view.routeAlpha = a
                    view.invalidate()
                    sp.edit().putInt(KEY_ALPHA, a).apply()
                }
                override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
            })
        }
        col.addView(alphaBar, android.widget.LinearLayout.LayoutParams(
            220, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        bar.addView(col, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ))
        val barParams = WindowManager.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        barParams.gravity = Gravity.TOP or Gravity.END
        barParams.x = 8
        barParams.y = 40
        windowManager.addView(bar, barParams)

        // --- Standalone colour key window, fixed at the screen's top-left corner,
        // touch-through: never sits inside the route box or covers an orb.
        val keyView = ColorKeyView(this)
        val keyBar = FrameLayout(this)
        val kd = resources.displayMetrics.density
        keyBar.addView(
            keyView,
            FrameLayout.LayoutParams((162 * kd).toInt(), (66 * kd).toInt()),
        )
        val keyParams = WindowManager.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        )
        keyParams.gravity = Gravity.TOP or Gravity.START
        keyParams.x = 8
        keyParams.y = 8
        windowManager.addView(keyBar, keyParams)

        overlay = view
        overlayParams = params
        rootView = routeRoot
        controlBar = bar
        colorKey = keyView
        colorKeyBar = keyBar
        alignMode = false
    }

    /** Toggle align mode: show the draggable/resizable frame to align the box. */
    private fun toggleAlign() {
        alignMode = !alignMode
        val params = overlayParams ?: return
        val root = rootView ?: return
        val frame = calibrationFrame ?: return
        if (alignMode) {
            val sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            frame.rect = if (sp.getBoolean("calibrated", false)) {
                loadRect()
            } else {
                PDBoardRect.fit(
                    resources.displayMetrics.widthPixels.toDouble(),
                    resources.displayMetrics.heightPixels.toDouble(),
                )
            }
            frame.visibility = View.VISIBLE
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            frame.visibility = View.GONE
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        windowManager.updateViewLayout(root, params)
    }

    /** Persist the aligned normalised board box so every analysis reuses it. */
    private fun saveRect(r: PDBoardRect) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_X, r.x.toFloat())
            .putFloat(KEY_Y, r.y.toFloat())
            .putFloat(KEY_W, r.width.toFloat())
            .putFloat(KEY_H, r.height.toFloat())
            .putBoolean("calibrated", true)
            .apply()
    }

    // ------------------------------------------------------------- rect

    private fun loadRect(): PDBoardRect {
        val sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return PDBoardRect(
            sp.getFloat(KEY_X, PDBoardRect.FALLBACK.x.toFloat()).toDouble(),
            sp.getFloat(KEY_Y, PDBoardRect.FALLBACK.y.toFloat()).toDouble(),
            sp.getFloat(KEY_W, PDBoardRect.FALLBACK.width.toFloat()).toDouble(),
            sp.getFloat(KEY_H, PDBoardRect.FALLBACK.height.toFloat()).toDouble(),
        )
    }

    private fun buildNotification(): Notification {
        val channelId = "padcombo"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "PadCombo", NotificationManager.IMPORTANCE_LOW),
            )
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("PadCombo 运行中")
            .setContentText("正在识别盘面并绘制路线")
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
    }

    /** Remove any previous overlay/state before a fresh start. */
    private fun resetBeforeStart() {
        mainHandler.removeCallbacks(analyzeTask)
        lockedCells = null
        lockedRoute = null
        detachOverlays()
        solverExecutor.shutdownNow()
        solverExecutor = Executors.newSingleThreadExecutor()
    }

    /** Remove the real root views added to WindowManager (not the inner RouteBoardView). */
    private fun detachOverlays() {
        rootView?.let { runCatching { windowManager.removeView(it) } }
        controlBar?.let { runCatching { windowManager.removeView(it) } }
        colorKeyBar?.let { runCatching { windowManager.removeView(it) } }
        rootView = null
        controlBar = null
        colorKeyBar = null
        colorKey = null
        overlay = null
        calibrationFrame = null
        alignMode = false
    }

    private fun teardown() {
        mainHandler.removeCallbacks(analyzeTask)
        lockedCells = null
        lockedRoute = null
        solverExecutor.shutdownNow()
        detachOverlays()
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projection?.stop()
        projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        instance = null
        teardown()
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: PadService? = null
            private set

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        private const val NOTIF_ID = 42
        private const val ANALYZE_INTERVAL_MS = 900L

        /** Cells allowed to differ before we treat the board as changed. */
        private const val STABLE_CELL_TOLERANCE = 3
        private const val FOREGROUND_TYPE =
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION

        const val PREFS = "padcombo_calibration"
        const val KEY_X = "rect_x"
        const val KEY_Y = "rect_y"
        const val KEY_W = "rect_w"
        const val KEY_H = "rect_h"
        const val KEY_ALPHA = "route_alpha"
    }
}
