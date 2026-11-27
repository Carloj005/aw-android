package com.carloj005.awanime.ui.screens.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.carloj005.awanime.data.model.StreamCandidate

enum class PipCommand {
    TOGGLE_PLAY,
    REWIND_10,
    FORWARD_10,
}

internal enum class VerticalGestureZone {
    NONE,
    BRIGHTNESS,
    VOLUME,
}

internal data class PlayerOverlayState(
    val animeTitle: String,
    val episodeNumber: String,
    val episodeCount: Int,
    val currentIndex: Int,
    val hasNextEpisode: Boolean,
    val qualityBadge: String,
    val hasSkipSegments: Boolean,
    val speedLabel: String,
    val resizeLabel: String,
    val isPlaying: Boolean,
    val isSeeking: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val sliderPosition: Float,
)

internal data class PlayerOverlayActions(
    val onBack: () -> Unit,
    val onOpenEpisodeDrawer: () -> Unit,
    val onCycleSpeed: () -> Unit,
    val onCycleResize: () -> Unit,
    val onEnterPip: () -> Unit,
    val onLockScreen: () -> Unit,
    val onPreviousEpisode: () -> Unit,
    val onNextEpisode: () -> Unit,
    val onRewind10: () -> Unit,
    val onForward10: () -> Unit,
    val onTogglePlayPause: () -> Unit,
    val onQuickSkip85: () -> Unit,
    val onSeekPreview: (Float) -> Unit,
    val onSeekCommit: () -> Unit,
)

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

internal fun buildMediaItem(candidate: StreamCandidate): MediaItem {
    val builder = MediaItem.Builder().setUri(Uri.parse(candidate.url))
    if (candidate.isHls) {
        builder.setMimeType(MimeTypes.APPLICATION_M3U8)
    }
    return builder.build()
}

internal fun findHttpStatusCode(error: Throwable?): Int? {
    var curr = error
    while (curr != null) {
        if (curr is HttpDataSource.InvalidResponseCodeException) {
            return curr.responseCode
        }
        curr = curr.cause
    }
    return null
}

@OptIn(UnstableApi::class)
internal class ResilientStreamErrorPolicy : DefaultLoadErrorHandlingPolicy(5) {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val httpCode = findHttpStatusCode(loadErrorInfo.exception)
        if (httpCode != null) {
            return when (httpCode) {
                429, 503, 502, 504, 500, 408 -> {
                    val count = loadErrorInfo.errorCount.coerceAtLeast(1)
                    (count * 700L).coerceAtMost(3_500L)
                }
                403, 404, 410, 416 -> C.TIME_UNSET
                else -> super.getRetryDelayMsFor(loadErrorInfo)
            }
        }
        return super.getRetryDelayMsFor(loadErrorInfo)
    }
}
