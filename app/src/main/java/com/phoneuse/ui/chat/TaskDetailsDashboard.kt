package com.phoneuse.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneuse.app.TaskCheckpoint
import com.phoneuse.app.TaskLogEntry
import com.phoneuse.app.TaskRunStatus
import com.phoneuse.i18n.tr
import com.phoneuse.product.ProductDatabase
import com.phoneuse.ui.theme.IconBtn
import com.phoneuse.ui.theme.MonoStyle
import com.phoneuse.ui.theme.PuIcons
import com.phoneuse.ui.theme.pu
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class StepTimelineItem(
    val stepIndex: Int,
    val title: String,
    val tool: String,
    val durationMs: Long,
    val isSuccess: Boolean,
    val isRunning: Boolean = false,
)

@Suppress("NonObservableLocale")
@Composable
fun TaskDetailsDashboard(
    task: TaskCheckpoint,
    logs: List<TaskLogEntry>,
    steps: List<StepTimelineItem>,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onBack: () -> Unit,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val c = pu
    var devDetailsOpen by remember { mutableStateOf(false) }
    var modelActivityOpen by remember { mutableStateOf(true) }

    val products = remember { ProductDatabase.listProducts() }
    val drafts = remember { ProductDatabase.listDrafts() }

    val elapsedSec = ((task.updatedAtMs - task.createdAtMs).coerceAtLeast(0L) / 1000L)
    val elapsedStr = String.format(Locale.US, "%02d:%02d", elapsedSec / 60, elapsedSec % 60)
    val dateStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(task.createdAtMs))

    Column(
        modifier
            .fillMaxSize()
            .background(c.bg),
    ) {
        // Header Bar
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBtn(PuIcons.Back, tr("Back", "뒤로"), onBack)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    tr("Task Details Dashboard", "작업 상세 대시보드"),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "ID: ${task.taskId.take(12)} · Started: $dateStr ($elapsedStr)",
                    fontSize = 11.5.sp,
                    color = c.textSub,
                    style = MonoStyle,
                )
            }
            StatusBadge(task.status)
        }

        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Task Summary Card
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(c.surface)
                        .border(1.dp, c.line, RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(task.prompt, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.text)

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        InfoPill(tr("Active App", "앱"), task.lastPackage.ifBlank { "System" })
                        InfoPill(tr("Active Role", "역할"), task.activeRole.ifBlank { "Manager" })
                        InfoPill(tr("Active Model", "모델"), task.activeModel.substringAfterLast('/').ifBlank { "Auto" })
                    }

                    // Progress Bar
                    val progressFraction = when {
                        task.status == TaskRunStatus.COMPLETED || task.status == TaskRunStatus.SUCCEEDED -> 1.0f
                        task.status.isTerminal -> 0.0f
                        task.stepCount > 0 -> (task.stepCount.toFloat() / (task.stepCount + 3).toFloat()).coerceIn(0.1f, 0.95f)
                        else -> 0.05f
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                tr("Verified Progress (${task.stepCount} steps)", "검증된 진행률 (${task.stepCount}단계)"),
                                fontSize = 12.sp,
                                color = c.textSub,
                            )
                            Text(
                                "${(progressFraction * 100).toInt()}%",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = c.text,
                                style = MonoStyle,
                            )
                        }
                        LinearProgressIndicator(
                            progress = { progressFraction },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = c.accentText,
                            trackColor = c.sunken,
                        )
                    }

                    // Action Controls
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (task.status == TaskRunStatus.RUNNING) {
                            ControlButton(tr("Pause", "일시정지"), PuIcons.Close, c.accentText, Modifier.weight(1f), onClick = onPause)
                        } else if (task.status == TaskRunStatus.PAUSED) {
                            ControlButton(tr("Resume", "재개"), PuIcons.Check, c.success, Modifier.weight(1f), onClick = onResume)
                        }
                        if (!task.status.isTerminal) {
                            ControlButton(tr("Stop", "중지"), PuIcons.Close, c.danger, Modifier.weight(1f), onClick = onStop)
                        }
                        if (task.status.isTerminal && onRetry != null && (task.status == TaskRunStatus.FAILED || task.status == TaskRunStatus.INTERRUPTED || task.status == TaskRunStatus.CANCELLED)) {
                            ControlButton(tr("Retry Task", "작업 다시 시도"), PuIcons.Check, c.accentText, Modifier.weight(1f), onClick = onRetry)
                        }
                    }
                }
            }

            // Cross-Website Product Listing Stats (Phase 4)
            if (products.isNotEmpty() || drafts.isNotEmpty()) {
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(c.surface)
                            .border(1.dp, c.line, RoundedCornerShape(16.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            tr("Cross-Website Product Listing Stats", "크로스 웹사이트 상품 리스팅 현황"),
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = c.text,
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            CounterPill(tr("Discovered", "발견"), products.size.toString())
                            CounterPill(tr("Extracted", "추출"), products.size.toString())
                            CounterPill(tr("Drafted", "임시저장"), drafts.size.toString())
                            CounterPill(tr("Verified", "검증"), drafts.count { it.status.name.contains("VERIFIED") || it.status.name.contains("SAVED") }.toString())
                        }
                    }
                }
            }

            // Step Timeline
            item {
                Text(
                    tr("Step Timeline", "단계별 실행 타임라인"),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = c.text,
                )
            }

            if (steps.isEmpty()) {
                item {
                    Text(
                        tr("No steps executed yet.", "아직 실행된 단계가 없습니다."),
                        fontSize = 12.5.sp,
                        color = c.textSub,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
            } else {
                items(steps) { step ->
                    StepRow(step)
                }
            }

            // Real-time Activity Feed
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(c.surface)
                        .border(1.dp, c.line, RoundedCornerShape(16.dp))
                        .padding(14.dp),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { devDetailsOpen = !devDetailsOpen },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            tr("Activity Feed & Logs (${logs.size})", "실시간 활동 기록 (${logs.size})"),
                            Modifier.weight(1f),
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = c.text,
                        )
                        Text(if (devDetailsOpen) "▲" else "▼", fontSize = 12.sp, color = c.textSub)
                    }

                    AnimatedVisibility(visible = devDetailsOpen) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (logs.isEmpty()) {
                                Text(tr("No log entries.", "기록 없음."), fontSize = 12.sp, color = c.textSub)
                            } else {
                                logs.takeLast(15).forEach { entry ->
                                    val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(entry.timestampMs))
                                    Text(
                                        "[$time] ${entry.level}: ${entry.message}",
                                        fontSize = 11.5.sp,
                                        color = if (entry.level == "ERROR") c.danger else c.textSub,
                                        style = MonoStyle,
                                        lineHeight = 15.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun StatusBadge(status: TaskRunStatus) {
    val c = pu
    val (color, text) = when (status) {
        TaskRunStatus.RUNNING -> c.accentText to "RUNNING"
        TaskRunStatus.PAUSED -> c.accent to "PAUSED"
        TaskRunStatus.WAITING_CONFIRM, TaskRunStatus.WAITING_FOR_USER -> c.accent to "WAITING"
        TaskRunStatus.RETRYING -> c.accent to "RETRYING"
        TaskRunStatus.BLOCKED -> c.danger to "BLOCKED"
        TaskRunStatus.COMPLETED, TaskRunStatus.SUCCEEDED -> c.success to "SUCCEEDED"
        TaskRunStatus.FAILED -> c.danger to "FAILED"
        TaskRunStatus.CANCELLED, TaskRunStatus.INTERRUPTED -> c.textSub to status.name
        TaskRunStatus.QUEUED, TaskRunStatus.PENDING -> c.textSub to "QUEUED"
    }
    Text(
        text,
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = color,
        style = MonoStyle,
    )
}

@Composable
private fun InfoPill(label: String, value: String) {
    val c = pu
    Column {
        Text(label, fontSize = 11.sp, color = c.textSub)
        Text(value, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = c.text, maxLines = 1)
    }
}

@Composable
private fun CounterPill(label: String, count: String) {
    val c = pu
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(count, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = c.text, style = MonoStyle)
        Text(label, fontSize = 11.sp, color = c.textSub)
    }
}

@Composable
private fun ControlButton(
    label: String,
    icon: Any,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = pu
    Row(
        modifier
            .height(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(c.sunken)
            .border(1.dp, c.line, RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

@Composable
private fun StepRow(step: StepTimelineItem) {
    val c = pu
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(if (step.isSuccess) c.success.copy(alpha = 0.15f) else c.sunken),
            contentAlignment = Alignment.Center,
        ) {
            if (step.isRunning) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp), color = c.accentText)
            } else if (step.isSuccess) {
                Text("✓", fontSize = 13.sp, color = c.success, fontWeight = FontWeight.Bold)
            } else {
                Text("${step.stepIndex}", fontSize = 11.sp, color = c.textSub, style = MonoStyle)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(step.title, fontSize = 13.5.sp, color = c.text, fontWeight = FontWeight.Medium)
            Text("Tool: ${step.tool} · ${step.durationMs}ms", fontSize = 11.5.sp, color = c.textSub, style = MonoStyle)
        }
    }
}
