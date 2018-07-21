package sg.edu.ri.ivpsoundalert

import be.tarsos.dsp.AudioDispatcher
import be.tarsos.dsp.io.android.AudioDispatcherFactory
import be.tarsos.dsp.pitch.PitchProcessor
import be.tarsos.dsp.pitch.PitchProcessor.PitchEstimationAlgorithm

class Detector {
	private var dispatcher: AudioDispatcher? = null
	private var detectorThread: Thread? = null

	fun open(listener: (Float, Float, Float, Float, Boolean) -> Unit) {
		if (active) close()

		val pitchProcessor = PitchProcessor(PITCH_ALGO, SAMPLE_RATE, BUFFER_SIZE) { res, event ->
			listener(event.timeStamp.toFloat(), event.endTimeStamp.toFloat(), res.pitch, event.rms.toFloat(), res.isPitched)
		}

		dispatcher = AudioDispatcherFactory.fromDefaultMicrophone(SAMPLE_RATE.toInt(), BUFFER_SIZE, BUFFER_OVERLAP).apply { addAudioProcessor(pitchProcessor) }

		detectorThread = Thread(dispatcher).apply { start() }
	}

	fun close() {
		dispatcher?.run { if (!isStopped) stop() }
		dispatcher = null
		detectorThread?.interrupt()
		detectorThread = null
	}

	val active : Boolean get() = dispatcher?.isStopped == false || detectorThread?.isAlive == true
}

private const val SAMPLE_RATE = 22050f //44100f
private const val BUFFER_SIZE = 1024
private const val BUFFER_OVERLAP = 0 //BUFFER_SIZE / 2

private val PITCH_ALGO = PitchEstimationAlgorithm.FFT_YIN