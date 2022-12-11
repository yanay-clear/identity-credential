package com.android.mdl.appreader.transfer

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertiseSettings.Builder
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import com.android.identity.Logger
import java.util.UUID

class BleEngagementReader(
  private val context: Context,
  private val bluetoothManager: BluetoothManager,
  private val transferManager: TransferManager,
  private val onConnecting: () -> Unit,
  private val onConnected: () -> Unit,
  private val onDisconnected: () -> Unit,
  private val onError: (error: Throwable) -> Unit,
) : BluetoothGattServerCallback() {

  companion object {
    const val TAG = "BleEngagementReader"
    val ENGAGEMENT_SERVICE: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")
    val ENGAGEMENT_CHARACTERISTIC: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    val ENGAGEMENT_DESCRIPTOR: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
  }

  var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
  var gattServer: BluetoothGattServer? = null
  var engagementService: BluetoothGattService? = null
  var currentConnection: BluetoothDevice? = null

  val advertiseCallback = object : AdvertiseCallback() {
    override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
      Logger.d(TAG, "onStartSuccess BLE advertiser")
    }

    override fun onStartFailure(errorCode: Int) {
      Logger.e(TAG, "BLE advertise failed with error code $errorCode")
    }
  }

  fun connect() {
    if (!startGattServer()) {
      Logger.e(TAG, "Failed to start Gatt server")
      return
    }

    val bluetoothAdapter = bluetoothManager.adapter
    bluetoothLeAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser

    if (bluetoothLeAdvertiser == null) {
      Logger.e(TAG, "Failed to create BLE advertiser")

      stopServer()
    } else {
      val settings = Builder()
        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
        .setConnectable(true)
        .setTimeout(0)
        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
        .build()
      val data = AdvertiseData.Builder()
        .setIncludeTxPowerLevel(true)
        .addServiceUuid(ParcelUuid(ENGAGEMENT_SERVICE))
        .build()
      Logger.d(
        TAG,
        "Started advertising UUID $ENGAGEMENT_SERVICE"
      )
      bluetoothLeAdvertiser?.let {
        try {
          it.startAdvertising(settings, data, advertiseCallback)
        } catch (e: SecurityException) {
          Logger.e(TAG, "startAdvertising Error")
        }
      }
    }
  }

  private fun startGattServer(): Boolean {
    try {
      gattServer = bluetoothManager.openGattServer(context, this)
    } catch (e: SecurityException) {
      Logger.e(TAG, "openGattServer Error")
      return false
    }

    gattServer?.let { gatt ->
      engagementService =
        BluetoothGattService(ENGAGEMENT_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)

      engagementService?.let {
        val c = BluetoothGattCharacteristic(
          ENGAGEMENT_CHARACTERISTIC,
          BluetoothGattCharacteristic.PROPERTY_NOTIFY
              or BluetoothGattCharacteristic.PROPERTY_READ
              or BluetoothGattCharacteristic.PROPERTY_WRITE,
          BluetoothGattCharacteristic.PERMISSION_WRITE or BluetoothGattCharacteristic.PERMISSION_READ
        )

        val d =
          BluetoothGattDescriptor(ENGAGEMENT_DESCRIPTOR, BluetoothGattDescriptor.PERMISSION_WRITE)
        d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        c.addDescriptor(d)

        it.addCharacteristic(c)

        try {
          gatt.addService(engagementService)
          return true
        } catch (e: SecurityException) {
          Logger.e(TAG, "addService Error $e")
          return false
        }
      }
    }


    return false
  }

  override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
    Logger.d(TAG, "onConnectionStateChange: " + device!!.address + " " + status + " " + newState)

    var state = "" + newState
    when(newState) {
      BluetoothProfile.STATE_CONNECTED -> state = "STATE_CONNECTED"
      BluetoothProfile.STATE_CONNECTING -> state = "STATE_CONNECTING"
      BluetoothProfile.STATE_DISCONNECTED -> state = "STATE_DISCONNECTED"
      BluetoothProfile.STATE_DISCONNECTING -> state = "STATE_DISCONNECTING"
    }

    Logger.d(
      TAG, "BLE STATE = '$state'"
    )

    currentConnection?.let {
      if (newState == BluetoothProfile.STATE_DISCONNECTED && device.address == it.address) {
        Logger.d(
          TAG, "Device " + currentConnection?.address + " which we're currently "
              + "connected to, has disconnected"
        )
        currentConnection = null
      }
    }
  }

  override fun onCharacteristicReadRequest(
    device: BluetoothDevice?,
    requestId: Int,
    offset: Int,
    characteristic: BluetoothGattCharacteristic?
  ) {
    Logger.d(
      TAG,
      "onCharacteristicReadRequest: " + device!!.address + " " + requestId + " " + offset + " " + characteristic!!.uuid
    )
  }

  override fun onCharacteristicWriteRequest(
    device: BluetoothDevice?,
    requestId: Int,
    characteristic: BluetoothGattCharacteristic?,
    preparedWrite: Boolean,
    responseNeeded: Boolean,
    offset: Int,
    value: ByteArray?
  ) {
    val charUuid = characteristic!!.uuid
    Logger.d(
      TAG, "onCharacteristicWriteRequest: " + device!!.address + " "
          + characteristic.uuid + " value='${value?.size}'"
    )

    // If we are connected to a device, ignore write from any other device
    currentConnection?.let { connection ->
      device.let {
        if (it.address.equals(connection.address)) {
          Logger.e(
            TAG, "Ignoring characteristic write request from "
                + it.address + " since we're already connected to "
                + connection.address
          )
          return
        }
      }
    }

    if (charUuid.equals(ENGAGEMENT_CHARACTERISTIC)) {
      value?.let { byteArr ->
        val base64Encoded =  String(byteArr)
        currentConnection = device
        stopServer()
        transferManager.setQrDeviceEngagement(base64Encoded)

        Logger.d(
          TAG, "On Peer Connected!")
      }
    }
  }

  override fun onDescriptorReadRequest(
    device: BluetoothDevice?,
    requestId: Int,
    offset: Int,
    descriptor: BluetoothGattDescriptor?
  ) {
    Logger.d(
      TAG, "onDescriptorReadRequest: " + device!!.address + " "
          + descriptor!!.characteristic.uuid + " " + offset
    )
  }

  override fun onDescriptorWriteRequest(
    device: BluetoothDevice?,
    requestId: Int,
    descriptor: BluetoothGattDescriptor?,
    preparedWrite: Boolean,
    responseNeeded: Boolean,
    offset: Int,
    value: ByteArray?
  ) {
    Logger.d(
      TAG, "onDescriptorWriteRequest: " + device!!.address + " "
          + descriptor!!.characteristic.uuid + " "
    )
    if (responseNeeded) {
      try {
        gattServer?.sendResponse(
          device,
          requestId,
          BluetoothGatt.GATT_SUCCESS,
          0,
          null
        )
      } catch (e: SecurityException) {
        Logger.e(TAG, "sendResponse Error $e")
      }
    }
  }

  override fun onNotificationSent(device: BluetoothDevice?, status: Int) {
    Logger.d(TAG, "onNotificationSent " + status + " for " + device!!.address + " ")
  }

  private fun stopServer() {

    gattServer?.let { gatt ->
      try {
        currentConnection?.let { connection ->
          gatt.cancelConnection(connection)
          Logger.e(TAG, "cancelConnection success")
        }
        gatt.close()
      } catch (e: SecurityException) {
        Logger.e(TAG, "cancelConnection Error $e")
      }

      gattServer = null
    }
  }
}