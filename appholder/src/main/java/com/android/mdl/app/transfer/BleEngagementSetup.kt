package com.android.mdl.app.transfer

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.android.identity.Logger
import com.android.identity.PresentationHelper
import com.android.identity.PresentationSession
import com.android.mdl.app.util.log
import com.android.mdl.app.util.mainExecutor
import java.util.UUID

class BleEngagementSetup(
  private val context: Context,
  private val bluetoothManager: BluetoothManager,
  private val session: PresentationSession,
  private val onConnecting: () -> Unit,
  private val onConnected: () -> Unit,
  private val onDisconnected: () -> Unit,
  private val onError: (error: Throwable) -> Unit,
) : BluetoothGattCallback() {

  companion object {
    const val TAG = "BleEngagementSetup"
    val ENGAGEMENT_SERVICE: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")
    val ENGAGEMENT_CHARACTERISTIC: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    const val RSSI_MIN_THRESHOLD = -28
    const val RSSI_MAX_THRESHOLD = -20
  }

  var scanner: BluetoothLeScanner? = null
  var gatt: BluetoothGatt? = null

  private val scanCallback: ScanCallback = object : ScanCallback() {
    override fun onScanResult(callbackType: Int, result: ScanResult?) {
      result?.let {
//        val distance = 10.0.pow(it.txPower - it.rssi.toDouble() / 20)
        if (it.rssi in (RSSI_MIN_THRESHOLD + 1) until RSSI_MAX_THRESHOLD) {
          stopScan()
          try {
            Logger.d(TAG, "Connecting to device with address " + it.device.name)
            connect(it.device)
          } catch (e: SecurityException) {
            Logger.e(TAG, "" + e)
          }
        }
      }
    }

    override fun onBatchScanResults(results: MutableList<ScanResult>?) {
      Logger.w(TAG, "Ignoring unexpected onBatchScanResults")
    }

    override fun onScanFailed(errorCode: Int) {
      Logger.w(TAG, "BLE scan failed with error code $errorCode")
    }
  }

  fun stopScan() {
    scanner?.apply {
      try {
        this.stopScan(scanCallback)
      } catch (e: SecurityException) {
        Logger.e(TAG, "Ble stop scanning error $e")
        onError(e)
      }
      scanner = null
    }
  }

  fun connectAsMdocHolder() {
    val bluetoothAdapter = bluetoothManager.adapter

    val filter = ScanFilter.Builder()
      .setServiceUuid(ParcelUuid(ENGAGEMENT_SERVICE))
      .build()

    val settings = ScanSettings.Builder()
      .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
      .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
      .build()

    Logger.d(TAG, "Started scanning for UUID $ENGAGEMENT_SERVICE")

    scanner = bluetoothAdapter.bluetoothLeScanner

    val filterList = listOf<ScanFilter>(filter)

    try {
      scanner?.startScan(filterList, settings, scanCallback)
    } catch (e: SecurityException) {
      Logger.e(TAG, "Ble start scanning error $e")
      onError(e)
    }
  }

  private fun connect(device: BluetoothDevice) {
    try {
      onConnecting()
      gatt = device.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE)
    } catch (e: SecurityException) {
      Logger.e(TAG, "Ble connect error $e")
      onError(e)
    }
  }

  fun disconnect() {
    try {
      gatt?.disconnect()
    } catch (e: SecurityException) {
      Logger.e(TAG, "Ble disconnect error $e")
      onError(e)
    }

    gatt = null
  }

  override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
    Logger.d(
      TAG, "onConnectionStateChange: status=$status newState=$newState"
    )
    when (newState) {
      BluetoothProfile.STATE_CONNECTED -> {
        gatt?.let {
          try {
            it.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            it.discoverServices()
          } catch (e: SecurityException) {
            Logger.e(TAG, "Ble onConnectionStateChange error $e")
          }
        }
      }

      BluetoothProfile.STATE_DISCONNECTED -> {
        Logger.e(TAG, "Ble disconnected")
        onDisconnected()
      }
    }
  }

  override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
    Logger.d(TAG, "onServicesDiscovered: status=$status")

    if (status == BluetoothGatt.GATT_SUCCESS) {
      gatt?.let { bluetoothGatt ->
        bluetoothGatt.getService(ENGAGEMENT_SERVICE)?.let {
          it.getCharacteristic(ENGAGEMENT_CHARACTERISTIC)?.let { characteristic ->
            Logger.d(TAG, "onServicesDiscovered: characteristic=${characteristic.uuid}")

            try {
              bluetoothGatt.setCharacteristicNotification(characteristic, true)
              onDeviceConnected(gatt, characteristic)

            } catch (e: SecurityException) {
              Logger.e(TAG, "Ble onServicesDiscovered error $e")
            }
          }
        }
      }
    }
  }

  private fun onDeviceConnected(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
    val builder = PresentationHelper.Builder(
      context,
      presentationListener,
      context.mainExecutor(),
      session
    )

    val msg = "Hello From Android!"
    sendMessage(gatt, characteristic, msg)

  }

  private val presentationListener = object : PresentationHelper.Listener {

    override fun onDeviceRequest(deviceRequestBytes: ByteArray) {
      log("Presentation Listener (QR): OnDeviceRequest")
//      onNewDeviceRequest(deviceRequestBytes)

    }

    override fun onDeviceDisconnected(transportSpecificTermination: Boolean) {
      log("Presentation Listener (QR): onDeviceDisconnected")
      onDisconnected()
    }

    override fun onError(error: Throwable) {
      log("Presentation Listener (QR): onError -> ${error.message}")
      onError(error)
    }
  }

  override fun onCharacteristicRead(
    gatt: BluetoothGatt,
    characteristic: BluetoothGattCharacteristic,
    value: ByteArray,
    status: Int
  ) {
    Logger.d(
      TAG,
      "onCharacteristicRead: uuid='${characteristic.uuid}' status='$status' value='$value'"
    )
  }

  override fun onCharacteristicWrite(
    gatt: BluetoothGatt?,
    characteristic: BluetoothGattCharacteristic?,
    status: Int
  ) {
    characteristic?.let {
      val value = String(it.value)
      Logger.d(TAG, "onCharacteristicWrite: uuid='${characteristic?.uuid}' status='$status' value='$value'" )
    }
  }

  override fun onReliableWriteCompleted(gatt: BluetoothGatt?, status: Int) {
    Logger.d(TAG, "onReliableWriteCompleted: status='$status'" )
  }


  override fun onCharacteristicChanged(
    gatt: BluetoothGatt,
    characteristic: BluetoothGattCharacteristic,
    value: ByteArray
  ) {
    Logger.d(TAG, "onCharacteristicChanged: uuid='${characteristic.uuid}' value='$value'")
  }

  override fun onServiceChanged(gatt: BluetoothGatt) {
    Logger.d(TAG, "onServiceChanged:")
  }

  private fun sendMessage(
    gatt: BluetoothGatt,
    characteristic: BluetoothGattCharacteristic,
    value: String
  ) {
    try {
      characteristic.setValue(value.toByteArray())
      gatt.writeCharacteristic(characteristic)
    } catch (e: SecurityException) {
      Logger.e(TAG, "Ble onServicesDiscovered error $e")
      onError(e)
    }
  }
}
