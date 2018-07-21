package sg.edu.ri.ivpsoundalert

import android.content.Context
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.os.VibrationEffect
import android.os.VibrationEffect.DEFAULT_AMPLITUDE
import android.os.Vibrator

class PhoneBuzzer(context: Context) : Buzzer {
	private val vibrator: Vibrator by lazy { context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator }
	private var listener: Listener? = null

	override val name: String = "Vibrator"
	override val ready: Boolean = true

	override fun open(listener: Listener?) {
		this.listener = listener
		listener?.ready()
	}

	override fun close() {
		listener?.closed()
	}

	override fun buzz(durationMs: Long) {
		if (VERSION.SDK_INT >= VERSION_CODES.O) {
			vibrator.vibrate(VibrationEffect.createOneShot(durationMs, DEFAULT_AMPLITUDE))
		} else {
			vibrator.vibrate(durationMs)
		}
	}

	override fun mute() {
		vibrator.cancel()
	}

}