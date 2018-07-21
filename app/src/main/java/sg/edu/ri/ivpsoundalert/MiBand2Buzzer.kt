package sg.edu.ri.ivpsoundalert

import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import java.util.UUID
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class MiBand2Buzzer(private val context: Context) : Buzzer {
	private val ble = RxBle()
	private val alertCha = AtomicReference<BluetoothGattCharacteristic?>(null)

	override val name: String = DEVICE_NAME
	override val ready: Boolean get() = alertCha.get() != null

	override fun open(listener: Listener?) {
		val d = connect(alertService, alertCharacteristic, listener)
		.subscribe(
			{ c -> alertCha.set(c) },
			{ t -> listener?.error(t) },
			{ alertCha.set(null) }
		)
	}

	override fun close() {
		ble.disconnect()
		.subscribe()
	}

	override fun buzz(durationMs: Long) {
		val on = ready()
		.doOnSuccess { c -> ble.writeChaIgnoreResponse(c, vibrateOn) }

		val onoff = on.delay(durationMs, MILLISECONDS)
		.doOnSuccess { c -> ble.writeChaIgnoreResponse(c, vibrateOff) }

		run { if (durationMs > 0) onoff else on }
		.subscribeOn(Schedulers.io())
		.ignoreElement()
		.onErrorComplete()
		.subscribe()
	}

	override fun mute() {
		ready()
		.subscribeOn(Schedulers.io())
		.doOnSuccess { c -> ble.writeChaIgnoreResponse(c, vibrateOff) }
		.ignoreElement()
		.onErrorComplete()
		.subscribe()
	}

	fun authenticate(listener: Listener?) {
		connect(authService, authCharacteristic, listener)
		.subscribeOn(Schedulers.io())
		.flatMapSingle { c ->
			if (!ble.enableNotifications(c, authNotification)) {
				throw IllegalStateException("authenticate: enableNotifications failed")
			}
			ble.writeCha(c, authSendKeyCmd) // send secret key
			.flatMap { rsp ->
				if (!rsp.contentEquals(authSendKeyResponse)) { // verify expected response
					throw IllegalStateException("sendSecretKey: unexpected response")
				}
				// request random number
				ble.writeCha(c, authRequestRandomNumberCmd)
			}
			.flatMap {
				// verify expected response
				val ok = it.size == 19 && it.sliceArray(0..2).contentEquals(authRequestRandomNumberResponse)
				if (!ok) throw IllegalStateException("requestRandomNumber: unexpected response")

				// extract and encrypt random number
				val random = it.sliceArray(3..(it.size-1))
				val cipher = Cipher.getInstance("AES/ECB/NoPadding")
				val key = SecretKeySpec(authSecretKey, "AES")
				cipher.init(Cipher.ENCRYPT_MODE, key)
				val encrypted = cipher.doFinal(random)

				// write encrypted random number
				ble.writeCha(c, byteArrayOf(authSendEncryptedAuthNumber, authByte) + encrypted)
				.map {
					if (!it.contentEquals(authSendEncryptedNumberResponse)) {
						throw IllegalStateException("sendEncryptedNumberResponse: unexpected response")
					}
				}
			}
		}
		.doOnNext { listener?.authenticated() }
		.doOnError { listener?.error(it) }
		.onErrorReturnItem(Unit)
		.flatMapCompletable { ble.disconnect() }
		.subscribe()
	}

	private fun ready() : Single<BluetoothGattCharacteristic> {
		return alertCha.get()?.run { Single.just(this) } ?: Single.error(IllegalStateException("not ready"))
	}

	private fun connect(service: UUID, cha: UUID, listener: Listener?) : Observable<BluetoothGattCharacteristic> {
		return ble.connect(context, DEVICE_NAME)
		.map { gatt -> gatt.getService(service).getCharacteristic(cha) }
		.doOnError { listener?.error(it) }
		.onErrorResumeNext(Observable.empty()) // to complete the observable
		.doOnNext { listener?.ready() }
		.doOnComplete { listener?.closed() }
	}
}

private const val DEVICE_NAME = "MI Band 2"

private val authService = UUID.fromString("0000fee1-0000-1000-8000-00805f9b34fb")
private val authCharacteristic = UUID.fromString("00000009-0000-3512-2118-0009af100700")
private val authNotification = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
private const val authSendKey: Byte = 0x01
private const val authRequestRandomAuthNumber: Byte = 0x02
private const val authSendEncryptedAuthNumber: Byte = 0x03
private const val authResponse: Byte = 0x10
private const val authSuccess: Byte = 0x01
private const val authFail: Byte = 0x04
private const val authByte: Byte = 0x8
private val authSecretKey = byteArrayOf(0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x40, 0x41, 0x42, 0x43, 0x44, 0x45)
private val authSendKeyCmd = byteArrayOf(authSendKey, authByte) + authSecretKey
private val authSendKeyResponse = byteArrayOf(authResponse, authSendKey, authSuccess)
private val authRequestRandomNumberCmd = byteArrayOf(authRequestRandomAuthNumber, authByte)
private val authRequestRandomNumberResponse = byteArrayOf(authResponse, authRequestRandomAuthNumber, authSuccess)
private val authSendEncryptedNumberResponse = byteArrayOf(authResponse, authSendEncryptedAuthNumber, authSuccess)

private val alertService = UUID.fromString("00001802-0000-1000-8000-00805f9b34fb")
private val alertCharacteristic = UUID.fromString("00002a06-0000-1000-8000-00805f9b34fb")
private val vibrateOn = byteArrayOf(3) // 1 - message icon | 2 - phone icon | 3 - no icon
private val vibrateOff = byteArrayOf(0)