package eu.kanade.presentation.reader

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

private const val QUICK_TURN_INTERVAL_MILLIS = 700L
private const val QUICK_PROGRESS_HIDE_DELAY_MILLIS = 900L

/**
 * Shows the current page and a thin progress bar only while the reader is being rapidly page-turned.
 *
 * There is intentionally no animation here. The progress width is quantized to whole percentages
 * so a small movement only causes the bar to redraw when it visibly changes by at least 1%.
 */
@Composable
fun ReaderQuickPageProgress(
    currentPage: Int,
    totalPages: Int,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(false) }
    var lastPage by remember { mutableIntStateOf(currentPage) }
    var lastTotalPages by remember { mutableIntStateOf(totalPages) }
    var lastChangeAt by remember { mutableLongStateOf(0L) }
    var progressPercent by remember { mutableIntStateOf(-1) }

    LaunchedEffect(currentPage, totalPages) {
        if (currentPage <= 0 || totalPages <= 0) {
            visible = false
            lastPage = currentPage
            lastTotalPages = totalPages
            lastChangeAt = 0L
            progressPercent = -1
            return@LaunchedEffect
        }

        val now = SystemClock.uptimeMillis()
        val pageChanged = currentPage != lastPage
        val chapterChanged = totalPages != lastTotalPages

        // A chapter transition is not a quick page turn. Start timing again from its first page.
        if (chapterChanged) {
            visible = false
            lastPage = currentPage
            lastTotalPages = totalPages
            lastChangeAt = now
            progressPercent = -1
        } else if (pageChanged) {
            visible = visible || now - lastChangeAt in 0 until QUICK_TURN_INTERVAL_MILLIS
            lastPage = currentPage
            lastChangeAt = now
        } else if (lastChangeAt == 0L) {
            lastChangeAt = now
        }

        val percent = (currentPage.toFloat() / totalPages * 100f)
            .roundToInt()
            .coerceIn(0, 100)
        if (progressPercent < 0 || abs(percent - progressPercent) >= 1) {
            progressPercent = percent
        }

        if (visible) {
            // This effect is cancelled and restarted by every page change, so the delay effectively
            // measures time since the last turn and cannot hide the bar while turning continues.
            delay(QUICK_PROGRESS_HIDE_DELAY_MILLIS)
            visible = false
        }
    }

    if (!visible) return

    val progress = progressPercent.coerceIn(0, 100) / 100f
    Surface(
        modifier = modifier.width(150.dp),
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$currentPage / $totalPages",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Box(
                modifier = Modifier
                    .padding(top = 5.dp)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(2.dp),
                    )
                    .semantics {
                        progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                    },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(3.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(2.dp),
                        ),
                )
            }
        }
    }
}
