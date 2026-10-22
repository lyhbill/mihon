package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.domain.manga.model.readerOrientation
import eu.kanade.domain.manga.model.readingMode
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.PageCropState
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import java.text.NumberFormat

@Composable
internal fun ColumnScope.ReadingModePage(viewModel: ReaderSettingsViewModel) {
    HeadingItem(MR.strings.pref_category_for_this_series)
    val manga by viewModel.mangaFlow.collectAsState()

    val readingMode = remember(manga) { ReadingMode.fromPreference(manga?.readingMode?.toInt()) }
    SettingsChipRow(MR.strings.pref_category_reading_mode) {
        ReadingMode.entries.map {
            FilterChip(
                selected = it == readingMode,
                onClick = { viewModel.onChangeReadingMode(it) },
                label = { Text(stringResource(it.stringRes)) },
            )
        }
    }

    val orientation = remember(manga) { ReaderOrientation.fromPreference(manga?.readerOrientation?.toInt()) }
    SettingsChipRow(MR.strings.rotation_type) {
        ReaderOrientation.entries.map {
            FilterChip(
                selected = it == orientation,
                onClick = { viewModel.onChangeOrientation(it) },
                label = { Text(stringResource(it.stringRes)) },
            )
        }
    }

    val viewer by viewModel.viewerFlow.collectAsState()
    if (viewer is WebtoonViewer) {
        WebtoonViewerSettings(viewModel)
    } else {
        PagerViewerSettings(viewModel)
    }
}

@Composable
private fun ColumnScope.PagerViewerSettings(viewModel: ReaderSettingsViewModel) {
    HeadingItem(MR.strings.pager_viewer)

    val navigationModePager by viewModel.preferences.navigationModePager.collectAsState()
    val pagerNavInverted by viewModel.preferences.pagerNavInverted.collectAsState()
    TapZonesItems(
        selected = navigationModePager,
        onSelect = viewModel.preferences.navigationModePager::set,
        invertMode = pagerNavInverted,
        onSelectInvertMode = viewModel.preferences.pagerNavInverted::set,
    )

    val imageScaleType by viewModel.preferences.imageScaleType.collectAsState()
    SettingsChipRow(MR.strings.pref_image_scale_type) {
        ReaderPreferences.ImageScaleType.mapIndexed { index, it ->
            FilterChip(
                selected = imageScaleType == index + 1,
                onClick = { viewModel.preferences.imageScaleType.set(index + 1) },
                label = { Text(stringResource(it)) },
            )
        }
    }

    val zoomStart by viewModel.preferences.zoomStart.collectAsState()
    SettingsChipRow(MR.strings.pref_zoom_start) {
        ReaderPreferences.ZoomStart.mapIndexed { index, it ->
            FilterChip(
                selected = zoomStart == index + 1,
                onClick = { viewModel.preferences.zoomStart.set(index + 1) },
                label = { Text(stringResource(it)) },
            )
        }
    }

    val numberFormat = remember { NumberFormat.getPercentInstance() }
    val secondsFormat = remember {
        NumberFormat.getNumberInstance().apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }
    }
    val previewDurationStep = ReaderPreferences.LANDSCAPE_ZOOM_PREVIEW_DURATION_STEP_MILLIS
    val pagerHorizontalPadding by viewModel.preferences.pagerHorizontalPadding.collectAsState()
    SliderItem(
        value = pagerHorizontalPadding,
        valueRange = ReaderPreferences.let { it.PAGER_PADDING_MIN..it.PAGER_PADDING_MAX },
        label = stringResource(MR.strings.pref_pager_horizontal_padding),
        valueString = numberFormat.format(
            pagerHorizontalPadding / ReaderPreferences.PAGER_PADDING_PERCENTAGE_DIVISOR,
        ),
        onChange = viewModel.preferences.pagerHorizontalPadding::set,
        pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    )

    val pagerVerticalPadding by viewModel.preferences.pagerVerticalPadding.collectAsState()
    SliderItem(
        value = pagerVerticalPadding,
        valueRange = ReaderPreferences.let { it.PAGER_PADDING_MIN..it.PAGER_PADDING_MAX },
        label = stringResource(MR.strings.pref_pager_vertical_padding),
        valueString = numberFormat.format(
            pagerVerticalPadding / ReaderPreferences.PAGER_PADDING_PERCENTAGE_DIVISOR,
        ),
        onChange = viewModel.preferences.pagerVerticalPadding::set,
        pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    )

    CheckboxItem(
        label = stringResource(MR.strings.pref_landscape_zoom),
        pref = viewModel.preferences.landscapeZoom,
    )

    val landscapeZoomPreview by viewModel.preferences.navigateToPan.collectAsState()
    CheckboxItem(
        label = stringResource(MR.strings.pref_navigate_pan),
        pref = viewModel.preferences.navigateToPan,
    )
    if (landscapeZoomPreview) {
        val durationPref = viewModel.preferences.landscapeZoomPreviewDurationMillis
        val duration by durationPref.collectAsState()
        SliderItem(
            value = duration / previewDurationStep,
            valueRange = ReaderPreferences.LANDSCAPE_ZOOM_PREVIEW_DURATION_STEPS,
            label = stringResource(MR.strings.pref_landscape_zoom_preview_duration),
            valueString = stringResource(
                MR.strings.pref_landscape_zoom_preview_duration_value,
                secondsFormat.format(duration / 1000.0),
            ),
            onChange = { durationPref.set(it * previewDurationStep) },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }

    val navigatePageSegments by viewModel.preferences.navigatePageSegments.collectAsState()
    CheckboxItem(
        label = stringResource(MR.strings.pref_navigate_page_segments),
        pref = viewModel.preferences.navigatePageSegments,
    )
    if (navigatePageSegments) {
        CheckboxItem(
            label = stringResource(MR.strings.pref_navigate_page_segments_backward),
            pref = viewModel.preferences.navigatePageSegmentsBackward,
        )
        CheckboxItem(
            label = stringResource(MR.strings.pref_navigate_page_segments_smoothly),
            pref = viewModel.preferences.navigatePageSegmentsSmoothly,
        )
    }

    val pageCropState by viewModel.pageCropState.collectAsState()
    CurrentScaleCropItem(
        state = pageCropState,
        onClick = viewModel::toggleCurrentPageCrop,
    )

    val cropBorders by viewModel.preferences.cropBorders.collectAsState()
    CheckboxItem(
        label = stringResource(MR.strings.pref_crop_borders),
        checked = cropBorders,
        onClick = viewModel::toggleCropBorders,
    )

    CheckboxItem(
        label = stringResource(MR.strings.pref_disable_pager_swipe),
        pref = viewModel.preferences.disablePagerSwipe,
    )

    val dualPageSplitPaged by viewModel.preferences.dualPageSplitPaged.collectAsState()
    CheckboxItem(
        label = stringResource(MR.strings.pref_dual_page_split),
        pref = viewModel.preferences.dualPageSplitPaged,
    )

    if (dualPageSplitPaged) {
        CheckboxItem(
            label = stringResource(MR.strings.pref_dual_page_invert),
            pref = viewModel.preferences.dualPageInvertPaged,
        )
    }

    val dualPageRotateToFit by viewModel.preferences.dualPageRotateToFit.collectAsState()
    CheckboxItem(
        label = stringResource(MR.strings.pref_page_rotate),
        pref = viewModel.preferences.dualPageRotateToFit,
    )

    if (dualPageRotateToFit) {
        CheckboxItem(
            label = stringResource(MR.strings.pref_page_rotate_invert),
            pref = viewModel.preferences.dualPageRotateToFitInvert,
        )
    }
}

@Composable
private fun ColumnScope.WebtoonViewerSettings(viewModel: ReaderSettingsViewModel) {
    val numberFormat = remember { NumberFormat.getPercentInstance() }

    HeadingItem(MR.strings.webtoon_viewer)

    val navigationModeWebtoon by viewModel.preferences.navigationModeWebtoon.collectAsState()
    val webtoonNavInverted by viewModel.preferences.webtoonNavInverted.collectAsState()
    TapZonesItems(
        selected = navigationModeWebtoon,
        onSelect = viewModel.preferences.navigationModeWebtoon::set,
        invertMode = webtoonNavInverted,
        onSelectInvertMode = viewModel.preferences.webtoonNavInverted::set,
    )

    val webtoonSidePadding by viewModel.preferences.webtoonSidePadding.collectAsState()
    SliderItem(
        value = webtoonSidePadding,
        valueRange = ReaderPreferences.let { it.WEBTOON_PADDING_MIN..it.WEBTOON_PADDING_MAX },
        label = stringResource(MR.strings.pref_webtoon_side_padding),
        valueString = numberFormat.format(webtoonSidePadding / 100f),
        onChange = {
            viewModel.preferences.webtoonSidePadding.set(it)
        },
        pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    )

    CheckboxItem(
        label = stringResource(MR.strings.pref_crop_borders),
        pref = viewModel.preferences.cropBordersWebtoon,
    )

    val dualPageSplitWebtoon by viewModel.preferences.dualPageSplitWebtoon.collectAsState()
    CheckboxItem(
        label = stringResource(MR.strings.pref_dual_page_split),
        pref = viewModel.preferences.dualPageSplitWebtoon,
    )

    if (dualPageSplitWebtoon) {
        CheckboxItem(
            label = stringResource(MR.strings.pref_dual_page_invert),
            pref = viewModel.preferences.dualPageInvertWebtoon,
        )
    }

    val dualPageRotateToFitWebtoon by viewModel.preferences.dualPageRotateToFitWebtoon.collectAsState()
    CheckboxItem(
        label = stringResource(MR.strings.pref_page_rotate),
        pref = viewModel.preferences.dualPageRotateToFitWebtoon,
    )

    if (dualPageRotateToFitWebtoon) {
        CheckboxItem(
            label = stringResource(MR.strings.pref_page_rotate_invert),
            pref = viewModel.preferences.dualPageRotateToFitInvertWebtoon,
        )
    }

    CheckboxItem(
        label = stringResource(MR.strings.pref_double_tap_zoom),
        pref = viewModel.preferences.webtoonDoubleTapZoomEnabled,
    )
    CheckboxItem(
        label = stringResource(MR.strings.pref_webtoon_disable_zoom_out),
        pref = viewModel.preferences.webtoonDisableZoomOut,
    )
}

@Composable
private fun CurrentScaleCropItem(
    state: PageCropState,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = state.available, onClick = onClick)
            .padding(
                horizontal = SettingsItemsPaddings.Horizontal,
                vertical = SettingsItemsPaddings.Vertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = state.active,
            enabled = state.available,
            onCheckedChange = null,
            modifier = Modifier.padding(end = 24.dp),
        )
        Text(
            text = stringResource(MR.strings.pref_crop_current_zoom),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.weight(1F))
        if (state.available) {
            Text(
                text = stringResource(
                    MR.strings.pref_crop_current_zoom_summary,
                    state.ratio.toString(),
                    state.topPercent,
                    state.bottomPercent,
                    state.leftPercent,
                    state.rightPercent,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun ColumnScope.TapZonesItems(
    selected: Int,
    onSelect: (Int) -> Unit,
    invertMode: ReaderPreferences.TappingInvertMode,
    onSelectInvertMode: (ReaderPreferences.TappingInvertMode) -> Unit,
) {
    SettingsChipRow(MR.strings.pref_viewer_nav) {
        ReaderPreferences.TapZones.mapIndexed { index, it ->
            FilterChip(
                selected = selected == index,
                onClick = { onSelect(index) },
                label = { Text(stringResource(it)) },
            )
        }
    }

    if (selected != 5) {
        SettingsChipRow(MR.strings.pref_read_with_tapping_inverted) {
            ReaderPreferences.TappingInvertMode.entries.map {
                FilterChip(
                    selected = it == invertMode,
                    onClick = { onSelectInvertMode(it) },
                    label = { Text(stringResource(it.titleRes)) },
                )
            }
        }
    }
}
