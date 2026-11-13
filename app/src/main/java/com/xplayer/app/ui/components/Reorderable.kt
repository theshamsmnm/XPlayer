package com.xplayer.app.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Geometry of one visible lazy item, independent of list/grid. */
data class ReorderItemInfo(val key: Any, val index: Int, val offset: IntOffset, val size: IntSize)

/**
 * Long-press-and-drag reordering for LazyColumn / LazyVerticalGrid.
 *
 * The data list is owned by the caller. While dragging, [onMove] is called with *data indexes* whenever
 * the dragged item passes over another one (the caller reorders its local copy immediately so the UI
 * follows the finger); [onDragEnd] is called once at the end so the caller can persist the final order.
 * Nothing here touches the playback queue or the database.
 */
class ReorderState internal constructor(
    private val visibleItems: () -> List<ReorderItemInfo>,
    private val viewportSize: () -> IntSize,
    private val scrollBy: suspend (Float) -> Float,
    private val scope: CoroutineScope,
    private val onMoveState: () -> ((from: Int, to: Int) -> Unit),
    private val onDragEndState: () -> (() -> Unit),
) {
    var draggingKey by mutableStateOf<Any?>(null)
        private set
    private var startOffset = IntOffset.Zero
    private var dragDelta by mutableStateOf(Offset.Zero)
    private var scrollJob: Job? = null
    private var draggingSize = IntSize.Zero

    val isDragging: Boolean get() = draggingKey != null

    /** Translation to apply to the dragged item so it follows the finger even as the list reorders. */
    fun translationFor(key: Any): Offset {
        if (key != draggingKey) return Offset.Zero
        val current = visibleItems().firstOrNull { it.key == key } ?: return Offset.Zero
        return Offset(
            startOffset.x + dragDelta.x - current.offset.x,
            startOffset.y + dragDelta.y - current.offset.y,
        )
    }

    internal fun start(position: Offset) {
        val hit = visibleItems().firstOrNull { it.contains(position) } ?: return
        draggingKey = hit.key
        startOffset = hit.offset
        draggingSize = hit.size
        dragDelta = Offset.Zero
    }

    internal fun drag(delta: Offset) {
        val key = draggingKey ?: return
        dragDelta += delta
        val items = visibleItems()
        val from = items.firstOrNull { it.key == key } ?: return
        val center = Offset(
            startOffset.x + dragDelta.x + draggingSize.width / 2f,
            startOffset.y + dragDelta.y + draggingSize.height / 2f,
        )
        val target = items.firstOrNull { it.key != key && it.contains(center) }
        if (target != null && target.index != from.index) onMoveState()(from.index, target.index)
        autoScroll(center.y)
    }

    private fun autoScroll(centerY: Float) {
        val h = viewportSize().height.toFloat()
        val zone = h * 0.12f
        val speed = when {
            centerY < zone -> -SCROLL_STEP
            centerY > h - zone -> SCROLL_STEP
            else -> 0f
        }
        if (speed == 0f) { scrollJob?.cancel(); scrollJob = null; return }
        if (scrollJob?.isActive == true) return
        scrollJob = scope.launch {
            while (isActive && draggingKey != null) {
                val consumed = scrollBy(speed)
                // Compensate so the dragged item stays under the finger while the viewport moves.
                dragDelta += Offset(0f, consumed)
                delay(16)
            }
        }
    }

    internal fun end(cancelled: Boolean) {
        scrollJob?.cancel(); scrollJob = null
        val wasDragging = draggingKey != null
        draggingKey = null
        dragDelta = Offset.Zero
        if (wasDragging && !cancelled) onDragEndState()()
    }

    private fun ReorderItemInfo.contains(p: Offset): Boolean =
        p.x >= offset.x && p.x <= offset.x + size.width && p.y >= offset.y && p.y <= offset.y + size.height

    private companion object {
        const val SCROLL_STEP = 24f
    }
}

@Composable
fun rememberReorderableGridState(
    state: LazyGridState,
    onMove: (from: Int, to: Int) -> Unit,
    onDragEnd: () -> Unit,
): ReorderState {
    val scope = rememberCoroutineScope()
    val moveRef by rememberUpdatedState(onMove)
    val endRef by rememberUpdatedState(onDragEnd)
    return remember(state) {
        ReorderState(
            visibleItems = {
                state.layoutInfo.visibleItemsInfo.map { ReorderItemInfo(it.key, it.index, it.offset, it.size) }
            },
            viewportSize = { state.layoutInfo.viewportSize },
            scrollBy = { state.scrollBy(it) },
            scope = scope,
            onMoveState = { moveRef },
            onDragEndState = { endRef },
        )
    }
}

@Composable
fun rememberReorderableListState(
    state: LazyListState,
    onMove: (from: Int, to: Int) -> Unit,
    onDragEnd: () -> Unit,
    /** Number of non-draggable items (headers) before the data items; data indexes exclude them. */
    leadingItems: Int = 0,
): ReorderState {
    val scope = rememberCoroutineScope()
    val moveRef by rememberUpdatedState(onMove)
    val endRef by rememberUpdatedState(onDragEnd)
    return remember(state, leadingItems) {
        ReorderState(
            visibleItems = {
                state.layoutInfo.visibleItemsInfo
                    .filter { it.index >= leadingItems }
                    .map {
                        ReorderItemInfo(it.key, it.index - leadingItems, IntOffset(0, it.offset), IntSize(state.layoutInfo.viewportSize.width, it.size))
                    }
            },
            viewportSize = { state.layoutInfo.viewportSize },
            scrollBy = { state.scrollBy(it) },
            scope = scope,
            onMoveState = { moveRef },
            onDragEndState = { endRef },
        )
    }
}

/** Put on the lazy container. [enabled] = false removes the gesture entirely (locked layout). */
fun Modifier.reorderable(state: ReorderState, enabled: Boolean): Modifier = if (!enabled) this else dragGesture(state)

private fun Modifier.dragGesture(state: ReorderState): Modifier = this.then(
    Modifier.pointerInput(state) {
        detectDragGesturesAfterLongPress(
            onDragStart = { state.start(it) },
            onDrag = { change, amount -> change.consume(); state.drag(amount) },
            onDragEnd = { state.end(cancelled = false) },
            onDragCancel = { state.end(cancelled = true) },
        )
    },
)

/** Apply to each item (inside the lazy item scope) to make it follow the finger while dragged. */
fun Modifier.draggedItem(state: ReorderState, key: Any): Modifier {
    val dragging = state.draggingKey == key
    return if (!dragging) this else this
        .zIndex(1f)
        .graphicsLayer {
            val t = state.translationFor(key)
            translationX = t.x
            translationY = t.y
            scaleX = 1.04f
            scaleY = 1.04f
            alpha = 0.92f
        }
}
