package sg.edu.ri.ivpsoundalert

import android.content.Context
import android.util.AttributeSet
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.MarkerImage
import com.github.mikephil.charting.components.YAxis.AxisDependency
import com.github.mikephil.charting.components.YAxis.AxisDependency.LEFT
import com.github.mikephil.charting.components.YAxis.AxisDependency.RIGHT
import com.github.mikephil.charting.data.DataSet.Rounding.CLOSEST
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet

class Chart @JvmOverloads constructor(
context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : LineChart(context, attrs, defStyleAttr) {

	init {
		description.isEnabled = false
		setDrawBorders(false)

		xAxis.isEnabled = false
		setPinchZoom(false)
		isDoubleTapToZoomEnabled = false
		setScaleEnabled(false)
		setMaxVisibleValueCount(100)
		isDragEnabled = false
		setTouchEnabled(false)
		isHighlightPerDragEnabled = false
		isHighlightPerTapEnabled = false

		setDrawMarkers(true)

		marker = MarkerImage(context, R.drawable.ic_notifications_active_white_24dp)
	}

	fun reset() {
		clear()
		data = LineData() // add empty data
	}

	fun addEntry(label: String, timestamp: Float, yVal: Float, detected: Boolean, color: Int, leftAxis: Boolean) {
		data?.run {
			var dataSet = getDataSetByLabel(label, true)
			if (dataSet == null) {
				dataSet = createDataSet(label, color, if (leftAxis) LEFT else RIGHT)
				addDataSet(dataSet)
			}
			val entry = Entry(timestamp, yVal)
			val index = getIndexOfDataSet(dataSet)
			addEntry(entry, index)
			notifyDataChanged()

			if (detected) highlightValue(timestamp, yVal, index, false)

			moveViewToX(timestamp) // move to the latest entry
			setVisibleXRange(5f, 5f) // limit the number of visible entries

			notifyDataSetChanged() // let the chart know its data has changed
		}
	}

	fun getYValues(label: String, startX: Float, endX: Float) : Array<Float>? {
		return data?.getDataSetByLabel(label, true)?.run {
			val startIndex = getEntryIndex(startX, 0f, CLOSEST)
			val endIndex = getEntryIndex(endX, 0f, CLOSEST)
			val count = endIndex - startIndex + 1
			Array(count) { i -> getEntryForIndex(startIndex + i).y }
		}
	}

	private fun createDataSet(label: String, color: Int, axis: AxisDependency): LineDataSet {
		val set = LineDataSet(null, label)
		set.axisDependency = axis
		set.color = color
		set.lineWidth = 2f

		set.setDrawValues(false)

		set.setDrawCircles(false)
		set.setDrawIcons(true)
		set.setDrawHighlightIndicators(false)

		set.setDrawFilled(true)
		set.fillAlpha = 50
		set.fillColor = color

		return set
	}

	fun enableDataSet(label: String, enable: Boolean) {
		val dataSet = data?.getDataSetByLabel(label, true)
		if (dataSet != null && !enable) data?.removeDataSet(dataSet)
	}
}