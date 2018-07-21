package sg.edu.ri.ivpsoundalert

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGatt.GATT_SUCCESS
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import com.jakewharton.rxrelay2.PublishRelay
import io.reactivex.Completable
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Single
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class RxBle {

	private val btg = AtomicReference<BluetoothGatt?>(null)
	private val relay = PublishRelay.create<BleEvent>()

	val connected: Boolean get() = btg.get() != null

	fun connectedTo(deviceName: String) : Boolean = btg.get()?.device?.name == deviceName

	fun connect(context: Context, deviceName: String) : Observable<BluetoothGatt> {
		return disconnect()
		.andThen(startScan(deviceName))
		.flatMapObservable { device -> connectGatt(context, device) }
	}

	private fun connectGatt(context: Context, device: BluetoothDevice) : Observable<BluetoothGatt> {
		return relay
		.doOnSubscribe { device.connectGatt(context, true, gattCallback) }
		.takeUntil { it === Disconnected || it === Abort } // completes upon disconnection or abortion
		.map { if (it is BleError) throw it.t else it }
		.filter { it is ServicesDiscovered }
		.map { (it as ServicesDiscovered).gatt }
		.doOnNext { btg.set(it) }
		.doOnComplete { btg.get()?.close(); btg.set(null) }
	}

	fun disconnect() : Completable {
		return stopScan()
		.doOnTerminate { relay.accept(Abort); btg.get()?.disconnect() }
	}

	fun enableNotifications(c: BluetoothGattCharacteristic, uuid: UUID) : Boolean {
		if (btg.get()?.setCharacteristicNotification(c, true) == false) return false
		val d = c.getDescriptor(uuid)
		return writeDescIgnoreResponse(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
	}

	fun writeCha(c: BluetoothGattCharacteristic, data: ByteArray) : Single<ByteArray> {
		return relay
		.doOnSubscribe {
			c.value = data
			if (btg.get()?.writeCharacteristic(c) == false) throw RuntimeException("writeCharacteristic: write error")
		}
		.filter { event -> event is CharacteristicChanged && event.c.uuid == c.uuid }
		.map { (it as CharacteristicChanged).c.value }
		.firstOrError()
	}

	fun writeChaIgnoreResponse(c: BluetoothGattCharacteristic, data: ByteArray) : Boolean {
		c.value = data
		return btg.get()?.writeCharacteristic(c) == true
	}

	fun writeDescIgnoreResponse(d: BluetoothGattDescriptor, data: ByteArray) : Boolean {
		d.value = data
		return btg.get()?.writeDescriptor(d) == true
	}

	fun startScan(deviceName: String) : Maybe<BluetoothDevice> {
		val adapter = BluetoothAdapter.getDefaultAdapter()
		val scanner = adapter.bluetoothLeScanner
		val scanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
		val scanFilter = ScanFilter.Builder().setDeviceName(deviceName).build()

		return relay
		.doOnSubscribe {
			scanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
		}
		.takeWhile { it !== Abort } // completes upon abortion (does not propagate abort event itself)
		.map {
			scanner.stopScan(scanCallback)
			when (it) {
				is ScanFound -> it.device
				is ScanFailed -> throw IllegalStateException("onScanFailed: ${it.errorCode}")
				else -> throw IllegalStateException("Unexpected event while scanning: $it")
			}
		}
		.firstElement()
	}

	fun stopScan() : Completable {
		return Completable.create { emitter ->
			BluetoothAdapter.getDefaultAdapter().bluetoothLeScanner?.stopScan(scanCallback)
			emitter.onComplete()
		}
	}

	private val scanCallback = object : ScanCallback() {
		override fun onScanFailed(errorCode: Int) {
			super.onScanFailed(errorCode)
			relay.accept(ScanFailed(errorCode))
		}

		override fun onScanResult(callbackType: Int, result: ScanResult) {
			super.onScanResult(callbackType, result)
			relay.accept(ScanFound(result.device))
			BluetoothAdapter.getDefaultAdapter().bluetoothLeScanner?.stopScan(this)
		}
	}

	private val gattCallback = object : BluetoothGattCallback() {
		override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
			super.onConnectionStateChange(gatt, status, newState)
			if (newState == BluetoothProfile.STATE_CONNECTED) {
				gatt.discoverServices() // must discover services first
				relay.accept(Connected(gatt))
			} else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
				relay.accept(Disconnected)
			}
		}

		override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
			super.onServicesDiscovered(gatt, status)
			relay.accept(ServicesDiscovered(gatt))
		}

		override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
		) {
			super.onCharacteristicRead(gatt, characteristic, status)
			if (status == GATT_SUCCESS) relay.accept(CharacteristicRead(characteristic))
			else relay.accept(BleError(IllegalStateException("onCharacteristicRead error: $status")))
		}

		override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
		) {
			super.onCharacteristicWrite(gatt, characteristic, status)
			if (status == GATT_SUCCESS) relay.accept(CharacteristicWrite(characteristic))
			else relay.accept(BleError(IllegalStateException("onCharacteristicWrite error: $status")))
		}

		override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
			super.onCharacteristicChanged(gatt, characteristic)
			relay.accept(CharacteristicChanged(characteristic))
		}

		override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
			super.onDescriptorWrite(gatt, descriptor, status)
			if (status == GATT_SUCCESS) relay.accept(DescriptorWrite(descriptor))
			else relay.accept(BleError(IllegalStateException("onDescriptorWrite error: $status")))
		}

		override fun onDescriptorRead(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
			super.onDescriptorRead(gatt, descriptor, status)
			if (status == GATT_SUCCESS) relay.accept(DescriptorRead(descriptor))
			else relay.accept(BleError(IllegalStateException("onDescriptorRead error: $status")))
		}
	}
}

sealed class BleEvent
object Abort : BleEvent()
data class ScanFailed(val errorCode: Int) : BleEvent()
data class ScanFound(val device: BluetoothDevice) : BleEvent()
object Disconnected : BleEvent()
data class Connected(val gatt: BluetoothGatt) : BleEvent()
data class ServicesDiscovered(val gatt: BluetoothGatt) : BleEvent()
data class CharacteristicRead(val c: BluetoothGattCharacteristic) : BleEvent()
data class CharacteristicWrite(val c: BluetoothGattCharacteristic) : BleEvent()
data class CharacteristicChanged(val c: BluetoothGattCharacteristic) : BleEvent()
data class DescriptorRead(val d: BluetoothGattDescriptor) : BleEvent()
data class DescriptorWrite(val d: BluetoothGattDescriptor) : BleEvent()
data class BleError(val t: Throwable) : BleEvent()


