package sg.edu.ri.ivpsoundalert

interface Buzzer {
	val name: String
	val ready: Boolean
	fun open(listener: Listener? = null)
	fun close()
	fun buzz(durationMs: Long = 0)
	fun mute()
}

typealias Listener = (Event) -> Unit

fun Listener.ready() = this(Ready)
fun Listener.closed() = this(Closed)
fun Listener.authenticated() = this(Authenticated)
fun Listener.error(t: Throwable) = this(Error(t))

sealed class Event
object Ready : Event()
object Closed : Event()
object Authenticated : Event()
data class Error(val t: Throwable) : Event()