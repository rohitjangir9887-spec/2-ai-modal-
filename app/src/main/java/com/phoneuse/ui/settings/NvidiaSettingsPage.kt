package com.phoneuse.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneuse.app.AppGraph
import com.phoneuse.app.NvidiaOrchestrator
import com.phoneuse.app.NvidiaVoiceGender
import com.phoneuse.app.SettingsStore
import com.phoneuse.i18n.tr
import com.phoneuse.model.AgentRole
import com.phoneuse.model.ModelHealthState
import com.phoneuse.model.NvidiaModelRegistry
import com.phoneuse.model.RoleAssignment
import com.phoneuse.ui.theme.ButtonKind
import com.phoneuse.ui.theme.IconBtn
import com.phoneuse.ui.theme.MonoStyle
import com.phoneuse.ui.theme.PlexMono
import com.phoneuse.ui.theme.PlexSans
import com.phoneuse.ui.theme.PuButton
import com.phoneuse.ui.theme.PuIcons
import com.phoneuse.ui.theme.PuRadio
import com.phoneuse.ui.theme.PuSwitch
import com.phoneuse.ui.theme.Segmented
import com.phoneuse.ui.theme.pu
import kotlinx.coroutines.launch

@Composable
fun NvidiaSettingsPage(
    settings: SettingsStore,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = pu
    val scope = rememberCoroutineScope()

    val autoOrchestration by settings.nvidiaAutoOrchestration.collectAsState(true)
    val visionEnabled by settings.nvidiaVisionEnabled.collectAsState(true)
    val streamingEnabled by settings.nvidiaStreamingEnabled.collectAsState(true)
    val pointerEnabled by settings.nvidiaPointerEnabled.collectAsState(true)
    val pointerLabels by settings.nvidiaPointerLabels.collectAsState(true)
    val continuousVoice by settings.nvidiaContinuousVoice.collectAsState(false)
    val ttsSpeed by settings.nvidiaTtsSpeed.collectAsState(1.0f)
    val voiceGender by settings.nvidiaVoiceGender.collectAsState(NvidiaVoiceGender.NEUTRAL)
    val parakeetUrl by settings.nvidiaParakeetEndpoint.collectAsState("")
    val magpieUrl by settings.nvidiaMagpieEndpoint.collectAsState("")
    val timeoutSec by settings.nvidiaRequestTimeoutSec.collectAsState(45)
    val maxRetries by settings.nvidiaMaxRetries.collectAsState(2)
    val roleOverrides by settings.nvidiaRoleOverrides.collectAsState(emptyMap())

    val healthMap by NvidiaOrchestrator.health.snapshots.collectAsState()
    val catalogVersion by NvidiaOrchestrator.registry.catalogVersion.collectAsState()
    val discovered = remember(catalogVersion) { NvidiaOrchestrator.registry.discoveredModels }
    val checkpoints by AppGraph.checkpoints.recent.collectAsState()
    val logs by AppGraph.taskLogs.entries.collectAsState()

    var editingRole by remember { mutableStateOf<AgentRole?>(null) }
    var parakeetDraft by remember(parakeetUrl) { mutableStateOf(parakeetUrl) }
    var magpieDraft by remember(magpieUrl) { mutableStateOf(magpieUrl) }
    var endpointsSaved by remember { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxSize()
            .background(c.bg)
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom)),
    ) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(start = 6.dp, end = 6.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBtn(PuIcons.Back, tr("Back", "뒤로"), onBack, modifier = Modifier.testTag("nvidia_settings_back"))
            Text(
                tr("NVIDIA Multi-Agent & Voice", "NVIDIA 멀티 에이전트 · 음성"),
                Modifier.weight(1f),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = c.text,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.width(44.dp))
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp),
        ) {
            NvidiaSection(tr("Orchestration and pointer", "오케스트레이션 및 포인터"))
            NvidiaGroup {
                NvidiaSwitchRow(
                    title = tr("Automatic specialist routing", "자동 전문가 모델 라우팅"),
                    sub = tr("Uses Manager, Vision, Action, Coding, and Fast specialists", "관리자·비전·액션·코딩·고속 모델 자동 선택"),
                    checked = autoOrchestration,
                    first = true,
                    tag = "nvidia_auto_orchestration",
                ) { v -> scope.launch { settings.setNvidiaAutoOrchestration(v) } }
                NvidiaSwitchRow(
                    title = tr("Multimodal Vision", "멀티모달 비전"),
                    sub = tr("Routes screenshots only to probed vision-capable models", "검증된 비전 모델에만 스크린샷 전달"),
                    checked = visionEnabled,
                    tag = "nvidia_vision_enabled",
                ) { v -> scope.launch { settings.setNvidiaVisionEnabled(v) } }
                NvidiaSwitchRow(
                    title = tr("Streaming responses", "스트리밍 응답"),
                    sub = tr("SSE live tokens and tool-call accumulation", "실시간 토큰 및 도구 호출 스트리밍"),
                    checked = streamingEnabled,
                    tag = "nvidia_streaming_enabled",
                ) { v -> scope.launch { settings.setNvidiaStreamingEnabled(v) } }
                NvidiaSwitchRow(
                    title = tr("Visible AI Touch Pointer", "AI 터치 포인터 표시"),
                    sub = tr("Shows live cursor and truthful tap/swipe outcome rings", "실시간 포인터와 성공/실패 터치 피드백 표시"),
                    checked = pointerEnabled,
                    tag = "nvidia_pointer_enabled",
                ) { v ->
                    NvidiaOrchestrator.updatePointerConfig(v, pointerLabels)
                    scope.launch { settings.setNvidiaPointerEnabled(v) }
                }
                NvidiaSwitchRow(
                    title = tr("Touch Pointer Action Labels", "포인터 동작 라벨"),
                    sub = tr("Shows short labels beside the AI pointer", "포인터 옆에 간단한 동작 라벨 표시"),
                    checked = pointerLabels,
                    enabled = pointerEnabled,
                    tag = "nvidia_pointer_labels",
                ) { v ->
                    NvidiaOrchestrator.updatePointerConfig(pointerEnabled, v)
                    scope.launch { settings.setNvidiaPointerLabels(v) }
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NvidiaDivider()
                    Text(tr("Request timeout", "요청 타임아웃"), fontSize = 14.sp, color = c.text)
                    Segmented(
                        listOf(15 to "15s", 30 to "30s", 45 to "45s", 90 to "90s"),
                        timeoutSec,
                        { v -> scope.launch { settings.setNvidiaRequestTimeoutSec(v) } },
                    )
                    Text(tr("Automatic retries per model", "모델당 자동 재시도"), fontSize = 14.sp, color = c.text)
                    Segmented(
                        listOf(0 to "0", 1 to "1", 2 to "2", 3 to "3"),
                        maxRetries,
                        { v -> scope.launch { settings.setNvidiaMaxRetries(v) } },
                    )
                }
            }

            NvidiaSection(tr("Specialist roles", "역할별 모델 배정"))
            NvidiaGroup {
                AgentRole.entries.forEachIndexed { idx, role ->
                    val override = roleOverrides[role.name]
                    val current = RoleAssignment(
                        role = role,
                        primaryModel = override?.first ?: NvidiaOrchestrator.registry.assignment(role).primaryModel,
                        backupModel = override?.second ?: NvidiaOrchestrator.registry.assignment(role).backupModel,
                    )
                    if (idx > 0) NvidiaDivider()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable(role = Role.Button) { editingRole = role }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(role.displayName, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.text)
                            Text(
                                "1: ${current.primaryModel}",
                                style = MonoStyle,
                                fontSize = 12.sp,
                                color = c.text2,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "2: ${current.backupModel}",
                                style = MonoStyle,
                                fontSize = 12.sp,
                                color = c.textSub,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("›", fontSize = 14.sp, color = c.textSub)
                    }
                }
                if (roleOverrides.isNotEmpty()) {
                    NvidiaDivider()
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        PuButton(
                            tr("Reset role defaults", "기본값으로 초기화"),
                            {
                                NvidiaOrchestrator.registry.resetRoleAssignments()
                                scope.launch { settings.clearNvidiaRoleModels() }
                            },
                            kind = ButtonKind.OUTLINE,
                            height = 38.dp,
                            radius = 10.dp,
                            fontSize = 13,
                        )
                    }
                }
            }

            NvidiaSection(tr("Live voice and speech", "실시간 음성 및 TTS"))
            NvidiaGroup {
                NvidiaSwitchRow(
                    title = tr("Continuous live voice mode", "연속 음성 대화 모드"),
                    sub = tr("Automatically resumes listening after replies finish", "답변이 끝나면 자동으로 다시 듣기 시작"),
                    checked = continuousVoice,
                    first = true,
                    tag = "nvidia_continuous_voice",
                ) { v -> scope.launch { settings.setNvidiaContinuousVoice(v) } }
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NvidiaDivider()
                    Text(tr("Voice speed", "음성 속도"), fontSize = 14.sp, color = c.text)
                    Segmented(
                        listOf(0.85f to "0.85x", 1.0f to "1.0x", 1.25f to "1.25x"),
                        ttsSpeed,
                        { v -> scope.launch { settings.setNvidiaTtsSpeed(v) } },
                    )
                    Text(tr("Voice style", "음성 톤"), fontSize = 14.sp, color = c.text)
                    Segmented(
                        listOf(
                            NvidiaVoiceGender.NEUTRAL to tr("Neutral", "기본"),
                            NvidiaVoiceGender.FEMALE to tr("Female", "여성"),
                            NvidiaVoiceGender.MALE to tr("Male", "남성"),
                        ),
                        voiceGender,
                        { v -> scope.launch { settings.setNvidiaVoiceGender(v) } },
                    )
                    Text(
                        tr("Optional NVIDIA Parakeet ASR & Magpie TTS URLs (leave empty for Android STT/TTS)", "선택 사항: NVIDIA Parakeet ASR 및 Magpie TTS 주소 (비우면 Android 기본 음성 사용)"),
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = c.textSub,
                    )
                    EndpointField(parakeetDraft, { parakeetDraft = it; endpointsSaved = false }, "Parakeet ASR URL (https://…)")
                    EndpointField(magpieDraft, { magpieDraft = it; endpointsSaved = false }, "Magpie TTS URL (https://…)")
                    PuButton(
                        if (endpointsSaved) tr("Saved", "저장됨") else tr("Save speech endpoints", "음성 엔드포인트 저장"),
                        {
                            scope.launch {
                                settings.setNvidiaSpeechEndpoints(parakeetDraft, magpieDraft)
                                endpointsSaved = true
                            }
                        },
                        Modifier.fillMaxWidth().testTag("nvidia_save_speech_endpoints"),
                        kind = ButtonKind.OUTLINE,
                        height = 40.dp,
                        radius = 12.dp,
                        fontSize = 13,
                    )
                }
            }

            NvidiaSection(tr("Model health and metrics", "모델 상태 및 지표"))
            NvidiaGroup {
                if (healthMap.isEmpty()) {
                    Text(
                        tr("Run 'Test Connection' or 'Discover Models' to populate live status.", "'연결 테스트' 또는 '모델 검색'을 누르면 상태가 표시돼요."),
                        Modifier.padding(16.dp),
                        fontSize = 13.sp,
                        color = c.textSub,
                    )
                } else {
                    healthMap.values.sortedBy { it.modelId }.forEachIndexed { idx, h ->
                        if (idx > 0) NvidiaDivider()
                        val dotColor = when (h.state) {
                            ModelHealthState.READY -> c.success
                            ModelHealthState.DEGRADED -> c.accentText
                            ModelHealthState.UNAVAILABLE, ModelHealthState.AUTH_ERROR -> c.danger
                            ModelHealthState.UNKNOWN -> c.textSub
                        }
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(dotColor))
                                Spacer(Modifier.width(8.dp))
                                Text(h.modelId, Modifier.weight(1f), style = MonoStyle, fontSize = 12.5.sp, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(h.state.name, style = MonoStyle, fontSize = 11.sp, color = dotColor)
                            }
                            val okCalls = (h.totalCalls - h.failedCalls).coerceAtLeast(0)
                            Text(
                                "avg=${h.ewmaLatencyMs}ms · ttft=${h.lastFirstOutputMs ?: 0}ms · ok=$okCalls · err=${h.failedCalls} · tok=${h.inputTokens}/${h.outputTokens}",
                                style = MonoStyle,
                                fontSize = 11.5.sp,
                                color = c.textSub,
                            )
                            h.lastError?.let { err ->
                                Text(err, fontSize = 11.5.sp, color = c.danger, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }

            NvidiaSection(tr("Recent checkpoints and scrubbed logs", "최근 작업 체크포인트 및 로그"))
            NvidiaGroup {
                if (checkpoints.isEmpty() && logs.isEmpty()) {
                    Text(
                        tr("No recorded tasks yet.", "아직 기록된 작업이 없어요."),
                        Modifier.padding(16.dp),
                        fontSize = 13.sp,
                        color = c.textSub,
                    )
                } else {
                    checkpoints.take(8).forEachIndexed { idx, cp ->
                        if (idx > 0) NvidiaDivider()
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(cp.status.name, style = MonoStyle, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = c.accentText)
                                Spacer(Modifier.width(8.dp))
                                Text("steps=${cp.stepCount}", style = MonoStyle, fontSize = 11.5.sp, color = c.textSub)
                                Spacer(Modifier.weight(1f))
                                if (cp.activeModel.isNotBlank()) {
                                    Text(cp.activeModel.substringAfter("nvidia/"), style = MonoStyle, fontSize = 11.sp, color = c.textSub, maxLines = 1)
                                }
                            }
                            Text(cp.prompt, fontSize = 13.sp, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            cp.lastError?.let { err ->
                                Text(err, fontSize = 11.5.sp, color = c.danger, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (logs.isNotEmpty()) {
                        NvidiaDivider()
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(tr("Recent execution events", "최근 실행 로그"), fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = c.textSub)
                            for (entry in logs.takeLast(12).asReversed()) {
                                Text("[${entry.level}] ${entry.message}", style = MonoStyle, fontSize = 11.5.sp, color = c.text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }

    editingRole?.let { role ->
        val candidates = remember(discovered) {
            (NvidiaModelRegistry.DEFAULT_ASSIGNMENTS.values.flatMap { listOf(it.primaryModel, it.backupModel) } + discovered).distinct()
        }
        val current = NvidiaOrchestrator.registry.assignment(role)
        RoleAssignmentDialog(
            role = role,
            initialPrimary = roleOverrides[role.name]?.first ?: current.primaryModel,
            initialBackup = roleOverrides[role.name]?.second ?: current.backupModel,
            candidates = candidates,
            onDismiss = { editingRole = null },
            onSave = { p, b ->
                NvidiaOrchestrator.registry.setRoleAssignment(role, p, b)
                scope.launch { settings.setNvidiaRoleModels(role.name, p, b) }
                editingRole = null
            },
        )
    }
}

@Composable
private fun NvidiaSection(title: String) {
    Text(
        title,
        Modifier.padding(start = 6.dp, end = 6.dp, top = 18.dp, bottom = 6.dp),
        fontSize = 12.5.sp,
        fontWeight = FontWeight.Bold,
        color = pu.textSub,
    )
}

@Composable
private fun NvidiaGroup(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(pu.surface).border(1.dp, pu.line, shape),
        content = content,
    )
}

@Composable
private fun NvidiaDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(pu.lineSoft))
}

@Composable
private fun NvidiaSwitchRow(
    title: String,
    sub: String,
    checked: Boolean,
    first: Boolean = false,
    enabled: Boolean = true,
    tag: String,
    onChange: (Boolean) -> Unit,
) {
    val c = pu
    if (!first) NvidiaDivider()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 8.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = c.text)
            Text(sub, fontSize = 12.5.sp, color = c.textSub)
        }
        PuSwitch(checked, onChange, title, enabled = enabled)
    }
}

@Composable
private fun EndpointField(value: String, onChange: (String) -> Unit, placeholder: String) {
    val c = pu
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(shape)
            .border(1.dp, c.inputBorder, shape)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(placeholder, fontSize = 13.5.sp, color = c.textSub, maxLines = 1)
        BasicTextField(
            value,
            onChange,
            singleLine = true,
            textStyle = TextStyle(fontFamily = PlexMono, fontSize = 13.sp, color = c.text),
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RoleAssignmentDialog(
    role: AgentRole,
    initialPrimary: String,
    initialBackup: String,
    candidates: List<String>,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    val c = pu
    var pickingPrimary by remember { mutableStateOf(true) }
    var primary by remember { mutableStateOf(initialPrimary) }
    var backup by remember { mutableStateOf(initialBackup) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        shape = RoundedCornerShape(22.dp),
        title = { Text(role.displayName, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = c.text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Segmented(
                    listOf(true to tr("Primary", "기본 모델"), false to tr("Backup", "백업 모델")),
                    pickingPrimary,
                    { pickingPrimary = it },
                )
                val selected = if (pickingPrimary) primary else backup
                for (id in candidates) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .selectable(selected = id == selected, role = Role.RadioButton) {
                                if (pickingPrimary) primary = id else backup = id
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PuRadio(id == selected)
                        Spacer(Modifier.width(10.dp))
                        Text(id, style = MonoStyle, fontSize = 12.5.sp, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(primary, backup) }) {
                Text(tr("Save", "저장"), color = c.text, fontFamily = PlexSans, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(tr("Cancel", "취소"), color = c.textSub)
            }
        },
    )
}
