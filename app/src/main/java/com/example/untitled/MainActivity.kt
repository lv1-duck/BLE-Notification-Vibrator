package com.example.untitled

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.bluetooth.le.ScanFilter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.util.UUID

class MainActivity : AppCompatActivity() {

    // UI Components
    private lateinit var sendButton: Button
    private lateinit var statusText: TextView

    // Bluetooth Components
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var targetCharacteristic: BluetoothGattCharacteristic? = null

    // State Variables
    private var notificationWarningShown = false
    private var isScanning = false
    private var isConnected = false
    private var hasPromptedForNotifications = false
    private var isConnecting = false

    // Constants
    private val TAG = "UntitledBLE"
    private val ESP_DEVICE_NAME = "ESP32-Motor"
    private val SERVICE_UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
    private val CHARACTERISTIC_UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
    private val VIBRATION_COMMAND = "vibrate"
    private val VIBRATION_ACTION = "com.example.untitled.BLUETOOTH_VIBRATE_ACTION"

    // Scan timeout
    private val SCAN_PERIOD: Long = 15000
    private val scanHandler = Handler(Looper.getMainLooper())

    // Permission launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            updateStatus("Permissions granted")
            initializeBluetoothAfterPermissions()
        } else {
            val deniedPermissions = permissions.filter { !it.value }.keys
            updateStatus("Permissions denied: ${deniedPermissions.joinToString()}")
            Log.e(TAG, "CRITICAL: Missing permissions: ${deniedPermissions.joinToString()}")
        }
    }

    // Bluetooth enable launcher
    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            updateStatus("Bluetooth enabled - Ready!")
            refreshSendButtonState()
        } else {
            updateStatus("Bluetooth enable cancelled")
        }
    }

    // Enhanced BLE Scan Callback
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val deviceName = getDeviceNameSafely(device)
            val rssi = result.rssi
            val address = device.address

            Log.d(TAG, "=== FOUND BLE DEVICE ===")
            Log.d(TAG, "Name: ${deviceName ?: "NULL/UNKNOWN"}")
            Log.d(TAG, "Address: $address")
            Log.d(TAG, "RSSI: $rssi dBm")

            result.scanRecord?.serviceUuids?.let { uuids ->
                Log.d(TAG, "Advertised Services: ${uuids.joinToString()}")
            }

            val isTargetDevice = when {
                deviceName != null && deviceName.contains(ESP_DEVICE_NAME, ignoreCase = true) -> {
                    Log.d(TAG, "✅ MATCHED by name: $deviceName")
                    true
                }
                deviceName != null && deviceName.contains("ESP", ignoreCase = true) -> {
                    Log.d(TAG, "✅ POTENTIAL ESP device: $deviceName")
                    true
                }
                result.scanRecord?.serviceUuids?.any {
                    it.uuid.toString().equals(SERVICE_UUID.toString(), ignoreCase = true)
                } == true -> {
                    Log.d(TAG, "✅ MATCHED by service UUID")
                    true
                }
                else -> false
            }

            if (isTargetDevice) {
                Log.d(TAG, "🎯 TARGET DEVICE FOUND! Attempting connection...")
                stopScanning()
                connectToDevice(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            val errorMsg = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "Scan already started"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "App registration failed"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                SCAN_FAILED_INTERNAL_ERROR -> "Internal error"
                else -> "Unknown error: $errorCode"
            }
            Log.e(TAG, "❌ BLE scan failed: $errorMsg")
            updateStatus("Scan failed: $errorMsg")
            isScanning = false
            isConnecting = false
        }
    }

    // GATT callback
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "✅ Connected to GATT server (status: $status)")
                    runOnUiThread {
                        updateStatus("Connected, discovering services...")
                        isConnected = true
                        isConnecting = false
                        refreshSendButtonState()
                    }

                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!hasRequiredBluetoothPermissions()) {
                            Log.e(TAG, "Missing permissions for service discovery")
                            runOnUiThread { updateStatus("Missing permissions for service discovery") }
                            return@postDelayed
                        }

                        try {
                            Log.d(TAG, "Starting service discovery...")
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                gatt.requestMtu(512)
                            } else {
                                val success = gatt.discoverServices()
                                if (!success) {
                                    Log.e(TAG, "Failed to start service discovery")
                                    runOnUiThread { updateStatus("Failed to start service discovery") }
                                }
                            }
                        } catch (e: SecurityException) {
                            Log.e(TAG, "SecurityException during service discovery", e)
                            runOnUiThread { updateStatus("Permission error during service discovery") }
                        }
                    }, 1500)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "❌ Disconnected from GATT server (status: $status)")
                    runOnUiThread {
                        when (status) {
                            133 -> updateStatus("❌ Connection error 133 - Try restarting Bluetooth")
                            8 -> updateStatus("❌ Connection timeout - Device may be out of range")
                            else -> updateStatus("❌ Disconnected (status: $status)")
                        }
                        isConnected = false
                        isConnecting = false
                        refreshSendButtonState()
                    }
                    cleanupGattConnection()

                    if (status == 133 || status == 8) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            Log.d(TAG, "Auto-retry connection after error $status")
                            proceedWithDeviceConnection()
                        }, 3000)
                    }
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    Log.d(TAG, "🔄 Connecting to GATT server...")
                    runOnUiThread {
                        updateStatus("Connecting...")
                        isConnecting = true
                        refreshSendButtonState()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTING -> {
                    Log.d(TAG, "🔄 Disconnecting from GATT server...")
                    runOnUiThread { updateStatus("Disconnecting...") }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "MTU changed to $mtu, status: $status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                try {
                    val success = gatt.discoverServices()
                    if (!success) {
                        Log.e(TAG, "Failed to start service discovery after MTU change")
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "SecurityException during service discovery after MTU", e)
                }
            } else {
                try {
                    val success = gatt.discoverServices()
                    if (!success) {
                        Log.e(TAG, "Failed to start service discovery after MTU failure")
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "SecurityException during service discovery", e)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "✅ Services discovered successfully")

                gatt.services.forEach { service ->
                    Log.d(TAG, "Found service: ${service.uuid}")
                    service.characteristics.forEach { char ->
                        Log.d(TAG, "  - Characteristic: ${char.uuid} (Properties: ${char.properties})")
                    }
                }

                val service = gatt.getService(SERVICE_UUID)
                if (service != null) {
                    Log.d(TAG, "✅ Target service found: $SERVICE_UUID")
                    targetCharacteristic = service.getCharacteristic(CHARACTERISTIC_UUID)
                    if (targetCharacteristic != null) {
                        val properties = targetCharacteristic!!.properties
                        Log.d(TAG, "✅ Target characteristic found: $CHARACTERISTIC_UUID")
                        Log.d(TAG, "Characteristic properties: $properties")
                        Log.d(
                            TAG,
                            "Can write: ${properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0}"
                        )
                        Log.d(
                            TAG,
                            "Can write no response: ${
                                properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
                            }"
                        )

                        runOnUiThread {
                            updateStatus("✅ Ready to send commands!")
                            refreshSendButtonState()
                        }
                    } else {
                        runOnUiThread { updateStatus("❌ Characteristic not found") }
                        Log.e(TAG, "❌ Characteristic not found: $CHARACTERISTIC_UUID")
                    }
                } else {
                    runOnUiThread { updateStatus("❌ Service not found") }
                    Log.e(TAG, "❌ Target service not found: $SERVICE_UUID")
                }
            } else {
                Log.e(TAG, "❌ Service discovery failed with status: $status")
                runOnUiThread { updateStatus("Service discovery failed: $status") }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "✅ Command sent successfully to ${characteristic.uuid}")
                runOnUiThread { updateStatus("✅ Command sent successfully!") }
            } else {
                Log.e(TAG, "❌ Failed to send command to ${characteristic.uuid}, status: $status")
                runOnUiThread { updateStatus("❌ Failed to send command (status: $status)") }
            }
        }
    }

    // Notification receiver
    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == VIBRATION_ACTION) {
                Log.d(TAG, "Received vibration broadcast from notification service")
                Handler(Looper.getMainLooper()).post {
                    sendVibrateCommand()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        setupClickListeners()
        registerNotificationReceiver()

        // Start the initialization process immediately
        startInitializationProcess()
    }

    override fun onResume() {
        super.onResume()
        if (hasPromptedForNotifications && isNotificationServiceEnabled()) {
            proceedWithDeviceConnection() // Auto-retry connection
        }
        refreshSendButtonState()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }

    // =========================== INITIALIZATION ===========================

    private fun initializeViews() {
        sendButton = findViewById(R.id.sendVibrationButton)
        statusText = findViewById(R.id.statusText)

        sendButton.isEnabled = false
        updateStatus("App started - Checking setup...")
    }

    private fun startInitializationProcess() {
        Log.d(TAG, "Starting initialization process...")

        // Check if device supports Bluetooth first
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            updateStatus("❌ Bluetooth not supported on this device")
            sendButton.isEnabled = false
            Log.e(TAG, "Bluetooth not supported")
            return
        }

        Log.d(TAG, "✅ Bluetooth supported")
        updateStatus("Checking permissions...")

        // Check permissions immediately
        if (hasRequiredBluetoothPermissions()) {
            Log.d(TAG, "✅ All permissions already granted")
            initializeBluetoothAfterPermissions()
        } else {
            Log.d(TAG, "⚠️ Missing permissions, requesting...")
            updateStatus("🔑 Requesting permissions...")
            requestBluetoothPermissions()
        }
    }

    private fun initializeBluetoothAfterPermissions() {
        Log.d(TAG, "Initializing Bluetooth components with permissions...")

        try {
            bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
            if (bluetoothLeScanner == null) {
                updateStatus("❌ BLE not supported")
                sendButton.isEnabled = false
                Log.e(TAG, "BLE scanner not available")
                return
            }

            Log.d(TAG, "✅ BLE scanner initialized successfully")

            // Check if Bluetooth is enabled
            if (isBluetoothEnabled()) {
                updateStatus("✅ Ready to connect!")
                refreshSendButtonState()
                Log.d(TAG, "✅ Bluetooth is enabled and ready")

                // PROMPT FOR NOTIFICATION PERMISSION IMMEDIATELY
                if (!isNotificationServiceEnabled() && !hasPromptedForNotifications) {
                    promptForNotificationPermission()
                }
            } else {
                updateStatus("📱 Please enable Bluetooth")
                requestBluetoothEnable()
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException initializing BLE scanner", e)
            updateStatus("❌ Permission error accessing BLE")
            sendButton.isEnabled = false
        }
    }

    private fun setupClickListeners() {
        sendButton.setOnClickListener {
            sendVibrateCommand()
        }
    }

    private fun registerNotificationReceiver() {
        LocalBroadcastManager.getInstance(this).registerReceiver(
            notificationReceiver,
            IntentFilter(VIBRATION_ACTION)
        )
    }

    // =========================== MAIN BLE LOGIC ===========================

    private fun sendVibrateCommand() {
        Log.d(TAG, "🔄 sendVibrateCommand requested")

        // 1) If BLE adapter is missing → bail
        if (bluetoothAdapter == null) {
            updateStatus("❌ Bluetooth not supported")
            return
        }

        // 2) If BLE permissions are missing → request them, then bail
        if (!hasRequiredBluetoothPermissions()) {
            updateStatus("🔑 Requesting BLE permissions…")
            requestBluetoothPermissions()
            return
        }

        // 3) If Bluetooth is disabled → ask user to enable, then bail
        if (!isBluetoothEnabled()) {
            updateStatus("📱 Bluetooth disabled, requesting enable…")
            requestBluetoothEnable()
            return
        }

        // 4) If connected & characteristic available → SEND COMMAND
        if (isConnected && targetCharacteristic != null) {
            writeCharacteristic(VIBRATION_COMMAND)
            return
        }

        // 5) Otherwise → START CONNECTION PROCESS
        proceedWithDeviceConnection()
    }

    private fun proceedWithDeviceConnection() {
        if (isConnected || isScanning || isConnecting) {
            updateStatus("Already scanning or connecting...")
            return
        }

        startDeviceScanning()
    }

    // =========================== PERMISSION HANDLING ===========================

    private fun requestBluetoothPermissions() {
        val permissionsToRequest = getRequiredBluetoothPermissions()
        val missingPermissions = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            Log.d(TAG, "Requesting permissions: ${missingPermissions.joinToString()}")
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            Log.d(TAG, "✅ All permissions already granted")
            initializeBluetoothAfterPermissions()
        }
    }

    private fun getRequiredBluetoothPermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }

    private fun hasRequiredBluetoothPermissions(): Boolean {
        val hasPermissions = getRequiredBluetoothPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        Log.d(TAG, "Permission check result: $hasPermissions")
        return hasPermissions
    }

    private fun isBluetoothEnabled(): Boolean {
        if (!hasRequiredBluetoothPermissions()) {
            Log.w(TAG, "Cannot check Bluetooth enabled state - missing permissions")
            return false
        }

        return try {
            bluetoothAdapter?.isEnabled == true
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException checking Bluetooth enabled state", e)
            false
        }
    }

    private fun requestBluetoothEnable() {
        if (!hasRequiredBluetoothPermissions()) {
            updateStatus("❌ Missing permissions to enable Bluetooth")
            return
        }

        try {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent)
        } catch (securityException: SecurityException) {
            Log.e(TAG, "Security exception while requesting Bluetooth enable", securityException)
            updateStatus("❌ Permission error: Cannot request Bluetooth enable")
        }
    }

    // =========================== BLE SCANNING ===========================

    private fun startDeviceScanning() {
        if (!hasRequiredBluetoothPermissions()) {
            updateStatus("❌ Missing permissions for scanning")
            return
        }

        updateStatus("🔍 Scanning for ESP32 device...")
        isScanning = true
        isConnecting = false

        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
                .setReportDelay(0)
                .build()

            val filters = mutableListOf<ScanFilter>()

            Log.d(TAG, "Starting BLE scan...")
            bluetoothLeScanner?.startScan(filters, settings, scanCallback)

        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting BLE scan", e)
            updateStatus("❌ Permission error during scan")
            isScanning = false
            return
        }

        scanHandler.postDelayed({
            stopScanning()
            if (!isConnected && !isConnecting) {
                updateStatus("❌ ESP32 device not found - Check if device is on and nearby")
                Log.e(TAG, "Scan timeout - no target device found")
            }
        }, SCAN_PERIOD)
    }

    private fun stopScanning() {
        if (isScanning) {
            try {
                if (hasRequiredBluetoothPermissions()) {
                    bluetoothLeScanner?.stopScan(scanCallback)
                    Log.d(TAG, "BLE scan stopped")
                } else {
                    Log.w(TAG, "Cannot stop scanning - missing permissions")
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException stopping BLE scan", e)
            }
            isScanning = false
            scanHandler.removeCallbacksAndMessages(null)
        }
    }

    // =========================== BLE CONNECTION ===========================

    private fun connectToDevice(device: BluetoothDevice) {
        if (!hasRequiredBluetoothPermissions()) {
            updateStatus("❌ Missing permissions for connection")
            return
        }

        val deviceName = getDeviceNameSafely(device)
        updateStatus("🔄 Connecting to ${deviceName ?: "Unknown Device"}...")
        Log.d(TAG, "Attempting connection to: ${deviceName ?: "NULL"} (${device.address})")
        isConnecting = true
        refreshSendButtonState()

        try {
            clearGattCache()

            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    bluetoothGatt = device.connectGatt(this, false, gattCallback)
                    if (bluetoothGatt == null) {
                        Log.e(TAG, "❌ Failed to create GATT connection")
                        updateStatus("❌ Connection failed")
                        isConnecting = false
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "SecurityException connecting to GATT", e)
                    updateStatus("❌ Permission error during connection")
                    isConnecting = false
                }
            }, 500)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException during connection preparation", e)
            updateStatus("❌ Permission error during connection")
            isConnecting = false
        }
    }

    private fun clearGattCache() {
        try {
            bluetoothGatt?.let { gatt ->
                val refreshMethod = BluetoothGatt::class.java.getMethod("refresh")
                val success = refreshMethod.invoke(gatt) as Boolean
                Log.d(TAG, if (success) "✅ GATT cache cleared" else "❌ Failed to clear GATT cache")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear GATT cache via reflection", e)
            bluetoothGatt?.let { gatt ->
                try {
                    if (hasRequiredBluetoothPermissions()) {
                        gatt.disconnect()
                    }
                } catch (se: SecurityException) {
                    Log.e(TAG, "SecurityException during disconnect", se)
                }
            }
        }
    }

    private fun writeCharacteristic(command: String) {
        if (!hasRequiredBluetoothPermissions()) {
            updateStatus("❌ Missing permissions to write characteristic")
            return
        }

        targetCharacteristic?.let { characteristic ->
            try {
                Log.d(TAG, "Writing command: '$command' to characteristic ${characteristic.uuid}")

                val properties = characteristic.properties
                val writeType = when {
                    properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 -> {
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    }
                    properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 -> {
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    }
                    else -> {
                        Log.e(TAG, "❌ Characteristic doesn't support writing")
                        updateStatus("❌ Characteristic not writable")
                        return
                    }
                }

                characteristic.writeType = writeType
                characteristic.value = command.toByteArray(Charsets.UTF_8)

                val success = bluetoothGatt?.writeCharacteristic(characteristic) ?: false
                if (success) {
                    Log.d(TAG, "✅ Write request queued successfully")
                    updateStatus("📤 Sending command...")
                } else {
                    Log.e(TAG, "❌ Failed to queue write request")
                    updateStatus("❌ Failed to send command")
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException writing characteristic", e)
                updateStatus("❌ Permission error sending command")
            }
        } ?: run {
            updateStatus("❌ Not connected to device")
            Log.e(TAG, "No characteristic available for writing")
        }
    }

    private fun getDeviceNameSafely(device: BluetoothDevice): String? {
        if (!hasRequiredBluetoothPermissions()) {
            Log.w(TAG, "Cannot get device name - missing permissions")
            return null
        }

        return try {
            device.name
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException getting device name", e)
            null
        }
    }

    private fun cleanupGattConnection() {
        bluetoothGatt?.let { gatt ->
            try {
                if (isConnected && hasRequiredBluetoothPermissions()) {
                    gatt.disconnect()
                }
                gatt.close()
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException closing GATT connection", e)
            } catch (e: Exception) {
                Log.e(TAG, "Exception closing GATT connection", e)
            }
        }
        bluetoothGatt = null
        targetCharacteristic = null
        isConnected = false
        isConnecting = false
    }

    // =========================== NOTIFICATION PERMISSION ===========================

    private fun requestNotificationPermission() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        startActivity(intent)
        Toast.makeText(
            this,
            "Please enable ${getString(R.string.app_name)} in notification listeners",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val enabledListeners = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        )
        return enabledListeners?.contains(packageName) == true
    }

    private fun refreshSendButtonState() {
        runOnUiThread {
            sendButton.isEnabled =
                hasRequiredBluetoothPermissions() &&
                        isBluetoothEnabled() &&
                        !isConnecting
        }
    }

    // =========================== UTILITY METHODS ===========================

    private fun updateStatus(message: String) {
        Log.d(TAG, "Status: $message")
        runOnUiThread {
            statusText.text = message
        }
    }

    private fun cleanup() {
        Log.d(TAG, "Cleaning up resources...")
        stopScanning()

        bluetoothGatt?.let { gatt ->
            if (isConnected) {
                try {
                    if (hasRequiredBluetoothPermissions()) {
                        gatt.disconnect()
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "SecurityException disconnecting GATT", e)
                }
            }
            cleanupGattConnection()
        }

        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(notificationReceiver)
            Log.d(TAG, "Notification receiver unregistered")
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Notification receiver was not registered", e)
        }
    }

    private fun promptForNotificationPermission() {
        hasPromptedForNotifications = true
        AlertDialog.Builder(this)
            .setTitle("Notification Access Required")
            .setMessage("To function properly, this app needs notification access. Please enable it now in settings.")
            .setPositiveButton("Open Settings") { _, _ ->
                requestNotificationPermission()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                updateStatus("⚠️ Enable notification access in settings")
            }
            .setCancelable(false)
            .show()
    }
}