package sg.edu.ri.ivpsoundalert

import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.Manifest.permission.BLUETOOTH
import android.Manifest.permission.BLUETOOTH_ADMIN
import android.Manifest.permission.RECORD_AUDIO
import android.Manifest.permission.VIBRATE
import android.animation.ObjectAnimator
import android.content.res.ColorStateList
import android.os.Bundle
import android.support.design.widget.FloatingActionButton
import android.support.design.widget.Snackbar
import android.support.v7.app.AppCompatActivity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import com.daasuu.ei.Ease
import com.daasuu.ei.EasingInterpolator
import com.github.florent37.runtimepermission.kotlin.askPermission
import kotlinx.android.synthetic.main.activity_main.power
import kotlinx.android.synthetic.main.activity_main.root
import kotlinx.android.synthetic.main.activity_main.toolbar
import kotlinx.android.synthetic.main.content_main.chart
import kotlinx.android.synthetic.main.content_main.scanning
import kotlinx.android.synthetic.main.content_main.watch
import kotlinx.android.synthetic.main.content_main.watchStatus
import sg.edu.ri.ivpsoundalert.WatchState.FOUND
import sg.edu.ri.ivpsoundalert.WatchState.NONE
import sg.edu.ri.ivpsoundalert.WatchState.SCANNING


class MainActivity : AppCompatActivity() {
	private val pitchColor: Int by lazy { resources.getColor(R.color.colorPitch) }
	private val splColor: Int by lazy { resources.getColor(R.color.colorSpl) }
	private val pitchLabel: String by lazy { getString(R.string.pitch_label) }
	private val splLabel: String by lazy { getString(R.string.spl_label) }
	private val powerOnIconColor: ColorStateList by lazy { ColorStateList.valueOf(resources.getColor(R.color.colorFabOnIcon)) }
	private val powerOffIconColor: ColorStateList by lazy { ColorStateList.valueOf(resources.getColor(R.color.colorFabOffIcon)) }
	private val powerOnBackgroundColor: ColorStateList by lazy { ColorStateList.valueOf(resources.getColor(R.color.colorFabOnBackground)) }
	private val powerOffBackgroundColor: ColorStateList by lazy { ColorStateList.valueOf(resources.getColor(R.color.colorFabOffBackground)) }
	private val watchDisplay: WatchDisplay by lazy { WatchDisplay() }

	private val detector = Detector()
	private val remoteBuzzer: Buzzer = MiBand2Buzzer(this)
	private val localBuzzer: Buzzer = PhoneBuzzer(this)


	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_main)
		setSupportActionBar(toolbar)

		power.setOnClickListener { v ->
			if (!detector.active) {

				askPermission(RECORD_AUDIO) {
					power.on()
					chart.reset()

					detector.open { start, end, pitch, rms, isPitched ->
						val detected = isPitched && filterPitched(start, end)
						runOnUiThread {
							chart.addEntry(pitchLabel, start, pitch, detected, pitchColor, true)
							chart.addEntry(splLabel, start, rms, false, splColor, false)
							if (detected) {
								if (remoteBuzzer.ready) {
									watchDisplay.buzzing(BUZZ_DURATION)
									remoteBuzzer.buzz(BUZZ_DURATION)
								}
								if (localBuzzer.ready) {
									localBuzzer.buzz(BUZZ_DURATION)
								}
							}
						}
					}
				}

				askPermission(VIBRATE) { // granted by default unless disabled by user
					localBuzzer.open { event ->
						if (event is Error) {
							Snackbar.make(root, "${localBuzzer.name}: ${event.t}", Snackbar.LENGTH_LONG).show()
						}
					}
				}

				askPermission(BLUETOOTH, BLUETOOTH_ADMIN, ACCESS_COARSE_LOCATION) {
					when (watchDisplay.state) {
						NONE -> {
							watchDisplay.scanning()
							remoteBuzzer.open { runOnUiThread {
								when (it) {
									is Ready -> watchDisplay.found(remoteBuzzer.name)
									is Closed -> watchDisplay.none()
									is Error -> Snackbar.make(v, "${remoteBuzzer.name}: ${it.t}", Snackbar.LENGTH_LONG).show()
								}
							}}
						}
						SCANNING -> { remoteBuzzer.close() }
						FOUND -> { remoteBuzzer.close() }
					}
				}

			} else {
				power.off()
				detector.close(); lastDetected = 0f
				localBuzzer.close()
				remoteBuzzer.close()
			}
		}
	}

	override fun onDestroy() {
		super.onDestroy()
		detector.close()
		localBuzzer.close()
		remoteBuzzer.close()
	}

	override fun onCreateOptionsMenu(menu: Menu): Boolean {
		// Inflate the menu; this adds items to the action bar if it is present.
		menuInflater.inflate(R.menu.menu_main, menu)
		return true
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		// Handle action bar item clicks here. The action bar will
		// automatically handle clicks on the Home/Up button, so long
		// as you specify a parent activity in AndroidManifest.xml.
		return when (item.itemId) {
			//R.id.action_authenticate -> { buzzer.authenticate(); true }
			R.id.action_settings -> true
			else -> super.onOptionsItemSelected(item)
		}
	}

	private fun FloatingActionButton.on() {
		imageTintList = powerOnIconColor
		backgroundTintList = powerOnBackgroundColor
	}

	private fun FloatingActionButton.off() {
		imageTintList = powerOffIconColor
		backgroundTintList = powerOffBackgroundColor
	}

	private var lastDetected = 0f
	private fun filterPitched(start: Float, end: Float) : Boolean {
		val pitchVals = chart.getYValues(pitchLabel, start, end)
		val avg = pitchVals?.average() ?: 0.0
		val peak = pitchVals?.max()?.toDouble() ?: 0.0
		val detected = avg > MIN_PITCH_AVERAGE && peak > MIN_PITCH_PEAK && (end - lastDetected) > MIN_DETECTION_INTERVAL
		if (detected) lastDetected = end
		return detected
	}

	inner class WatchDisplay {
		private val noneColor = ColorStateList.valueOf(resources.getColor(R.color.colorWatchNone))
		private val foundColor = ColorStateList.valueOf(resources.getColor(R.color.colorWatchFound))
		private val buzzingColor = ColorStateList.valueOf(resources.getColor(R.color.colorWatchBuzzing))

		var state: WatchState = NONE
			private set

		fun none() {
			state = NONE
			watch.imageTintList = noneColor
			watchStatus.text = null
			scanning.visibility = View.GONE
		}

		fun scanning() {
			state = SCANNING
			watch.imageTintList = noneColor
			watchStatus.text = null
			scanning.visibility = View.VISIBLE
		}

		fun found(deviceName: String) {
			state = FOUND
			watch.imageTintList = foundColor
			watchStatus.text = deviceName
			scanning.visibility = View.GONE
		}

		fun buzzing(durationMs: Long) {
			ObjectAnimator.ofFloat(watch, View.TRANSLATION_X, 0f, 25f, 0f, 25f, 0f).run {
				duration = durationMs
				interpolator = EasingInterpolator(Ease.ELASTIC_IN_OUT)
				start()
			}

			watch.imageTintList = buzzingColor
			watchStatus.setTextColor(buzzingColor)
			watch.postDelayed({
				watch.imageTintList = foundColor // restore color
				watchStatus.setTextColor(foundColor)
			}, durationMs)
		}
	}
}

enum class WatchState { NONE, SCANNING, FOUND }

private const val MIN_DETECTION_INTERVAL = 1.0 // in seconds
private const val MIN_PITCH_AVERAGE = 1500.0
private const val MIN_PITCH_PEAK = 2000.0

private const val BUZZ_DURATION = 1000L
