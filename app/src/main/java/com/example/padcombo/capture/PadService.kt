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
    private var alignMode = false

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var solverExecutor = Executors.newSingleThreadExecutor()
    private var lastCells: IntArray? = null
    private var lockedCells: IntArray? = null
    private var lockedRoute: PDRoute? = null
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
            mainHandler.post(analyzeTask)
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

    private fun analyze() {
        val reader = imageReader ?: return
        val now = System.currentTimeMillis()
        if (now - lastAnalysis < ANALYZE_INTERVAL_MS) return
        val image = reader.acquireLatestImage() ?: return
        lastAnalysis = now

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

        // The floating window itself IS the capture box: move it to the orb board
        // and drag its bottom-right corner to resize; its on-screen rect becomes
        // the normalised region passed to the recognizer.
        val pm = overlayParams ?: return
        val metrics = resources.displayMetrics
        val rect = PDBoardRect(
            pm.x.toDouble() / metrics.widthPixels,
            pm.y.toDouble() / metrics.heightPixels,
            pm.width.toDouble() / metrics.widthPixels,
            pm.height.toDouble() / metrics.heightPixels,
        )
        val board = OrbRecognizer.classify(bitmap, rect)
        bitmap.recycle()
        if (board == null) return

        // Guard: if the box does not cover the whole 6x5 board (too many empty
        // cells because it sits on a boss / background), do not solve or update
        // the route — keep the last valid one instead of drawing garbage.
        val filled = board.cells.count { it != Orb.EMPTY }
        if (filled < 24) return

        // Lock onto the first board and keep showing its best combo route. Only
        // re-solve when the board actually changes; a couple of misread cells
        // (recognition noise) must not flip the route every tick.
        val current = board.cells
        val locked = lockedCells
        if (locked != null && lockedRoute != null) {
            var diff = 0
            for (i in current.indices) if (current[i] != locked[i]) diff++
            if (diff <= STABLE_CELL_TOLERANCE) return
        }

        solverExecutor.execute {
            val route = PDSolver.solve(board)
            mainHandler.post {
                lockedCells = current.copyOf()
                lockedRoute = route
                overlay?.show(board, route)
                listener?.onBoard(board, route)
            }
        }
    }

    // ------------------------------------------------------------- overlay

    private fun addOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = resources.displayMetrics
        // Start the transparent box directly over the bottom orb board (FALLBACK),
        // not up on the boss. In align mode the user drags/resizes it to frame the
        // 6x5 grid; otherwise the layer is touch-through so orbs stay draggable.
        val fb = PDBoardRect.FALLBACK
        val width = (metrics.widthPixels * fb.width).toInt()
        val height = (metrics.heightPixels * fb.height).toInt()

        // --- Route layer: transparent, touch-through by default so the game orb
        // remains draggable. Only becomes draggable when align mode is on.
        val routeRoot = FrameLayout(this)
        val view = RouteBoardView(this)
        view.showOrbs = false // overlay: draw route only, over the real orbs
        routeRoot.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        val params = WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = (metrics.widthPixels * fb.x).toInt()
        params.y = (metrics.heightPixels * fb.y).toInt()
        windowManager.addView(routeRoot, params)

        // --- Small separate control window (touchable), top-right corner:
        // [对准] toggles drag/resize, [重算] forces a re-solve.
        val bar = FrameLayout(this)
        val alignBtn = Button(this).apply {
            text = "对准"
            textSize = 12f
            setPadding(24, 6, 24, 6)
            setOnClickListener { toggleAlign() }
        }
        val rerun = Button(this).apply {
            text = "重算"
            textSize = 12f
            setPadding(24, 6, 24, 6)
            setOnClickListener {
                lockedCells = null
                lockedRoute = null
            }
        }
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            addView(alignBtn)
            addView(rerun)
        }
        bar.addView(row, FrameLayout.LayoutParams(
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

        overlay = view
        overlayParams = params
        rootView = routeRoot
        controlBar = bar
        alignMode = false
    }

    /** Toggle align mode: on = route layer draggable/resizable; off = touch-through. */
    private fun toggleAlign() {
        alignMode = !alignMode
        val params = overlayParams ?: return
        val root = rootView ?: return
        if (alignMode) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            root.setOnTouchListener(OverlayDrag(params, windowManager))
        } else {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            root.setOnTouchListener(null)
        }
        windowManager.updateViewLayout(root, params)
    }

    private class OverlayDrag(
        private val params: WindowManager.LayoutParams,
        private val wm: WindowManager,
    ) : View.OnTouchListener {
        private var startX = 0f
        private var startY = 0f
        private var startParamX = 0
        private var startParamY = 0
        private var startW = 0
        private var startH = 0
        private var mode = 0 // 0 = move, 1 = resize (bottom-right handle)
        private val handle = 56

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (event.x > v.width - handle && event.y > v.height - handle) {
                        mode = 1
                        startW = params.width
                        startH = params.height
                    } else {
                        mode = 0
                        startParamX = params.x
                        startParamY = params.y
                    }
                    startX = event.rawX
                    startY = event.rawY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (mode == 1) {
                        // Lock the 6:5 (cols:rows) aspect so the grid never stretches
                        // out of shape; width drives height = width * 5/6.
                        val newW = (startW + (event.rawX - startX)).toInt().coerceAtLeast(200)
                        params.width = newW
                        params.height = (newW * 5f / 6f).toInt()
                    } else {
                        params.x = startParamX + (event.rawX - startX).toInt()
                        params.y = startParamY + (event.rawY - startY).toInt()
                    }
                    wm.updateViewLayout(v, params)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    mode = 0
                    return true
                }
            }
            return false
        }
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
        rootView = null
        controlBar = null
        overlay = null
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
    }
}
