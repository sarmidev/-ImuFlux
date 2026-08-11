package com.sarmidev.imuflux.backoffice.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class BackofficeWidthClass {
    Compact,
    Expanded,
}

@Immutable
data class BackofficeWindowSize(
    val widthClass: BackofficeWidthClass,
    val maxWidth: Dp,
) {
    val isCompact: Boolean get() = widthClass == BackofficeWidthClass.Compact
}

val LocalBackofficeWindowSize = staticCompositionLocalOf {
    BackofficeWindowSize(
        widthClass = BackofficeWidthClass.Expanded,
        maxWidth = 1280.dp,
    )
}

val CompactBreakpoint = 600.dp

fun widthClassFor(maxWidth: Dp): BackofficeWidthClass =
    if (maxWidth < CompactBreakpoint) BackofficeWidthClass.Compact else BackofficeWidthClass.Expanded

fun contentPaddingFor(widthClass: BackofficeWidthClass): Dp =
    if (widthClass == BackofficeWidthClass.Compact) 12.dp else 16.dp

@Composable
fun AdaptiveContent(content: @Composable (BackofficeWindowSize) -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val windowSize = BackofficeWindowSize(
            widthClass = widthClassFor(maxWidth),
            maxWidth = maxWidth,
        )
        CompositionLocalProvider(LocalBackofficeWindowSize provides windowSize) {
            content(windowSize)
        }
    }
}

@Composable
fun currentWindowSize(): BackofficeWindowSize = LocalBackofficeWindowSize.current
