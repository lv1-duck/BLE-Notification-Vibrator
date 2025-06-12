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
    private var isScanning = false
    private var isConnected = false
    private var hasPromptedForNotifications = false
    private var isConnecting = false

    // Constants
    private val tag = "UntitledBLE"
    private val espDeviceName = "ESP32-Motor"
    private val serviceUUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
    private val characteristicUUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
    private val vibrationCommand = "vibrate"
    private val vibrationAction = "com.example.untitled.BLUETOOTH_VIBRATE_ACTION"

    // Scan timeout
    private val scanPeriod: Long = 15000
    private val scanHandler = Handler(Looper.getMainLooper())

    // Permission launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            updateStatus("Permissions granted")
            initializeBluetoothAfterPermissions()
        }
        else if (permissions.filter { !it.value }.keys.contains("android.permission.ACCESS_FINE_LOCATION") &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            initializeBluetoothAfterPermissions()
        }
        else {
            val deniedPermissions = permissions.filter { !it.value }.keys
            updateStatus("Permissions denied: ${deniedPermissions.joinToString()}")
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

            result.scanRecord?.serviceUuids?.let { uuids ->
                Log.d(tag, "Advertised Services: ${uuids.joinToString()}")
            }

            val isTargetDevice = when {
                deviceName != null && deviceName.contains(espDeviceName, ignoreCase = true) -> {
                    true
                }
                deviceName != null && deviceName.contains("ESP", ignoreCase = true) -> {
                    true
                }
                result.scanRecord?.serviceUuids?.any {
                    it.uuid.toString().equals(serviceUUID.toString(), ignoreCase = true)
                } == true -> {
                    true
                }
                else -> false
            }

            if (isTargetDevice) {
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
                    runOnUiThread {
                        updateStatus("Connected, discovering services...")
                        isConnected = true
                        isConnecting = false
                        refreshSendButtonState()
                    }

                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!hasRequiredBluetoothPermissions()) {
                            runOnUiThread { updateStatus("Missing permissions for service discovery") }
                            return@postDelayed
                        }

                        try {
                            gatt.requestMtu(512)
                        } catch (e: SecurityException) {
                            runOnUiThread { updateStatus("Permission error during service discovery") }
                            print(message = e)
                        }
                    }, 1500)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
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
                            proceedWithDeviceConnection()
                        }, 3000)
                    }
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    runOnUiThread {
                        updateStatus("Connecting...")
                        isConnecting = true
                        refreshSendButtonState()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTING -> {
                    runOnUiThread { updateStatus("Disconnecting...") }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                try
                {
                    val success = gatt.discoverServices()
                    if (!success)
                    {
                        Log.e(tag, "Failed to start service discovery after MTU change")
                    }
                }
                catch (e: SecurityException)
                {
                    Log.e(tag, "SecurityException during service discovery after MTU", e)
                }
            }

        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {

                gatt.services.forEach { service ->
                    service.characteristics.forEach { char ->
                    }
                }

                val service = gatt.getService(serviceUUID)
                if (service != null) {
                    targetCharacteristic = service.getCharacteristic(characteristicUUID)
                    if (targetCharacteristic != null) {
                        val properties = targetCharacteristic!!.properties
                        Log.d(tag, "✅ Target characteristic found: $characteristicUUID")
                        Log.d(tag, "Characteristic properties: $properties")

                        runOnUiThread {
                            updateStatus("✅ Ready to send commands!")
                            refreshSendButtonState()
                        }
                    } else {
                        runOnUiThread { updateStatus("❌ Characteristic not found") }
                    }
                } else {
                    runOnUiThread { updateStatus("❌ Service not found") }
                }
            } else {
                runOnUiThread { updateStatus("Service discovery failed: $status") }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(tag, "✅ Command sent successfully to ${characteristic.uuid}")
                runOnUiThread { updateStatus("✅ Command sent successfully!") }
            } else {
                runOnUiThread { updateStatus("❌ Failed to send command (status: $status)") }
            }
        }
    }

    // Notification receiver
    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == vibrationAction) {
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

        // Check if device supports Bluetooth first
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            updateStatus("❌ Bluetooth not supported on this device")
            sendButton.isEnabled = false
            return
        }

        updateStatus("Checking permissions...")

        // Check permissions immediately
        if (hasRequiredBluetoothPermissions()) {
            initializeBluetoothAfterPermissions()
        } else {
            updateStatus("🔑 Requesting permissions...")
            requestBluetoothPermissions()
        }
    }

    private fun initializeBluetoothAfterPermissions() {

        try {
            bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
            if (bluetoothLeScanner == null) {
                updateStatus("❌ BLE not supported")
                sendButton.isEnabled = false
                return
            }


            // Check if Bluetooth is enabled
            if (isBluetoothEnabled()) {
                updateStatus("✅ Ready to connect!")
                refreshSendButtonState()

                // PROMPT FOR NOTIFICATION PERMISSION IMMEDIATELY
                if (!isNotificationServiceEnabled() && !hasPromptedForNotifications) {
                    promptForNotificationPermission()
                }
            } else {
                updateStatus("📱 Please enable Bluetooth")
                requestBluetoothEnable()
            }

        } catch (e: SecurityException) {
            updateStatus("❌ Permission error accessing BLE")
            sendButton.isEnabled = false
            print(message = e)
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
            IntentFilter(vibrationAction)
        )
    }

    // =========================== MAIN BLE LOGIC ===========================

    private fun sendVibrateCommand() {

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
            writeCharacteristic()
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
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
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
        return hasPermissions
    }

    private fun isBluetoothEnabled(): Boolean {
        if (!hasRequiredBluetoothPermissions()) {
            return false
        }

        return try {
            bluetoothAdapter?.isEnabled == true
        } catch (e: SecurityException) {
            Log.e(tag, "SecurityException checking Bluetooth enabled state", e)
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
        } catch (e: SecurityException) {
            updateStatus("❌ Permission error: Cannot request Bluetooth enable")
            print(message = e)
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

            bluetoothLeScanner?.startScan(filters, settings, scanCallback)

        } catch (e: SecurityException) {
            updateStatus("❌ Permission error during scan")
            isScanning = false
            print(message = e)
            return
        }

        scanHandler.postDelayed({
            stopScanning()
            if (!isConnected && !isConnecting) {
                updateStatus("❌ ESP32 device not found - Check if device is on and nearby")
            }
        }, scanPeriod)
    }

    private fun stopScanning() {
        if (isScanning) {
            try {
                if (hasRequiredBluetoothPermissions()) {
                    bluetoothLeScanner?.stopScan(scanCallback)
                } else {
                    Log.w(tag, "Cannot stop scanning - missing permissions")
                }
            } catch (e: SecurityException) {
                Log.e(tag, "SecurityException stopping BLE scan", e)
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
        Log.d(tag, "Attempting connection to: ${deviceName ?: "NULL"} (${device.address})")
        isConnecting = true
        refreshSendButtonState()

        try {
            clearGattCache()

            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    bluetoothGatt = device.connectGatt(this, false, gattCallback)
                    if (bluetoothGatt == null) {
                        Log.e(tag, "❌ Failed to create GATT connection")
                        updateStatus("❌ Connection failed")
                        isConnecting = false
                    }
                } catch (e: SecurityException) {
                    Log.e(tag, "SecurityException connecting to GATT", e)
                    updateStatus("❌ Permission error during connection")
                    isConnecting = false
                }
            }, 500)
        } catch (e: SecurityException) {
            Log.e(tag, "SecurityException during connection preparation", e)
            updateStatus("❌ Permission error during connection")
            isConnecting = false
        }
    }

    private fun clearGattCache() {
        try {
            bluetoothGatt?.let { gatt ->
                val refreshMethod = BluetoothGatt::class.java.getMethod("refresh")
                refreshMethod.invoke(gatt) as Boolean
            }
        } catch (e: Exception) {
            bluetoothGatt?.let { gatt ->
                try {
                    if (hasRequiredBluetoothPermissions()) {
                        gatt.disconnect()
                    }
                } catch (se: SecurityException) {
                    print(message = e)
                    print(message = se)
                }
            }
        }
    }

    private fun writeCharacteristic() {
        if (!hasRequiredBluetoothPermissions()) {
            updateStatus("❌ Missing permissions to write characteristic")
            return
        }

        targetCharacteristic?.let { characteristic ->
            try {

                val properties = characteristic.properties
                val writeType = when {
                    properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 -> {
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    }
                    properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 -> {
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    }
                    else -> {
                        Log.e(tag, "❌ Characteristic doesn't support writing")
                        updateStatus("❌ Characteristic not writable")
                        return
                    }
                }

                characteristic.writeType = writeType
                characteristic.value = vibrationCommand.toByteArray(Charsets.UTF_8)

                val success = bluetoothGatt?.writeCharacteristic(characteristic) ?: false
                if (success) {
                    updateStatus("Sending command...")
                } else {
                    updateStatus("Failed to send command")
                }
            } catch (e: SecurityException) {
                updateStatus("Permission error sending command")
                print(message = e)
            }
        } ?: run {
            updateStatus("❌ Not connected to device")
        }
    }

    private fun getDeviceNameSafely(device: BluetoothDevice): String? {
        if (!hasRequiredBluetoothPermissions()) {
            return null
        }

        return try {
            device.name
        } catch (e: SecurityException) {
            print(message = e)
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
                Log.e(tag, "SecurityException closing GATT connection", e)
            } catch (e: Exception) {
                Log.e(tag, "Exception closing GATT connection", e)
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
        runOnUiThread {
            statusText.text = message
        }
    }

    @SuppressLint("MissingPermission")
    private fun cleanup() {
        stopScanning()

        bluetoothGatt?.let { gatt ->
            if (isConnected) {

                if (hasRequiredBluetoothPermissions())
                { gatt.disconnect() }
            }
            cleanupGattConnection()
        }
        LocalBroadcastManager.getInstance(this).unregisterReceiver(notificationReceiver)

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
