package com.example.soundy

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.example.soundy.databinding.ActivityMainBinding
import java.util.UUID

private const val PERMISSION_REQUEST_CODE = 1
private const val GATT_MAX_MTU_SIZE = 517

fun BluetoothGattCharacteristic.containsProperty(property: Int): Boolean =
    properties and property != 0

fun BluetoothGattCharacteristic.isReadable(): Boolean =
    containsProperty(BluetoothGattCharacteristic.PROPERTY_READ)

fun BluetoothGattCharacteristic.isWritable(): Boolean =
    containsProperty(BluetoothGattCharacteristic.PROPERTY_WRITE)

fun BluetoothGattCharacteristic.isWritableWithoutResponse(): Boolean =
    containsProperty(BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val bleScanner by lazy {
        bluetoothAdapter.bluetoothLeScanner
    }

    private var bluetoothGatt: BluetoothGatt? = null

    private val scanResults = mutableListOf<ScanResult>()
    private val scanResultAdapter: ScanResultAdapter by lazy {
        ScanResultAdapter(scanResults) { result ->
            if (isScanning) {
                stopBleScan()
            }
            with(result.device) {
                Log.w("ScanResultAdapter", "Connecting to $address")
                connectGatt(this@MainActivity, false, gattCallback)
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val deviceAddress = gatt.device.address
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.w("BluetoothGattCallback", "Successfully connected to $deviceAddress")
                    bluetoothGatt = gatt
                    Handler(Looper.getMainLooper()).post {
                        bluetoothGatt?.discoverServices()
                    }
                    bluetoothGatt?.requestMtu(GATT_MAX_MTU_SIZE)
//
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.w("BluetoothGattCallback", "Successfully disconnected from $deviceAddress")
                    gatt.close()
                }
            } else {
                Log.w("BluetoothGattCallback", "Error $status encountered for $deviceAddress! Disconnecting...")
                gatt.close()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            with(gatt) {
                Log.w("BluetoothGattCallback", "Discovered ${this?.services?.size} services for ${this?.device?.address}")
                this?.printGattTable() // See implementation just above this section
                // Consider connection setup as complete here
                writeSoundy()

            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            Log.w("BluetoothGattCallback", "ATT MTU changed to $mtu, success:${status == BluetoothGatt.GATT_SUCCESS}")
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            val uuid = characteristic.uuid
            when (status) {
                BluetoothGatt.GATT_SUCCESS -> {
                    Log.i("BluetoothGattCallback", "Read characteristic $uuid:\n${value.toHexString()}")
                }
                BluetoothGatt.GATT_READ_NOT_PERMITTED -> {
                    Log.e("BluetoothGattCallback", "Read not permitted for $uuid!")
                }
                else -> {
                    Log.e("BluetoothGattCallback", "Characteristic read failed for $uuid, error: $status")
                }
            }
        }
        // ... somewhere outside BluetoothGattCallback
        fun ByteArray.toHexString(): String =
            joinToString(separator = " ", prefix = "0x") { String.format("%02X", it) }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            with(characteristic) {
                when (status) {
                    BluetoothGatt.GATT_SUCCESS -> {
                        Log.i("BluetoothGattCallback", "Wrote to characteristic $uuid")
                    }
                    BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH -> {
                        Log.e("BluetoothGattCallback", "Write exceeded connection ATT MTU!")
                    }
                    BluetoothGatt.GATT_WRITE_NOT_PERMITTED -> {
                        Log.e("BluetoothGattCallback", "Write not permitted for $uuid!")
                    }
                    else -> {
                        Log.e("BluetoothGattCallback", "Characteristic write failed for $uuid, error: $status")
                    }
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            Log.i("BluetoothGattCallback", "Characteristic changed for ${characteristic.uuid} | value: ${value.toHexString()}")
        }

    }

    private fun BluetoothGatt.printGattTable() {
        if(services.isEmpty()){
            Log.i("printGattTable", "No service and characteristic available, call discoverServices() first?")
            return
        }
        services.forEach{ service ->
            val characteristicsTable = service.characteristics.joinToString(
                separator = "\n|--",
                prefix = "|--"
            ){ it.uuid.toString() }
            Log.i("printGattTable", " \nService ${service.uuid}\nCharacteristics:\n$characteristicsTable" )
        }
    }

    fun writeCharacteristic(characteristic: BluetoothGattCharacteristic, payload: ByteArray) {
        val writeType = when {
            characteristic.isWritable() -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.isWritableWithoutResponse() -> {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
            else -> error("Characteristic ${characteristic.uuid} cannot be written to")
        }
        Log.i("writeCharacteristic", "writeType {$writeType}")
        bluetoothGatt?.let { gatt ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(characteristic, payload, writeType)
                Log.i("writeCharacteristic", "wrote it")
            }
        } ?: error("Not connected to a BLE device!")
    }

    private fun writeSoundy() {

        val soundyServiceUuid = UUID.fromString("0000beef-0000-1000-8000-00805f9b34fb")
        val soundyWriteCharUuid = UUID.fromString("0000face-0000-1000-8000-00805f9b34fb")
        val soundyService = bluetoothGatt?.getService(soundyServiceUuid)
        Log.i("writeSoundy", "SoundyService {$soundyService}")
        val soundyCharacteristics = soundyService?.characteristics
        Log.i("writeSoundy", "SoundyCharacteristics {$soundyCharacteristics}")
        val soundyChar = bluetoothGatt?.getService(soundyServiceUuid)?.getCharacteristic(soundyWriteCharUuid)
        Log.i("writeSoundy", "SoundyIsWritable {$soundyChar}")
        if (soundyChar?.isWritable() == true) {
            writeCharacteristic(soundyChar, "Hellooooo".toByteArray())
        }
        if (soundyChar?.isReadable() == true) {
            bluetoothGatt?.readCharacteristic(soundyChar)
        }

    }


    private val bluetoothAdapter: BluetoothAdapter by lazy {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }
    private val bluetoothEnablingResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // Bluetooth is enabled, good to go
        } else {
            // User dismissed or denied Bluetooth prompt
            promptEnableBluetooth()
        }
    }

    private val scanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val indexQuery = scanResults.indexOfFirst { it.device.address == result.device.address }
            if (indexQuery != -1) { // A Scan result already exist with the same address
                scanResults[indexQuery] = result
                scanResultAdapter.notifyItemChanged(indexQuery)
            }else{
                with(result.device) {
                    Log.i("ScanCallback", "Found BLE device! Name: ${name ?: "Unnamed"}, address: $address")
                }
                scanResults.add(result)
                scanResultAdapter.notifyItemInserted(scanResults.size - 1)
            }
        }
        override fun onScanFailed(errorCode: Int) {
            Log.e("ScanCallback", "onScanFailed: code $errorCode")
        }
    }



    override fun onResume() {
        super.onResume()
        if (!bluetoothAdapter.isEnabled) {
            promptEnableBluetooth()
        }
    }
    /**
     * Prompts the user to enable Bluetooth via a system dialog.
     *
     * For Android 12+, [Manifest.permission.BLUETOOTH_CONNECT] is required to use
     * the [BluetoothAdapter.ACTION_REQUEST_ENABLE] intent.
     */
    private fun promptEnableBluetooth() {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            // Insufficient permission to prompt for Bluetooth enabling
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE).apply {
                bluetoothEnablingResult.launch(this)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.scanButton.setOnClickListener {
            if (isScanning) {
                stopBleScan()
            } else {
                startBleScan()
            }
        }
        setupRecyclerView()

    }
    /**
     * Determine whether the current [Context] has been granted the relevant [Manifest.permission].
     */
    private fun Context.hasPermission(permissionType: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permissionType) ==
                PackageManager.PERMISSION_GRANTED
    }

    /**
     * Determine whether the current [Context] has been granted the relevant permissions to perform
     * Bluetooth operations
     */
    private fun Context.hasRequiredBluetoothPermissions(): Boolean {
        return hasPermission(Manifest.permission.BLUETOOTH_SCAN) && hasPermission(Manifest.permission.BLUETOOTH_CONNECT)

    }

    private var isScanning = false
        set(value) {
            field = value
            runOnUiThread { binding.scanButton.text = if (value) "Stop Scan" else "Start Scan" }
        }


    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        if (!hasRequiredBluetoothPermissions()) {
            requestRelevantRuntimePermissions()
        } else {
            scanResults.clear()
            scanResultAdapter.notifyDataSetChanged()
            bleScanner.startScan(null, scanSettings, scanCallback)
            isScanning = true
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        bleScanner.stopScan(scanCallback)
        isScanning = false
    }

    private fun Activity.requestRelevantRuntimePermissions() {
        if (hasRequiredBluetoothPermissions()) { return }
        requestBluetoothPermissions()
    }

    private fun requestLocationPermission() = runOnUiThread {
        AlertDialog.Builder(this)
            .setTitle("Location permission required")
            .setMessage(
                "Starting from Android M (6.0), the system requires apps to be granted " +
                        "location access in order to scan for BLE devices."
            )
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    PERMISSION_REQUEST_CODE
                )
            }
            .show()
    }


    private fun requestBluetoothPermissions() = runOnUiThread {
        AlertDialog.Builder(this)
            .setTitle("Bluetooth permission required")
            .setMessage(
                "Starting from Android 12, the system requires apps to be granted " +
                        "Bluetooth access in order to scan for and connect to BLE devices."
            )
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ),
                    PERMISSION_REQUEST_CODE
                )
            }
            .show()
    }
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST_CODE) return

        val containsPermanentDenial = permissions.zip(grantResults.toTypedArray()).any {
            it.second == PackageManager.PERMISSION_DENIED &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(this, it.first)
        }
        val containsDenial = grantResults.any { it == PackageManager.PERMISSION_DENIED }
        val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        when {
            containsPermanentDenial -> {
                // TODO: Handle permanent denial (e.g., show AlertDialog with justification)
                // Note: The user will need to navigate to App Settings and manually grant
                // permissions that were permanently denied
            }
            containsDenial -> {
                requestRelevantRuntimePermissions()
            }
            allGranted && hasRequiredBluetoothPermissions() -> {
                startBleScan()
            }
            else -> {
                // Unexpected scenario encountered when handling permissions
                recreate()
            }
        }
    }

    private fun setupRecyclerView() {
        binding.scanResultsRecyclerView.apply {
            adapter = scanResultAdapter
            layoutManager = LinearLayoutManager(
                this@MainActivity,
                RecyclerView.VERTICAL,
                false
            )
            isNestedScrollingEnabled = false
        }
        val animator = binding.scanResultsRecyclerView.itemAnimator
        if (animator is SimpleItemAnimator) {
            animator.supportsChangeAnimations = false
        }
    }
}

