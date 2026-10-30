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
 * Key repeat events are not portable across devices (volume keys can be consumed by the system and
 * may not receive repeated [KeyEvent.ACTION_DOWN] events), so the repeat loop is driven by a
 * coroutine after the initial key-down event.
 */
internal class VolumeKeyPageTurnController(
    private val scope: CoroutineScope,
    private val enabled: () -> Boolean,
) {
    private var repeatJob: Job? = null
    private var turnedDuringPress = false

    /**
     * Returns false when volume-key paging is disabled, in which case the event should fall through
     * to the system so the volume keys keep their normal function.
     */
    fun handle(event: KeyEvent, turnPage: () -> Unit): Boolean {
        if (!enabled()) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
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
            }
            KeyEvent.ACTION_UP -> {
                repeatJob?.cancel()
                repeatJob = null
                if (!turnedDuringPress) turnPage()
                turnedDuringPress = false
            }
            else -> return false
        }
        return true
    }
}
