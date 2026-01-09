package com.coni.hyperisle.ui.screens.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.coni.hyperisle.R
import com.coni.hyperisle.data.AppPreferences
import com.coni.hyperisle.models.NotificationType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TypePriorityScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember { AppPreferences(context) }

    val savedOrder: List<String> by preferences.typePriorityOrderFlow.collectAsState(
        initial = NotificationType.entries.map { it.name }
    )

    val displayList = remember { mutableStateListOf<NotificationType>() }

    LaunchedEffect(savedOrder) {
        val ordered = savedOrder.mapNotNull { name ->
            NotificationType.entries.firstOrNull { it.name == name }
        }
        displayList.clear()
        displayList.addAll(ordered)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.type_priority_title)) },
                navigationIcon = {
                    FilledTonalIconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .fillMaxSize()
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.type_priority_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.type_priority_tip),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            DraggableTypeList(
                items = displayList,
                onMove = { from, to ->
                    displayList.apply { add(to, removeAt(from)) }
                },
                onDragEnd = {
                    scope.launch {
                        preferences.setTypePriorityOrder(displayList.map { it.name })
                    }
                }
            )
        }
    }
}

@Composable
private fun DraggableTypeList(
    items: List<NotificationType>,
    onMove: (Int, Int) -> Unit,
    onDragEnd: () -> Unit
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    val density = androidx.compose.ui.platform.LocalDensity.current
    val scrollThreshold = with(density) { 60.dp.toPx() }
    var scrollVelocity by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(scrollVelocity) {
        if (scrollVelocity != 0f) {
            while (true) {
                listState.scrollBy(scrollVelocity)
                delay(16)
                if (scrollVelocity == 0f) break
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 24.dp)
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        listState.layoutInfo.visibleItemsInfo
                            .firstOrNull { item ->
                                offset.y.toInt() in item.offset..(item.offset + item.size)
                            }
                            ?.let {
                                draggingIndex = it.index
                                dragOffset = 0f
                            }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragOffset += dragAmount.y

                        val viewportHeight = listState.layoutInfo.viewportSize.height
                        val touchY = change.position.y

                        scrollVelocity = if (touchY < scrollThreshold) {
                            if (listState.canScrollBackward && dragAmount.y <= 0) -20f else 0f
                        } else if (touchY > viewportHeight - scrollThreshold) {
                            if (listState.canScrollForward && dragAmount.y >= 0) 20f else 0f
                        } else {
                            0f
                        }

                        val currentIndex = draggingIndex ?: return@detectDragGesturesAfterLongPress
                        val currentItemInfo = listState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.index == currentIndex }

                        if (currentItemInfo != null) {
                            val itemHeight = currentItemInfo.size
                            val threshold = itemHeight * 0.5f

                            if (dragOffset > threshold) {
                                if (currentIndex < items.lastIndex) {
                                    onMove(currentIndex, currentIndex + 1)
                                    draggingIndex = currentIndex + 1
                                    dragOffset -= itemHeight
                                }
                            } else if (dragOffset < -threshold) {
                                if (currentIndex > 0) {
                                    onMove(currentIndex, currentIndex - 1)
                                    draggingIndex = currentIndex - 1
                                    dragOffset += itemHeight
                                }
                            }
                        }
                    },
                    onDragEnd = {
                        draggingIndex = null
                        dragOffset = 0f
                        scrollVelocity = 0f
                        onDragEnd()
                    },
                    onDragCancel = {
                        draggingIndex = null
                        dragOffset = 0f
                        scrollVelocity = 0f
                    }
                )
            },
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(items, key = { _, item -> item.name }) { index, item ->
            val isDragging = index == draggingIndex
            val elevation by animateDpAsState(if (isDragging) 12.dp else 0.dp, label = "elevation")
            val scale by animateFloatAsState(if (isDragging) 1.05f else 1f, label = "scale")
            val alpha = if (isDragging) 0.9f else 1f
            val areButtonsEnabled = draggingIndex == null

            Box(
                modifier = Modifier
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                        translationY = if (isDragging) dragOffset else 0f
                    }
                    .shadow(elevation, RoundedCornerShape(12.dp))
                    .then(if (!isDragging) Modifier.animateItem() else Modifier)
            ) {
                PriorityTypeItem(
                    rank = index + 1,
                    type = item,
                    isFirst = index == 0,
                    isLast = index == items.lastIndex,
                    areButtonsEnabled = areButtonsEnabled,
                    onMoveUp = {
                        if (index > 0) {
                            onMove(index, index - 1)
                            onDragEnd()
                            scope.launch { listState.animateScrollToItem(index - 1) }
                        }
                    },
                    onMoveDown = {
                        if (index < items.lastIndex) {
                            onMove(index, index + 1)
                            onDragEnd()
                            scope.launch { listState.animateScrollToItem(index + 1) }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun PriorityTypeItem(
    rank: Int,
    type: NotificationType,
    isFirst: Boolean,
    isLast: Boolean,
    areButtonsEnabled: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    val moveUpLabel = stringResource(R.string.cd_move_up)
    val moveDownLabel = stringResource(R.string.cd_move_down)
    val handleDesc = stringResource(R.string.cd_drag_handle)

    val buttonTint = if (areButtonsEnabled) MaterialTheme.colorScheme.primary else Color.Transparent

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                stateDescription = "Priority $rank"
                customActions = buildList {
                    if (areButtonsEnabled && !isFirst) {
                        add(CustomAccessibilityAction(label = moveUpLabel) { onMoveUp(); true })
                    }
                    if (areButtonsEnabled && !isLast) {
                        add(CustomAccessibilityAction(label = moveDownLabel) { onMoveDown(); true })
                    }
                }
            },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.DragHandle,
                contentDescription = handleDesc,
                tint = if (areButtonsEnabled) MaterialTheme.colorScheme.onSurfaceVariant else Color.Gray,
                modifier = Modifier.size(48.dp).padding(12.dp)
            )

            Text(
                text = "#$rank",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(32.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = stringResource(type.labelRes),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )

            IconButton(
                onClick = onMoveUp,
                modifier = Modifier.size(32.dp),
                enabled = areButtonsEnabled && !isFirst
            ) {
                if (!isFirst) {
                    Icon(Icons.Default.ArrowUpward, moveUpLabel, tint = buttonTint, modifier = Modifier.size(20.dp))
                }
            }
            IconButton(
                onClick = onMoveDown,
                modifier = Modifier.size(32.dp),
                enabled = areButtonsEnabled && !isLast
            ) {
                if (!isLast) {
                    Icon(Icons.Default.ArrowDownward, moveDownLabel, tint = buttonTint, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.width(8.dp))
        }
    }
}
