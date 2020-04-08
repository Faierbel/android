package pl.gov.mc.protego.bluetooth.advertiser

import android.bluetooth.*
import android.content.Context
import pl.gov.mc.protego.bluetooth.ProteGoCharacteristicUUIDString
import pl.gov.mc.protego.bluetooth.ProteGoServiceUUIDString
import pl.gov.mc.protego.bluetooth.safeCurrentThreadHandler
import pl.gov.mc.protego.bluetooth.toHexString
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.util.*
import kotlin.collections.HashMap

class ProteGoGattServer private constructor(
    private val context: Context,
    private val callback: ProteGoGattServerCallback
) : BluetoothGattServerCallback() {

    companion object {
        fun startGattServer(
            context: Context,
            callback: ProteGoGattServerCallback,
            gattServerCreator: (BluetoothGattServerCallback) -> BluetoothGattServer?
        ): ServerResult {
            val proteGoGattServer = ProteGoGattServer(context, callback)
            val gattServer = gattServerCreator(proteGoGattServer)
            if (gattServer == null) {
                Timber.w("failed to open GATT server")
                return ServerResult.Failure.CannotObtainGattServer
            }
            return proteGoGattServer.initialize(gattServer) ?: ServerResult.Success(proteGoGattServer)
        }
    }

    // This should be nullable as there is potential race condition in the API between callback
    // registration and receiving GattServer instance.
    private var gattServer: BluetoothGattServer? = null

    // Hash map containing pending values for the characteristic.
    private var pendingWrites: HashMap<BluetoothDevice, ByteArray> = HashMap()

    // Hash map containing RSSI grabbers.
    private var rssiLatches: HashMap<BluetoothDevice, ProteGoGattRSSILatch> = HashMap()

    private var bluetoothHandler = safeCurrentThreadHandler()

    private fun BluetoothDevice.latchRssi() {
        rssiLatches[this] = ProteGoGattRSSILatch(context, this, bluetoothHandler)
    }

    private fun BluetoothDevice.getLatchedRssi() = rssiLatches[this]?.rssi

    // Lifecycle -----------------------------------------------------------------------------------

    private fun initialize(gattServer: BluetoothGattServer): ServerResult.Failure? {
        check(this.gattServer == null) {
            gattServer.close()
            "Please create a new instance of ProteGoGattServer for a new GATT server handle"
        }
        this.gattServer = gattServer

        val gattCharacteristic = BluetoothGattCharacteristic(
            UUID.fromString(ProteGoCharacteristicUUIDString),
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        val gattService = BluetoothGattService(
            UUID.fromString(ProteGoServiceUUIDString),
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        if (!gattService.addCharacteristic(gattCharacteristic)) {
            Timber.d("Failed to add characteristic")
            gattServer.close()
            return ServerResult.Failure.CannotAddCharacteristic
        }

        if (!gattServer.addService(gattService)) {
            Timber.d("Failed to add service")
            gattServer.close()
            return ServerResult.Failure.CannotAddService
        }

        return null
    }

    fun close(): Boolean {
        val gattServer = this.gattServer
        if (gattServer == null) {
            Timber.d("GATT server already closed")
            return false
        }

        pendingWrites.clear()
        rssiLatches.values.forEach { it.cancel() }
        rssiLatches.clear()
        gattServer.close()
        this.gattServer = null
        Timber.d("GATT server closed")
        return true
    }

    // GATT Server callbacks -----------------------------------------------------------------------

    override fun onDescriptorReadRequest(
        device: BluetoothDevice,
        requestId: Int,
        offset: Int,
        descriptor: BluetoothGattDescriptor
    ) {
        super.onDescriptorReadRequest(device, requestId, offset, descriptor)
        Timber.d("onDescriptorReadRequest, device=${device.address}, requestId=${requestId}, offset=${offset}, desc=${descriptor.uuid}")
        withGattServer("onDescriptorReadRequest") {
            sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null)
        }
    }

    override fun onNotificationSent(device: BluetoothDevice, status: Int) {
        super.onNotificationSent(device, status)
        Timber.d("onNotificationSent, device=${device.address}, status=${status}")
    }

    override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
        super.onMtuChanged(device, mtu)
        Timber.d("onMtuChanged, device=${device.address}, mtu=${mtu}")
    }

    override fun onPhyUpdate(device: BluetoothDevice, txPhy: Int, rxPhy: Int, status: Int) {
        super.onPhyUpdate(device, txPhy, rxPhy, status)
        Timber.d("onPhyUpdate, device=${device.address}, txPhy=${txPhy}, rxPhy=${rxPhy}, status=${status}")
    }

    override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
        super.onExecuteWrite(device, requestId, execute)
        Timber.d("onExecuteWrite, device=${device.address}, reqId=${requestId}, execute=${execute}")

        var value: ByteArray? = null
        var status = BluetoothGatt.GATT_WRITE_NOT_PERMITTED

        if (execute) {
            // Let's execute pending writes.
            val pendingWrite = this.pendingWrites.remove(device)
            if (pendingWrite != null) {
                status = BluetoothGatt.GATT_SUCCESS
                value = pendingWrite
                this.callback.receivedTokenData(this, pendingWrite, device.getLatchedRssi())
            }
        } else {
            // We always allow cancelling request.
            this.pendingWrites.remove(device)
        }

        withGattServer("onExecuteWrite") {
            sendResponse(device, requestId, status, 0, value)
        }
    }

    override fun onCharacteristicWriteRequest(
        device: BluetoothDevice,
        requestId: Int,
        characteristic: BluetoothGattCharacteristic,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray
    ) {
        super.onCharacteristicWriteRequest(
            device,
            requestId,
            characteristic,
            preparedWrite,
            responseNeeded,
            offset,
            value
        )
        Timber.d("onCharacteristicWriteRequest, device=${device.address}, reqId=${requestId}, char=${characteristic.uuid}, prepWrite=${preparedWrite}, responseNeeded=${responseNeeded}, offset=${offset}, value=${value.toHexString()}")
        var status = BluetoothGatt.GATT_WRITE_NOT_PERMITTED

        if (preparedWrite) {
            // Got prepared write
            val pendingValue = this.pendingWrites[device]
            val expectedOffset = (pendingValue?.size ?: 0)
            if (expectedOffset == offset) {
                val stream = ByteArrayOutputStream(expectedOffset + value.size)
                if (pendingValue != null) {
                    stream.write(pendingValue)
                }
                stream.write(value)
                this.pendingWrites[device] = stream.toByteArray()
                status = BluetoothGatt.GATT_SUCCESS
            }
        } else {
            // Got simple write.
            status = BluetoothGatt.GATT_SUCCESS
            this.callback.receivedTokenData(this, value, device.getLatchedRssi())
        }

        if (responseNeeded) {
            withGattServer("onCharacteristicWriteRequest") {
                sendResponse(device, requestId, status, offset, value)
            }
        }
    }

    override fun onCharacteristicReadRequest(
        device: BluetoothDevice,
        requestId: Int,
        offset: Int,
        characteristic: BluetoothGattCharacteristic
    ) {
        super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
        Timber.d("onCharacteristicReadRequest, device: ${device.address}, reqId=${requestId}, offset=${offset}, char=${characteristic.uuid}")

        var value: ByteArray? = null
        var status = BluetoothGatt.GATT_READ_NOT_PERMITTED

        val tokenData = this.callback.getTokenData(this)
        if (tokenData != null && offset < tokenData.size) {
            value = tokenData.sliceArray(offset until tokenData.size)
            status = BluetoothGatt.GATT_SUCCESS
        }

        withGattServer("onCharacteristicReadRequest") {
            sendResponse(device, requestId, status, offset, value)
        }
    }

    override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
        super.onConnectionStateChange(device, status, newState)
        Timber.d("onConnectionStateChange, device=${device.address}, status=${status}, newState=${newState}")
        if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            this.pendingWrites.remove(device)
            this.rssiLatches.remove(device)
        }
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            device.latchRssi()
        }
    }

    override fun onPhyRead(device: BluetoothDevice, txPhy: Int, rxPhy: Int, status: Int) {
        super.onPhyRead(device, txPhy, rxPhy, status)
        Timber.d("onPhyRead, device=${device.address}, txPhy=${txPhy}, rxPhy=${rxPhy}, status=${status}")
    }

    override fun onDescriptorWriteRequest(
        device: BluetoothDevice,
        requestId: Int,
        descriptor: BluetoothGattDescriptor,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray
    ) {
        super.onDescriptorWriteRequest(
            device,
            requestId,
            descriptor,
            preparedWrite,
            responseNeeded,
            offset,
            value
        )
        Timber.d("onDescriptorWriteRequest, device=${device.address}, reqId=${requestId}, descriptor=${descriptor.uuid}, prepWrite=${preparedWrite}, responseNeeded=${responseNeeded}, offset=${offset}, value=${value.toHexString()}")
        if (responseNeeded) {
            withGattServer("onDescriptorWriteRequest") {
                sendResponse(device, requestId, BluetoothGatt.GATT_WRITE_NOT_PERMITTED, offset, null)
            }
        }
    }

    override fun onServiceAdded(status: Int, service: BluetoothGattService) {
        super.onServiceAdded(status, service)
        Timber.d("onServiceAdded status=${status}, service=${service.uuid}")
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Timber.e("failed to add service: ${service.uuid ?: "-"}")
            callback.gattServerFailed(this)
            return
        } else {
            callback.gattServerStarted(this)
        }
    }

    private inline fun withGattServer(callbackName: String, crossinline call: BluetoothGattServer.() -> Boolean) {
        when (this.gattServer?.run(call)) {
            null -> Timber.e("[$callbackName] Cannot use BluetoothGattServer. Reference is 'null'.")
            false -> Timber.w("[$callbackName] BluetoothGattServer returned 'false'")
            true -> Unit
        }
    }
}