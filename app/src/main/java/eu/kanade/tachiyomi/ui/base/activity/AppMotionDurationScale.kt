package eu.kanade.tachiyomi.ui.base.activity

import android.content.Context
import android.provider.Settings
import androidx.compose.ui.MotionDurationScale
import tachiyomi.core.common.preference.Preference

/**
 * Window-level Compose motion scale.
 *
 * Returning 0 makes every Compose animation finish on the next frame, including animations that
 * don't use Material's MotionScheme. When the option is off we defer to Android's animator duration
 * scale so accessibility settings keep working.
 */
internal class AppMotionDurationScale(
    private val context: Context,
    private val reduceMotion: Preference<Boolean>,
) : MotionDurationScale {
    override val scaleFactor: Float
        get() = if (reduceMotion.get()) {
            0f
        } else {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        }
}
