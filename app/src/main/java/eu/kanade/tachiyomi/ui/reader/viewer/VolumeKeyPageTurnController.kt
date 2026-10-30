package eu.kanade.tachiyomi.ui.reader.viewer

import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val VOLUME_KEY_REPEAT_START_DELAY_MILLIS = 450L
private const val VOLUME_KEY_REPEAT_INTERVAL_MILLIS = 250L

/**
 * Turns one page on a short volume-key press and keeps turning pages while it is held down.
 *
 * Some devices deliver volume-key autorepeat as new [KeyEvent.ACTION_DOWN] events whose
 * `repeatCount` stays at zero. A single pressed/released state is therefore tracked explicitly;
 * that state starts the repeat loop only once for the whole physical press.
 */
internal class VolumeKeyPageTurnController(
    private val scope: CoroutineScope,
    private val enabled: () -> Boolean,
) {
    private var repeatJob: Job? = null
    private var isPressed = false
    private var turnedDuringPress = false

    /**
     * Returns false when volume-key paging is disabled, in which case the event should fall through
     * to the system so the volume keys keep their normal function.
     */
    fun handle(event: KeyEvent, turnPage: () -> Unit): Boolean {
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (!enabled()) {
                    reset()
                    return false
                }
                if (!isPressed) {
                    isPressed = true
                    turnedDuringPress = false
                    repeatJob?.cancel()
                    repeatJob = scope.launch {
                        delay(VOLUME_KEY_REPEAT_START_DELAY_MILLIS)
                        while (isActive) {
                            if (!enabled()) break
                            turnedDuringPress = true
                            turnPage()
                            delay(VOLUME_KEY_REPEAT_INTERVAL_MILLIS)
                        }
                    }
                }
                true
            }
            KeyEvent.ACTION_UP -> {
                val wasPressed = isPressed
                val consume = wasPressed && enabled()
                val shouldTurn = consume && !turnedDuringPress
                reset()
                if (shouldTurn) turnPage()
                consume
            }
            else -> false
        }
    }

    private fun reset() {
        repeatJob?.cancel()
        repeatJob = null
        isPressed = false
        turnedDuringPress = false
    }
}
