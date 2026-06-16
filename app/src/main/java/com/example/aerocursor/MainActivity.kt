package com.example.aerocursor

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.chip.Chip
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private var hidService: HidService? = null
    private var bound = false
    private lateinit var statusText: TextView
    private lateinit var controlZone: View
    private lateinit var modeChip: Chip
    private lateinit var controlHint: TextView

    // Touchpad and Tap State
    private var lastX = 0f
    private var lastY = 0f
    private var touchStartTime = 0L
    private var isPotentialTap = false
    private var pointerCount = 1

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as HidService.LocalBinder
            hidService = binder.getService()
            bound = true
            hidService?.statusListener = { status -> runOnUiThread { statusText.text = status } }
            updateUiForMode()
        }
        override fun onServiceDisconnected(name: ComponentName?) { hidService = null; bound = false }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        // Note: New ID in XML is control_card which contains the RelativeLayout we use for touch
        controlZone = findViewById(R.id.control_card) 
        controlHint = findViewById(R.id.control_hint)
        modeChip = findViewById(R.id.mode_chip)
        
        if (allPermissionsGranted()) startAndBindService() else requestPermissions()

        findViewById<Button>(R.id.select_pc_button).setOnClickListener { showPairedDevicesDialog() }
        findViewById<Button>(R.id.calibrate_button).setOnClickListener {
            hidService?.calibrate()
            vibrate(50)
            Toast.makeText(this, "Calibrated", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.inject_button).setOnClickListener { sendClipboard() }
        
        modeChip.setOnCheckedChangeListener { _, isChecked ->
            // isChecked true = Touchpad (initial state), false = Tilt
            val nextMode = if (isChecked) Constants.MODE_TOUCHPAD else Constants.MODE_TILT
            hidService?.currentMode = nextMode
            updateUiForMode()
            vibrate(40)
        }

        setupTouchHandling()
    }

    private fun sendClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString() ?: ""
            if (text.isNotEmpty()) {
                Toast.makeText(this, "Pasting to PC...", Toast.LENGTH_SHORT).show()
                hidService?.injectText(text)
                vibrate(100)
            } else {
                Toast.makeText(this, "Clipboard empty", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Nothing to paste", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateUiForMode() {
        val isTouchpad = hidService?.currentMode == Constants.MODE_TOUCHPAD
        modeChip.text = if (isTouchpad) "Touchpad" else "Tilt Mode"
        controlHint.text = if (isTouchpad) "SWIPE TO MOVE / TAP TO CLICK" else "POINT & TILT"
        findViewById<View>(R.id.calibrate_button).visibility = if (isTouchpad) View.GONE else View.VISIBLE
        findViewById<View>(R.id.click_labels_container)?.visibility = if (isTouchpad) View.GONE else View.VISIBLE
    }

    private fun setupTouchHandling() {
        controlZone.setOnTouchListener { _, event ->
            val x = event.x
            val y = event.y

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = x
                    lastY = y
                    touchStartTime = System.currentTimeMillis()
                    isPotentialTap = true
                    pointerCount = 1

                    if (hidService?.currentMode == Constants.MODE_TILT) {
                        if (x < controlZone.width / 2) hidService?.buttonState = 1 else hidService?.buttonState = 2
                        vibrate(20)
                    }
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    pointerCount = event.pointerCount
                    if (pointerCount > 2) isPotentialTap = false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (x - lastX)
                    val dy = (y - lastY)
                    
                    if (abs(dx) > 5 || abs(dy) > 5) {
                        isPotentialTap = false
                    }

                    if (hidService?.currentMode == Constants.MODE_TOUCHPAD) {
                        if (event.pointerCount == 1) {
                            // Touchpad mode uses relative move
                            hidService?.addTouchDelta(dx * 1.6f, dy * 1.8f)
                        }
                    }
                    lastX = x
                    lastY = y
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                    val duration = System.currentTimeMillis() - touchStartTime
                    
                    if (event.actionMasked == MotionEvent.ACTION_UP && isPotentialTap && duration < 250) {
                        if (pointerCount == 1) {
                            hidService?.sendButtonReport(1) // Left
                        } else if (pointerCount == 2) {
                            hidService?.sendButtonReport(2) // Right
                        }
                        vibrate(25)
                    }

                    if (event.pointerCount <= 1) {
                        hidService?.buttonState = 0
                    }
                    lastX = x
                    lastY = y
                }
            }
            true
        }
    }

    private fun vibrate(durationMs: Long) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION") vibrator.vibrate(durationMs)
        }
    }

    private fun startAndBindService() {
        val intent = Intent(this, HidService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) { Log.e("MainActivity", "Error starting service", e) }
    }

    private fun showPairedDevicesDialog() {
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = btManager.adapter ?: return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { requestPermissions(); return }
        }
        val pairedDevices = adapter.bondedDevices.toList()
        if (pairedDevices.isEmpty()) { Toast.makeText(this, "No paired devices found", Toast.LENGTH_LONG).show(); return }
        val deviceNames = pairedDevices.map { it.name ?: "Unknown Device" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Select your PC").setItems(deviceNames) { _, which ->
            hidService?.connect(pairedDevices[which])
        }.setNegativeButton("Cancel", null).show()
    }

    private fun allPermissionsGranted(): Boolean {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        return permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        if (permissions.isNotEmpty()) ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startAndBindService()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (bound) { unbindService(connection); bound = false }
    }
}
