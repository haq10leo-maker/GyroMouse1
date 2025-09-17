package com.example.gyromouse

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.util.*

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var statusTextView: TextView
    private lateinit var leftClickButton: Button
    private lateinit var rightClickButton: Button

    private lateinit var sensorManager: SensorManager
    private var gyroscope: Sensor? = null
    private val gyroHandler = Handler(Looper.getMainLooper())

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var hidDevice: BluetoothHidDevice? = null
    private var hostDevice: BluetoothDevice? = null

    private var isSendingReports = false
    private val movementMultiplier = 15f

    companion object {
        private const val APP_NAME = "GyroMouse"
        private const val APP_DESCRIPTION = "Android Gyroscope Mouse"
        private const val PROVIDER = "Gemini"
        private const val SUBCLASS: Byte = 0b00000100 // Mouse subclass
        private val MOUSE_REPORT_DESCRIPTOR = byteArrayOf(
            0x05, 0x01, 0x09, 0x02, 0xA1.toByte(), 0x01, 0x09, 0x01, 0xA1.toByte(), 0x00, 0x05, 0x09,
            0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x95.toByte(), 0x03, 0x75, 0x01, 0x81.toByte(), 0x02,
            0x95.toByte(), 0x01, 0x75, 0x05, 0x81.toByte(), 0x01, 0x05, 0x01, 0x09, 0x30, 0x09, 0x31,
            0x15, 0x81.toByte(), 0x25, 0x7F, 0x75, 0x08, 0x95.toByte(), 0x02, 0x81.toByte(), 0x06, 0xC0.toByte(), 0xC0.toByte()
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        statusTextView = findViewById(R.id.statusTextView)
        leftClickButton = findViewById(R.id.leftClickButton)
        rightClickButton = findViewById(R.id.rightClickButton)
        
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        
        initBluetooth()
        setupClickListeners()
    }

    private fun initBluetooth() {
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not supported", Toast.LENGTH_LONG).show()
            return
        }
        
        if (checkPermissions()) {
            bluetoothAdapter?.getProfileProxy(this, profileListener, BluetoothProfile.HID_DEVICE)
        } else {
            requestPermissions()
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = proxy as BluetoothHidDevice
                hidDevice?.registerApp(
                    BluetoothHidDeviceAppSdpSettings(APP_NAME, APP_DESCRIPTION, PROVIDER, SUBCLASS, MOUSE_REPORT_DESCRIPTOR),
                    null, null, mainExecutor, appCallback
                )
            }
        }
        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) hidDevice = null
        }
    }

    private val appCallback = object : BluetoothHidDevice.Callback() {
        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            super.onConnectionStateChanged(device, state)
            runOnUiThread {
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        hostDevice = device
                        statusTextView.text = "Status: Connected to ${device?.name}"
                        startSendingReports()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        hostDevice = null
                        statusTextView.text = "Status: Disconnected"
                        stopSendingReports()
                    }
                    BluetoothProfile.STATE_CONNECTING -> statusTextView.text = "Status: Connecting..."
                }
            }
        }
    }

    private val gyroRunnable = object : Runnable {
        private var accumulatedX = 0f
        private var accumulatedY = 0f
        fun updateData(event: SensorEvent) {
            accumulatedX += event.values[1] * movementMultiplier
            accumulatedY += event.values[0] * movementMultiplier
        }
        override fun run() {
            if (!isSendingReports || hostDevice == null) return
            val dx = accumulatedX.toInt().coerceIn(-127, 127)
            val dy = accumulatedY.toInt().coerceIn(-127, 127)
            if (dx != 0 || dy != 0) sendMouseMove(dx.toByte(), dy.toByte())
            accumulatedX = 0f
            accumulatedY = 0f
            gyroHandler.postDelayed(this, 10)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_GYROSCOPE) {
            (gyroRunnable as? MainActivity.gyroRunnable)?.updateData(event)
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    
    private fun sendMouseMove(dx: Byte, dy: Byte) {
        hidDevice?.sendReport(hostDevice, 0, byteArrayOf(0, dx, dy))
    }
    
    private fun sendClick(isLeft: Boolean, isPressed: Boolean) {
        val buttonByte = when {
            isLeft && isPressed -> 0b001; !isLeft && isPressed -> 0b010; else -> 0b000
        }.toByte()
        hidDevice?.sendReport(hostDevice, 0, byteArrayOf(buttonByte, 0, 0))
    }

    private fun startSendingReports() {
        if (!isSendingReports) {
            isSendingReports = true
            sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME)
            gyroHandler.post(gyroRunnable)
        }
    }
    private fun stopSendingReports() {
        if (isSendingReports) {
            isSendingReports = false
            sensorManager.unregisterListener(this)
            gyroHandler.removeCallbacks(gyroRunnable)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupClickListeners() {
        leftClickButton.setOnTouchListener { _, event ->
            handleTouch(isLeft = true, action = event.action); true
        }
        rightClickButton.setOnTouchListener { _, event ->
            handleTouch(isLeft = false, action = event.action); true
        }
    }

    private fun handleTouch(isLeft: Boolean, action: Int) {
        when (action) {
            MotionEvent.ACTION_DOWN -> sendClick(isLeft, isPressed = true)
            MotionEvent.ACTION_UP -> sendClick(isLeft, isPressed = false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSendingReports()
        hidDevice?.unregisterApp()
        bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice)
    }

    private fun checkPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE), 1)
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN), 1)
        }
    }
}

	