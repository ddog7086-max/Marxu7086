package com.example.padcombo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.padcombo.capture.PadService
import com.example.padcombo.core.PDBoard
import com.example.padcombo.core.PDBoardRect
import com.example.padcombo.core.PDRoute
import com.example.padcombo.overlay.RouteBoardView

class MainActivity : AppCompatActivity(), PadService.Listener {

    private lateinit var statusText: TextView
    private lateinit var preview: RouteBoardView
    private lateinit var toggleBtn: Button

    private lateinit var barX: SeekBar
    private lateinit var barY: SeekBar
    private lateinit var barW: SeekBar
    private lateinit var barH: SeekBar
    private lateinit var valX: TextView
    private lateinit var valY: TextView
    private lateinit var valW: TextView
    private lateinit var valH: TextView

    private val projectionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val intent = Intent(this, PadService::class.java).apply {
                putExtra(PadService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(PadService.EXTRA_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, intent)
            setRunning(true)
        } else {
            Toast.makeText(this, "未授权屏幕录制，助手未启动", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        preview = findViewById(R.id.preview)
        toggleBtn = findViewById(R.id.toggleBtn)
        barX = findViewById(R.id.barX)
        barY = findViewById(R.id.barY)
        barW = findViewById(R.id.barW)
        barH = findViewById(R.id.barH)
        valX = findViewById(R.id.valX)
        valY = findViewById(R.id.valY)
        valW = findViewById(R.id.valW)
        valH = findViewById(R.id.valH)

        loadCalibration()
        bindCalibration(barX, valX, { rect.x }, { v -> rect.x = v }, 0.0, 0.6)
        bindCalibration(barY, valY, { rect.y }, { v -> rect.y = v }, 0.0, 0.8)
        bindCalibration(barW, valW, { rect.width }, { v -> rect.width = v }, 0.2, 1.0)
        bindCalibration(barH, valH, { rect.height }, { v -> rect.height = v }, 0.1, 0.6)

        toggleBtn.setOnClickListener {
            if (PadService.instance != null) {
                stopService(Intent(this, PadService::class.java))
                setRunning(false)
            } else {
                startHelper()
            }
        }
    }

    private var rect: PDBoardRect = PDBoardRect.FALLBACK

    private fun loadCalibration() {
        val sp = getSharedPreferences(PadService.PREFS, Context.MODE_PRIVATE)
        rect = PDBoardRect(
            sp.getFloat(PadService.KEY_X, PDBoardRect.FALLBACK.x.toFloat()).toDouble(),
            sp.getFloat(PadService.KEY_Y, PDBoardRect.FALLBACK.y.toFloat()).toDouble(),
            sp.getFloat(PadService.KEY_W, PDBoardRect.FALLBACK.width.toFloat()).toDouble(),
            sp.getFloat(PadService.KEY_H, PDBoardRect.FALLBACK.height.toFloat()).toDouble(),
        )
    }

    private fun bindCalibration(
        bar: SeekBar,
        label: TextView,
        getter: () -> Double,
        setter: (Double) -> Unit,
        min: Double,
        max: Double,
    ) {
        val progress = ((getter() - min) / (max - min) * 1000).toInt()
        bar.progress = progress.coerceIn(0, 1000)
        label.text = String.format("%.3f", getter())
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val v = min + p / 1000.0 * (max - min)
                setter(v)
                label.text = String.format("%.3f", v)
                saveCalibration()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun saveCalibration() {
        getSharedPreferences(PadService.PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(PadService.KEY_X, rect.x.toFloat())
            .putFloat(PadService.KEY_Y, rect.y.toFloat())
            .putFloat(PadService.KEY_W, rect.width.toFloat())
            .putFloat(PadService.KEY_H, rect.height.toFloat())
            .apply()
    }

    private fun startHelper() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
            return
        }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun setRunning(running: Boolean) {
        toggleBtn.text = if (running) "停止助手" else "开启助手"
        statusText.text = if (running) "运行中：识别盘面并绘制路线" else "未开启。先授予悬浮窗权限，再点「开启助手」允许屏幕录制。"
    }

    override fun onResume() {
        super.onResume()
        PadService.instance?.setListener(this)
        if (PadService.instance != null) setRunning(true)
    }

    override fun onPause() {
        super.onPause()
        PadService.instance?.setListener(null)
    }

    override fun onBoard(board: PDBoard, route: PDRoute) {
        preview.show(board, route)
    }
}
