package com.example.aerocursor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.*
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Timer
import java.util.concurrent.Executors
import kotlin.concurrent.fixedRateTimer

class HidService : Service(), SensorEventListener {

    private val binder = LocalBinder()
    private lateinit var sensorManager: SensorManager
    private var motionSensor: Sensor? = null
    private var isUsingGyro = true
    
    private var hidDevice: BluetoothHidDevice? = null
    private var connectedDevice: BluetoothDevice? = null
    private var reportTimer: Timer? = null

    private val sensorLock = Any()
    private var sensorX = 0f
    private var sensorY = 0f
    private var sensorZ = 0f

    @Volatile var buttonState: Byte = 0
    @Volatile var currentMode = Constants.MODE_TILT
    
    // Smooth Touchpad Accumulation
    private val touchLock = Any()
    private var touchDxAccum = 0f
    private var touchDyAccum = 0f

    private val converter = GyroToMouseConverter()
    private val handler = Handler(Looper.getMainLooper())
    private val keyboardExecutor = Executors.newSingleThreadExecutor()

    var statusListener: ((String) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): HidService = this@HidService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d("HidService", "onCreate")
        createNotificationChannel()
        startForeground(Constants.NOTIFICATION_ID, buildNotification("Initializing..."))

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        
        motionSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (motionSensor != null) {
            isUsingGyro = true
        } else {
            motionSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            isUsingGyro = false
        }

        if (motionSensor == null) {
            updateStatus("No motion sensors found!")
        }

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = btManager.adapter
        if (adapter != null) {
            try {
                if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    adapter.name = "AeroCursor Pro"
                }
            } catch (e: Exception) {}

            adapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                    if (profile == BluetoothProfile.HID_DEVICE) {
                        hidDevice = proxy as BluetoothHidDevice
                        registerHid()
                    }
                }
                override fun onServiceDisconnected(profile: Int) {
                    if (profile == BluetoothProfile.HID_DEVICE) {
                        hidDevice = null
                    }
                }
            }, BluetoothProfile.HID_DEVICE)
        }
    }

    private fun registerHid() {
        val sdpSettings = BluetoothHidDeviceAppSdpSettings(
            "AeroCursor", "AeroCursor Pro", "AeroCursor Team",
            BluetoothHidDevice.SUBCLASS1_MOUSE, HidReportDescriptor.comboDescriptor
        )

        val qosSettings = BluetoothHidDeviceAppQosSettings(
            1, // BEST_EFFORT
            800, 9, 0, 11250, BluetoothHidDeviceAppQosSettings.MAX
        )

        try { hidDevice?.unregisterApp() } catch (e: Exception) {}

        hidDevice?.registerApp(
            sdpSettings, null, qosSettings,
            Executors.newSingleThreadExecutor(),
            object : BluetoothHidDevice.Callback() {
                override fun onAppStatusChanged(device: BluetoothDevice?, registered: Boolean) {
                    if (registered) updateStatus(if (isUsingGyro) "Ready to connect" else "Ready (Tilt mode)")
                }

                override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
                    when (state) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            connectedDevice = device
                            updateStatus("Connected")
                            startSensorAndReports()
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            connectedDevice = null
                            stopSensorAndReports()
                            updateStatus("Disconnected")
                        }
                    }
                }

                override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
                    if (type == BluetoothHidDevice.REPORT_TYPE_INPUT) {
                        if (id == 2.toByte()) dispatchTimedReport()
                        else if (id == 1.toByte()) hidDevice?.sendReport(device, 1, ByteArray(8))
                    }
                }
                override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {}
            }
        )
    }

    fun connect(device: BluetoothDevice) {
        if (hidDevice == null) return
        calibrate()
        updateStatus("Connecting...")
        hidDevice?.connect(device)
    }

    fun addTouchDelta(dx: Float, dy: Float) {
        synchronized(touchLock) {
            touchDxAccum += dx
            touchDyAccum += dy
        }
    }

    fun injectText(text: String) {
        val device = connectedDevice ?: return
        keyboardExecutor.execute {
            for (char in text) {
                val (keyCode, shift) = KeyboardHelper.getHidKey(char)
                if (keyCode != 0.toByte()) {
                    // Key Down (8 bytes)
                    hidDevice?.sendReport(device, 1, byteArrayOf(shift, 0, keyCode, 0, 0, 0, 0, 0))
                    Thread.sleep(35)
                    // Key Up (8 bytes)
                    hidDevice?.sendReport(device, 1, byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0))
                    Thread.sleep(35)
                }
            }
        }
    }

    fun sendButtonReport(button: Byte) {
        val device = connectedDevice ?: return
        hidDevice?.sendReport(device, 2, byteArrayOf(button, 0, 0))
        handler.postDelayed({
            hidDevice?.sendReport(device, 2, byteArrayOf(0, 0, 0))
        }, 30)
    }

    private fun updateStatus(text: String) {
        statusListener?.invoke(text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(Constants.NOTIFICATION_ID, buildNotification(text))
    }

    private fun startSensorAndReports() {
        motionSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        reportTimer?.cancel()
        reportTimer = fixedRateTimer("mouse_reporter", false, 0L, Constants.REPORT_INTERVAL_MS) {
            dispatchTimedReport()
        }
    }

    private fun dispatchTimedReport() {
        val device = connectedDevice ?: return
        var dx = 0; var dy = 0

        if (currentMode == Constants.MODE_TILT) {
            var x: Float; var y: Float; var z: Float
            synchronized(sensorLock) { x = sensorX; y = sensorY; z = sensorZ }
            val dt = Constants.REPORT_INTERVAL_MS / 1000f
            val deltas = if (isUsingGyro) converter.calculateDelta(x, y, dt) else converter.calculateDeltaFromAccel(x, y, z)
            dx = deltas.first
            dy = deltas.second
        } else {
            synchronized(touchLock) {
                dx = touchDxAccum.toInt()
                dy = touchDyAccum.toInt()
                touchDxAccum = 0f
                touchDyAccum = 0f
            }
        }

        if (dx != 0 || dy != 0 || buttonState != 0.toByte()) {
            hidDevice?.sendReport(device, 2, byteArrayOf(buttonState, dx.coerceIn(-127, 127).toByte(), dy.coerceIn(-127, 127).toByte()))
        }
    }

    private fun stopSensorAndReports() {
        reportTimer?.cancel()
        reportTimer = null
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == (if (isUsingGyro) Sensor.TYPE_GYROSCOPE else Sensor.TYPE_ACCELEROMETER)) {
            synchronized(sensorLock) {
                sensorX = event.values[0]; sensorY = event.values[1]; sensorZ = event.values[2]
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun calibrate() {
        synchronized(sensorLock) {
            if (isUsingGyro) converter.calibrate(sensorX, sensorY) else converter.calibrateAccel(sensorX, sensorY, sensorZ)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(Constants.NOTIFICATION_CHANNEL_ID, "Air Mouse", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
        .setContentTitle("AeroCursor").setContentText(text).setSmallIcon(android.R.drawable.ic_menu_camera)
        .setOngoing(true).build()

    override fun onDestroy() {
        stopSensorAndReports()
        hidDevice?.unregisterApp()
        super.onDestroy()
    }
}
