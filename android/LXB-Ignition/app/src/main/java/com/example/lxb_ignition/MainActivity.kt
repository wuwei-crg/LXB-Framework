package com.example.lxb_ignition

import android.Manifest
import android.app.DatePickerDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.EditText
import android.widget.NumberPicker
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.lxb_ignition.model.CoreRuntimeStatus
import com.example.lxb_ignition.model.ScheduleSummary
import com.example.lxb_ignition.model.TaskSummary
import com.example.lxb_ignition.model.TaskRuntimeUiStatus
import com.example.lxb_ignition.model.WirelessBootstrapStatus
import com.example.lxb_ignition.ui.theme.LXBIgnitionTheme
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                this,
                "Notification permission is recommended for task/runtime status.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureNotificationPermissionOnLaunch()
        enableEdgeToEdge()
        setContent {
            LXBIgnitionTheme {
                LXBIgnitionApp()
            }
        }
    }

    private fun ensureNotificationPermissionOnLaunch() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LXBIgnitionApp(viewModel: MainViewModel = viewModel()) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val uiLang by viewModel.uiLang.collectAsState()
    val appUpdateResult by viewModel.appUpdateResult.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val i18n = remember(uiLang) { UiI18n(uiLang) }

    LaunchedEffect(appUpdateResult) {
        if (appUpdateResult.isNotBlank()) {
            snackbarHostState.showSnackbar(appUpdateResult)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.checkAppUpdateOnLaunch()
    }

    CompositionLocalProvider(LocalUiI18n provides i18n) {
        val tabs = listOf("Control", "Tasks", "Config", "Logs")
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("${tr("LXB Ignition")} v${BuildConfig.VERSION_NAME}") },
                    actions = {
                        TextButton(onClick = { viewModel.checkAppUpdateFromGithub() }) {
                            Text(tr("Check update"), fontSize = 12.sp)
                        }
                    }
                )
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            bottomBar = {
                NavigationBar {
                    tabs.forEachIndexed { index, rawLabel ->
                        val label = tr(rawLabel)
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            icon = { Text(label.take(1), fontSize = 18.sp) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        ) { innerPadding ->
            when (selectedTab) {
                0 -> ControlTab(viewModel, Modifier.padding(innerPadding))
                1 -> TasksTab(viewModel, Modifier.padding(innerPadding))
                2 -> ConfigTab(viewModel, Modifier.padding(innerPadding))
                3 -> LogsTab(viewModel, Modifier.padding(innerPadding))
            }
        }
    }
}

// Tab 1: Control

@Composable
fun ControlTab(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val coreRuntime by viewModel.coreRuntimeStatus.collectAsState()
    val wireless by viewModel.wirelessBootstrapStatus.collectAsState()
    val rootAvailable by viewModel.rootAvailable.collectAsState()
    val rootDetail by viewModel.rootDetail.collectAsState()
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ProcessRuntimeCard(
            status = coreRuntime
        )

        RootDirectStartCard(
            rootAvailable = rootAvailable,
            rootDetail = rootDetail,
            onStartRoot = { viewModel.startServerWithRootDirect() }
        )

        AdbStartCard(
            onStartAdb = { viewModel.startServerWithNative() }
        )

        UnifiedStopCard(
            onStop = { viewModel.stopServerProcess() },
            onRefreshState = { viewModel.refreshCoreRuntimeStatusNow() }
        )

        WirelessBootstrapCard(
            status = wireless,
            onStartGuide = { viewModel.startWirelessBootstrapGuide() }
        )
    }
}

@Composable
fun TaskSessionCard(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val requirement by viewModel.requirement.collectAsState()
    val chatMessages by viewModel.chatMessages.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(chatMessages.size) {
        if (chatMessages.isNotEmpty()) {
            listState.animateScrollToItem(chatMessages.size - 1)
        }
    }

    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(tr("Task Session"), style = MaterialTheme.typography.titleSmall)
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(chatMessages) { msg ->
                    ChatBubble(message = msg)
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = requirement,
                    onValueChange = { viewModel.requirement.value = it },
                    label = { Text(tr("Describe what you want to do")) },
                    modifier = Modifier.weight(1f),
                    maxLines = 3
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Button(
                        onClick = { viewModel.runRequirementOnDevice() },
                        modifier = Modifier.height(40.dp)
                    ) {
                        Text(tr("Run"))
                    }
                    OutlinedButton(
                        onClick = { viewModel.cancelCurrentTaskOnDevice() },
                        modifier = Modifier.height(32.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 8.dp,
                            vertical = 4.dp
                        )
                    ) {
                        Text(tr("Stop"), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun TaskRuntimeStatusCard(status: TaskRuntimeUiStatus, modifier: Modifier = Modifier) {
    val bgColor = if (status.running) Color(0xFF4CAF50) else Color(0xFF9E9E9E)
    val stateText = if (status.running) tr("RUNNING") else tr("IDLE")
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    tr("Task Runtime"),
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White
                )
                Text(stateText, color = Color.White, fontSize = 12.sp)
            }
            val taskText = if (status.taskId.isNotEmpty()) {
                "${tr("Current task")}: ${status.taskId.take(8)}..."
            } else {
                "${tr("Current task")}: -"
            }
            Text(
                text = taskText,
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.92f)
            )
            Text(
                text = "${tr("Phase")}: ${status.phase}",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.92f)
            )
            val detail = if (status.running) status.detail else tr("No task is running.")
            Text(
                text = detail,
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.88f)
            )
        }
    }
}

private class UiI18n(private val lang: String) {
    fun tr(text: String): String {
        if (lang != "zh") return text
        return ZhMap[text] ?: text
    }
}

private val LocalUiI18n = staticCompositionLocalOf { UiI18n("en") }

@Composable
private fun tr(text: String): String = LocalUiI18n.current.tr(text)

private val ZhMap = mapOf(
    "LXB Ignition" to "LXB 点火",
    "Update" to "更新",
    "Check update" to "检查更新",
    "Control" to "控制",
    "Tasks" to "任务",
    "Config" to "配置",
    "Logs" to "日志",
    "Task Session" to "任务会话",
    "Describe what you want to do" to "描述你想执行的任务",
    "Run" to "运行",
    "Stop" to "停止",
    "Task Manager" to "任务管理",
    "Task Runtime" to "任务运行状态",
    "RUNNING" to "运行中",
    "IDLE" to "空闲",
    "Current task" to "当前任务",
    "Phase" to "阶段",
    "No task is running." to "当前没有任务在运行。",
    "Refresh All" to "全部刷新",
    "No runs yet. Submit a task from Task Session or wait for schedules." to "暂无执行记录。可在任务会话中提交任务，或等待定时任务执行。",
    "Schedules" to "定时任务",
    "Recent Runs" to "最近执行",
    "Back" to "返回",
    "New" to "新建",
    "Refresh" to "刷新",
    "Task description (required)" to "任务描述（必填）",
    "Pick Date" to "选择日期",
    "Pick Time" to "选择时间",
    "Name (optional)" to "名称（可选）",
    "Package (optional)" to "包名（可选）",
    "User playbook (optional)" to "用户操作文档（可选）",
    "Record task screen" to "录制任务屏幕",
    "Save task recording to Movies/lxb." to "将任务录屏保存到 Movies/lxb。",
    "Save" to "保存",
    "Submit" to "提交",
    "Cancel" to "取消",
    "Delete" to "删除",
    "Select Time" to "选择时间",
    "Hour" to "小时",
    "Minute" to "分钟",
    "OK" to "确定",
    "Core Connected" to "Core 已连接",
    "Core Disconnected" to "Core 未连接",
    "Start Native" to "原生启动",
    "ADB start" to "ADB 启动",
    "Use Wireless ADB bootstrap path. Pair once, then tap start." to "使用无线 ADB 引导路径，先完成配对，再点击启动。",
    "Start via ADB" to "通过 ADB 启动",
    "Root direct start" to "Root 直启",
    "Root start" to "Root 启动",
    "For rooted phones: start lxb-core directly via su. No Wireless ADB guide required." to "Root 手机可直接通过 su 启动 lxb-core，无需走无线 ADB 引导。",
    "Requires root permission grant in superuser manager." to "需要在超级用户管理器中授权 Root 权限。",
    "Start via Root" to "通过 Root 启动",
    "Root available" to "Root 可用",
    "Root unavailable" to "Root 不可用",
    "Check Root" to "检测 Root",
    "Unified stop" to "统一停止",
    "Stop core process regardless of start mode." to "无论通过哪种方式启动，都用这里统一停止 Core 进程。",
    "Stop Core Process" to "停止 Core 进程",
    "Refresh Core State" to "刷新 Core 状态",
    "Wireless bootstrap" to "无线 ADB 引导",
    "Wireless ADB bootstrap path (native). Start guide, then submit pairing code from notification." to "无线 ADB 引导路径（原生）。点击开始引导后，在通知栏仅输入配对码。",
    "Wireless ADB setup steps:" to "无线 ADB 引导步骤：",
    "1. Ensure Developer options is enabled. If unsure, search online for your phone model." to "1. 确保已打开手机开发者模式；如果不知道如何打开，请根据手机型号上网搜索。",
    "2. Tap \"Open Developer Options (Start Guide)\"." to "2. 点击“打开开发者选项（开始引导）”。",
    "3. In Developer options, find Wireless debugging and turn it on." to "3. 在开发者选项中找到“无线调试”并打开。",
    "4. Tap \"Pair device with pairing code\"." to "4. 点击“使用配对码配对设备”。",
    "5. After the pairing code appears, enter it in this app's notification input." to "5. 看到配对码后，在本应用通知栏输入框中填入配对码。",
    "6. Return to the app and check whether core starts automatically. If not, tap \"Start via ADB\" manually." to "6. 回到应用，观察是否自动启动成功；如果没有，手动点击“通过 ADB 启动”。",
    "Open Developer Options (Start Guide)" to "打开开发者选项（开始引导）",
    "State" to "状态",
    "Start" to "启动",
    "Config center" to "配置中心",
    "Choose a category to configure. Each section opens a dedicated settings page." to "选择一个配置类别，每个类别会进入独立配置页面。",
    "Language" to "语言",
    "Language for app UI text." to "应用界面语言设置。",
    "English" to "英文",
    "Chinese" to "中文",
    "Device core server" to "设备端核心服务",
    "Control mode config" to "操控方式配置",
    "Core runtime & input" to "Core 运行与输入通道",
    "Core startup & input compatibility (Important)" to "核心启动与输入兼容（重要）",
    "If Start Native fails or taps/swipes do not work, open this first." to "如果原生启动失败，或点击/滑动不生效，先看这里。",
    "Open core service settings" to "打开核心服务配置",
    "If taps/swipes are not working, adjust compatibility mode here." to "如果点击/滑动不生效，在这里调整兼容模式。",
    "Open control mode settings" to "打开操控方式配置",
    "TCP port and related options for lxb-core running on this device." to "配置设备端 lxb-core 的 TCP 端口与相关选项。",
    "LLM config (device-side)" to "设备端 LLM 配置",
    "Base URL, API key and model for device-side LLM/VLM calls." to "配置设备端 LLM/VLM 调用的 Base URL、API Key 与模型。",
    "Unlock & lock policy" to "解锁与锁屏策略",
    "Auto unlock before route, auto lock after task, and lockscreen credentials." to "配置路由前自动解锁、任务后自动锁屏与锁屏凭据。",
    "Map sync & source" to "地图同步与来源",
    "Sync stable maps, pull map by identifier, and choose runtime source lane." to "同步稳定地图、按标识拉取地图，并选择运行时地图来源。",
    "App update" to "应用更新",
    "Check and open latest GitHub release APK." to "检查并打开 GitHub 最新 Release APK。",
    "Current app version" to "当前应用版本",
    "Check latest release" to "检查最新版本",
    "Open releases" to "打开 Releases",
    "Use OpenAI Chat Completions compatible endpoint. URL should end with /v1 or /v1/chat/completions." to "使用 OpenAI Chat Completions 兼容接口。URL 建议以 /v1 或 /v1/chat/completions 结尾。",
    "lxb-core server" to "lxb-core 服务",
    "TCP port" to "TCP 端口",
    "TCP port listened by lxb-core on device (default 12345)" to "设备端 lxb-core 监听的 TCP 端口（默认 12345）",
    "Controls native start endpoint and click/swipe compatibility path." to "配置原生启动端点与点击/滑动兼容路径。",
    "Configure touch injection mode and compatibility." to "配置触摸注入模式与兼容策略。",
    "Touch mode profile" to "触摸策略档位",
    "Choose preferred injection pipeline for tap/swipe/long press." to "为点击/滑动/长按选择优先注入链路。",
    "Compatibility mode (Shell first)" to "兼容模式（Shell 优先）",
    "Precision mode (UiAutomation first)" to "精细模式（UiAutomation 优先）",
    "Task-time Do Not Disturb" to "任务期间免打扰",
    "Policy applied when a task starts." to "任务启动时应用此策略。",
    "Keep current DND state" to "不调整免打扰（保持当前状态）",
    "Set DND to OFF (allow notifications)" to "设置任务期 DND=OFF（允许提醒）",
    "Set DND to NONE (silence all)" to "设置任务期 DND=NONE（全部静音）",
    "Shell" to "Shell",
    "UIAutomator" to "UIAutomator",
    "Touch input mode" to "触摸注入模式",
    "How taps/swipes are injected to the device." to "控制点击/滑动如何注入到设备。",
    "Shell input first" to "Shell 优先",
    "UiAutomation first" to "UiAutomation 优先",
    "Apply to core" to "应用到 Core",
    "API Base URL" to "API Base URL",
    "API Key" to "API Key",
    "Model" to "模型",
    "Test LLM & sync to device" to "测试 LLM 并同步到设备",
    "Save only" to "仅保存",
    "Save all config only" to "仅保存（保存所有配置）",
    "Auto unlock before route" to "路由前自动解锁",
    "Check screen state and unlock before app launch/routing." to "在应用启动/路由前检查屏幕状态并执行解锁。",
    "Auto lock after task" to "任务后自动锁屏",
    "Lock screen when task ends if the FSM unlocked it." to "若由 FSM 解锁，任务结束后自动锁屏。",
    "Unlock PIN / password" to "解锁 PIN / 密码",
    "Used only when swipe unlock is not enough." to "仅在上滑解锁不足时使用。",
    "Sync to device" to "同步到设备",
    "Map sync & lane control" to "地图同步与轨道控制",
    "Map repo raw base URL" to "Map 仓库 Raw Base URL",
    "Use map routing" to "使用 Map 路由",
    "ON: route with map when available." to "开启：有地图时优先按地图路由。",
    "OFF: force no-map mode (launch then vision-only)." to "关闭：强制无地图模式（仅启动后视觉执行）。",
    "Map source" to "地图来源",
    "Choose which lane is applied to runtime routing map." to "选择应用到运行时路由地图的来源。",
    "Stable" to "稳定",
    "Candidate" to "测试",
    "Burn" to "烧录",
    "Package name" to "包名",
    "Used for pull-by-identifier and active status." to "用于按标识拉取与查看当前生效状态。",
    "Map ID" to "Map ID",
    "Required for stable/candidate pull by identifier." to "stable/candidate 按标识拉取时必填。",
    "Sync Stable All" to "同步全部 Stable",
    "Pull Stable by ID" to "按 ID 拉取 Stable",
    "Pull Candidate by ID" to "按 ID 拉取 Candidate",
    "Show active" to "查看当前生效"
)

// Tab 2: Tasks

@Composable
fun TasksTab(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val tasks by viewModel.taskList.collectAsState()
    val schedules by viewModel.scheduleList.collectAsState()
    val taskRuntime by viewModel.taskRuntimeUiStatus.collectAsState()
    val scheduleName by viewModel.scheduleName.collectAsState()
    val scheduleTask by viewModel.scheduleTask.collectAsState()
    val scheduleStartAtMs by viewModel.scheduleStartAtMs.collectAsState()
    val scheduleRepeatMode by viewModel.scheduleRepeatMode.collectAsState()
    val scheduleRepeatWeekdays by viewModel.scheduleRepeatWeekdays.collectAsState()
    val schedulePackage by viewModel.schedulePackage.collectAsState()
    val schedulePlaybook by viewModel.schedulePlaybook.collectAsState()
    val scheduleRecordEnabled by viewModel.scheduleRecordEnabled.collectAsState()
    var page by rememberSaveable { mutableIntStateOf(0) }
    var editingScheduleId by rememberSaveable { mutableStateOf("") }
    var selectedTask by remember { mutableStateOf<TaskSummary?>(null) }

    LaunchedEffect(Unit) {
        viewModel.refreshScheduleListOnDevice()
        viewModel.refreshTaskListOnDevice()
    }

    when (page) {
        0 -> {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(tr("Task Manager"), style = MaterialTheme.typography.titleSmall)
                    OutlinedButton(
                        onClick = {
                            viewModel.refreshScheduleListOnDevice()
                            viewModel.refreshTaskListOnDevice()
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 8.dp,
                            vertical = 4.dp
                        ),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(tr("Refresh All"), fontSize = 12.sp)
                    }
                }

                TaskRuntimeStatusCard(status = taskRuntime)
                TaskSessionCard(viewModel = viewModel)

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    onClick = { page = 1 }
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(tr("Schedules"), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "Manage scheduled tasks and create new ones. (${schedules.size})",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                        )
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    onClick = { page = 2 }
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(tr("Recent Runs"), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "View recent execution records. (${tasks.size})",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                        )
                    }
                }
            }
        }

        1 -> {
            val scheduleListState = rememberLazyListState()
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { page = 0 },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(tr("Back"), fontSize = 12.sp)
                    }
                    Text(
                        text = "Schedules",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = 6.dp)
                    )
                    OutlinedButton(
                        onClick = {
                            editingScheduleId = ""
                            viewModel.resetScheduleForm()
                            page = 3
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(tr("New"), fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = { viewModel.refreshScheduleListOnDevice() },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(tr("Refresh"), fontSize = 12.sp)
                    }
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    if (schedules.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp)
                        ) {
                            Text(
                                text = "No schedules yet.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    } else {
                        LazyColumn(
                            state = scheduleListState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(schedules, key = { it.scheduleId }) { schedule ->
                                ScheduleRow(
                                    schedule = schedule,
                                    onEdit = {
                                        editingScheduleId = schedule.scheduleId
                                        viewModel.loadScheduleForm(schedule)
                                        page = 3
                                    },
                                    onDelete = { viewModel.removeScheduleOnDevice(schedule.scheduleId) }
                                )
                            }
                        }
                    }
                }
            }
        }

        2 -> {
            val taskListState = rememberLazyListState()
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { page = 0 },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(tr("Back"), fontSize = 12.sp)
                    }
                    Text(
                        text = "Recent Runs",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = 6.dp)
                    )
                    OutlinedButton(
                        onClick = { viewModel.refreshTaskListOnDevice() },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(tr("Refresh"), fontSize = 12.sp)
                    }
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    if (tasks.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp)
                        ) {
                            Text(
                                text = "No runs yet. Submit a task from Task Session or wait for schedules.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    } else {
                        LazyColumn(
                            state = taskListState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(tasks, key = { it.taskId }) { task ->
                                TaskRow(task = task, onClick = { selectedTask = task })
                            }
                        }
                    }
                }
            }
        }

        else -> {
            val context = LocalContext.current
            val selectedRunAt = scheduleStartAtMs.toLongOrNull()?.takeIf { it > 0L }
                ?: (System.currentTimeMillis() + 5 * 60_000L)
            var showTimeWheel by rememberSaveable { mutableStateOf(false) }
            val isEditing = editingScheduleId.isNotEmpty()
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            page = 1
                            editingScheduleId = ""
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(tr("Back"), fontSize = 12.sp)
                    }
                    Text(
                        text = if (isEditing) "Edit Schedule" else "Create Schedule",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = 6.dp)
                    )
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = scheduleTask,
                            onValueChange = { viewModel.scheduleTask.value = it },
                            label = { Text(tr("Task description (required)")) },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 3
                        )
                        Text(
                            text = "Run time: ${formatTsFull(selectedRunAt)}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val cal = Calendar.getInstance().apply { timeInMillis = selectedRunAt }
                                    DatePickerDialog(
                                        context,
                                        { _, y, m, d ->
                                            val next = Calendar.getInstance().apply {
                                                timeInMillis = selectedRunAt
                                                set(Calendar.YEAR, y)
                                                set(Calendar.MONTH, m)
                                                set(Calendar.DAY_OF_MONTH, d)
                                            }
                                            viewModel.scheduleStartAtMs.value = next.timeInMillis.toString()
                                        },
                                        cal.get(Calendar.YEAR),
                                        cal.get(Calendar.MONTH),
                                        cal.get(Calendar.DAY_OF_MONTH)
                                    ).show()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(tr("Pick Date"))
                            }
                            OutlinedButton(
                                onClick = {
                                    showTimeWheel = true
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(tr("Pick Time"))
                            }
                        }
                        Text(
                            text = "Repeat",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            RepeatModeButton(
                                text = "Once",
                                selected = scheduleRepeatMode == MainViewModel.REPEAT_ONCE,
                                onClick = { viewModel.scheduleRepeatMode.value = MainViewModel.REPEAT_ONCE },
                                modifier = Modifier.weight(1f)
                            )
                            RepeatModeButton(
                                text = "Daily",
                                selected = scheduleRepeatMode == MainViewModel.REPEAT_DAILY,
                                onClick = { viewModel.scheduleRepeatMode.value = MainViewModel.REPEAT_DAILY },
                                modifier = Modifier.weight(1f)
                            )
                            RepeatModeButton(
                                text = "Weekly",
                                selected = scheduleRepeatMode == MainViewModel.REPEAT_WEEKLY,
                                onClick = { viewModel.scheduleRepeatMode.value = MainViewModel.REPEAT_WEEKLY },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (scheduleRepeatMode == MainViewModel.REPEAT_WEEKLY) {
                            val labels = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                for (i in 0 until 4) {
                                    val selected = ((scheduleRepeatWeekdays shr i) and 1) == 1
                                    WeekdayButton(
                                        text = labels[i],
                                        selected = selected,
                                        onClick = {
                                            viewModel.scheduleRepeatWeekdays.value = scheduleRepeatWeekdays xor (1 shl i)
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                for (i in 4..6) {
                                    val selected = ((scheduleRepeatWeekdays shr i) and 1) == 1
                                    WeekdayButton(
                                        text = labels[i],
                                        selected = selected,
                                        onClick = {
                                            viewModel.scheduleRepeatWeekdays.value = scheduleRepeatWeekdays xor (1 shl i)
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Spacer(modifier = Modifier.weight(1f))
                            }
                            Text(
                                text = "Selected days: ${formatWeekdayMask(scheduleRepeatWeekdays)}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                            )
                        }
                        OutlinedTextField(
                            value = scheduleName,
                            onValueChange = { viewModel.scheduleName.value = it },
                            label = { Text(tr("Name (optional)")) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = schedulePackage,
                            onValueChange = { viewModel.schedulePackage.value = it },
                            label = { Text(tr("Package (optional)")) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = schedulePlaybook,
                            onValueChange = { viewModel.schedulePlaybook.value = it },
                            label = { Text(tr("User playbook (optional)")) },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 4
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = tr("Record task screen"),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = tr("Save task recording to Movies/lxb."),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                            Switch(
                                checked = scheduleRecordEnabled,
                                onCheckedChange = { viewModel.scheduleRecordEnabled.value = it }
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (isEditing) {
                                        viewModel.updateScheduleOnDevice(editingScheduleId)
                                    } else {
                                        viewModel.addScheduleOnDevice()
                                    }
                                    page = 1
                                    editingScheduleId = ""
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(tr(if (isEditing) "Save" else "Submit"))
                            }
                            OutlinedButton(
                                onClick = {
                                    page = 1
                                    editingScheduleId = ""
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(tr("Cancel"))
                            }
                        }
                    }
                }
            }
            if (showTimeWheel) {
                val cal = Calendar.getInstance().apply { timeInMillis = selectedRunAt }
                WheelTimePickerDialog(
                    initialHour = cal.get(Calendar.HOUR_OF_DAY),
                    initialMinute = cal.get(Calendar.MINUTE),
                    onDismiss = { showTimeWheel = false },
                    onConfirm = { hour, minute ->
                        val next = Calendar.getInstance().apply {
                            timeInMillis = selectedRunAt
                            set(Calendar.HOUR_OF_DAY, hour)
                            set(Calendar.MINUTE, minute)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        viewModel.scheduleStartAtMs.value = next.timeInMillis.toString()
                        showTimeWheel = false
                    }
                )
            }
        }
    }

    val detail = selectedTask
    if (detail != null) {
        AlertDialog(
            onDismissRequest = { selectedTask = null },
            title = { Text("Task Details") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (detail.taskSummary.isNotBlank()) {
                        Text(
                            text = detail.taskSummary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        Text(
                            text = "No task summary available yet.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Text("id=${detail.taskId}", fontSize = 11.sp)
                    Text(
                        text = "state=${detail.state}" +
                                if (detail.finalState.isNotBlank()) " / ${detail.finalState}" else "",
                        fontSize = 11.sp
                    )
                    if (detail.reason.isNotBlank()) {
                        Text("reason=${detail.reason}", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                    }
                    if (detail.userTask.isNotBlank()) {
                        Text("task=${detail.userTask}", fontSize = 11.sp)
                    }
                    if (detail.packageName.isNotBlank()) {
                        Text("package=${detail.packageName}", fontSize = 11.sp)
                    }
                    if (detail.targetPage.isNotBlank()) {
                        Text("target_page=${detail.targetPage}", fontSize = 11.sp)
                    }
                    if (detail.source.isNotBlank()) {
                        Text("source=${detail.source}", fontSize = 11.sp)
                    }
                    if (detail.scheduleId.isNotBlank()) {
                        Text("schedule_id=${detail.scheduleId}", fontSize = 11.sp)
                    }
                    Text("memory_applied=${detail.memoryApplied}", fontSize = 11.sp)
                    Text("record_enabled=${detail.recordEnabled}", fontSize = 11.sp)
                    if (detail.recordFile.isNotBlank()) {
                        Text("record_file=${detail.recordFile}", fontSize = 11.sp)
                    }
                    if (detail.createdAt > 0L) {
                        Text("created_at=${formatTsFull(detail.createdAt)}", fontSize = 11.sp)
                    }
                    if (detail.finishedAt > 0L) {
                        Text("finished_at=${formatTsFull(detail.finishedAt)}", fontSize = 11.sp)
                    }
                }
            },
            confirmButton = {
                OutlinedButton(onClick = { selectedTask = null }) {
                    Text(tr("Close"))
                }
            }
        )
    }
}

@Composable
fun ScheduleRow(
    schedule: ScheduleSummary,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceVariant),
        onClick = onEdit
    ) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = if (schedule.name.isNotEmpty()) schedule.name else schedule.userTask,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "run_at=${formatTsFull(schedule.runAtMs)}, repeat=${formatRepeat(schedule.repeatMode, schedule.repeatWeekdays)}, next=${formatTsFull(schedule.nextRunAt)}, triggered=${schedule.triggerCount}, record=${if (schedule.recordEnabled) "on" else "off"}",
                fontSize = 11.sp,
                color = scheme.onSurface.copy(alpha = 0.75f)
            )
            if (schedule.packageName.isNotEmpty()) {
                Text(
                    text = "package=${schedule.packageName}",
                    fontSize = 11.sp,
                    color = scheme.onSurface.copy(alpha = 0.75f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "id=${schedule.scheduleId.take(8)}...",
                    fontSize = 10.sp,
                    color = scheme.onSurface.copy(alpha = 0.6f)
                )
                OutlinedButton(
                    onClick = onDelete,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Text(tr("Delete"), fontSize = 11.sp, color = scheme.error)
                }
            }
        }
    }
}

@Composable
fun TaskRow(task: TaskSummary, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val bgColor = when (task.state) {
        "COMPLETED" -> scheme.surfaceVariant
        "CANCELLED" -> scheme.surfaceVariant
        "FAILED" -> scheme.errorContainer
        "RUNNING" -> scheme.surfaceVariant
        else -> scheme.surface
    }
    val primaryTextColor = if (task.state == "FAILED") scheme.onErrorContainer else scheme.onSurface
    Card(
        modifier = Modifier
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = if (task.userTask.isNotEmpty()) task.userTask else "(no task description)",
                style = MaterialTheme.typography.bodyMedium,
                color = primaryTextColor
            )
            Spacer(modifier = Modifier.height(2.dp))
            val stateLabel = buildString {
                append(task.state)
                if (task.finalState.isNotEmpty()) {
                    append(" / ").append(task.finalState)
                }
                if (task.packageName.isNotEmpty()) {
                    append(" / ").append(task.packageName)
                }
                if (task.source.isNotEmpty()) {
                    append(" / ").append(task.source)
                }
                if (task.memoryApplied) {
                    append(" / memory")
                }
            }
            Text(
                text = stateLabel,
                fontSize = 11.sp,
                color = scheme.onSurface.copy(alpha = 0.75f)
            )
            if (task.reason.isNotEmpty()) {
                Text(
                    text = "reason=${task.reason}",
                    fontSize = 10.sp,
                    color = scheme.error
                )
            }
        }
    }
}

@Composable
private fun WheelTimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit
) {
    var hour by rememberSaveable { mutableIntStateOf(initialHour.coerceIn(0, 23)) }
    var minute by rememberSaveable { mutableIntStateOf(initialMinute.coerceIn(0, 59)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Select Time")) },
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(tr("Hour"), fontSize = 12.sp)
                    AndroidView(
                        factory = { ctx ->
                            NumberPicker(ctx).apply {
                                minValue = 0
                                maxValue = 23
                                wrapSelectorWheel = true
                                descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
                                setFormatter { String.format(Locale.getDefault(), "%02d", it) }
                                styleWheelNumberPicker(this)
                            }
                        },
                        update = { picker ->
                            styleWheelNumberPicker(picker)
                            if (picker.value != hour) {
                                picker.value = hour
                            }
                            picker.setOnValueChangedListener { _, _, newVal ->
                                hour = newVal
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(tr("Minute"), fontSize = 12.sp)
                    AndroidView(
                        factory = { ctx ->
                            NumberPicker(ctx).apply {
                                minValue = 0
                                maxValue = 59
                                wrapSelectorWheel = true
                                descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
                                setFormatter { String.format(Locale.getDefault(), "%02d", it) }
                                styleWheelNumberPicker(this)
                            }
                        },
                        update = { picker ->
                            styleWheelNumberPicker(picker)
                            if (picker.value != minute) {
                                picker.value = minute
                            }
                            picker.setOnValueChangedListener { _, _, newVal ->
                                minute = newVal
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(hour, minute) }) {
                Text(tr("OK"))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(tr("Cancel"))
            }
        }
    )
}

private fun styleWheelNumberPicker(picker: NumberPicker) {
    runCatching {
        picker.setBackgroundColor(android.graphics.Color.WHITE)
        for (i in 0 until picker.childCount) {
            val child = picker.getChildAt(i)
            if (child is EditText) {
                child.setTextColor(android.graphics.Color.BLACK)
                child.textSize = 20f
            }
        }
    }
}

@Composable
private fun RepeatModeButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) {
            Text(text)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) {
            Text(text)
        }
    }
}

@Composable
private fun WeekdayButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) {
            Text(text, fontSize = 11.sp)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) {
            Text(text, fontSize = 11.sp)
        }
    }
}

private fun formatTsFull(ms: Long): String {
    if (ms <= 0L) return "-"
    return runCatching {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        sdf.format(Date(ms))
    }.getOrElse { "-" }
}

private fun formatWeekdayMask(mask: Int): String {
    if ((mask and 0x7F) == 0) return "-"
    val labels = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val selected = mutableListOf<String>()
    for (i in 0..6) {
        if (((mask shr i) and 1) == 1) {
            selected.add(labels[i])
        }
    }
    return if (selected.isEmpty()) "-" else selected.joinToString(", ")
}

private fun formatRepeat(modeRaw: String, weekdays: Int): String {
    return when (modeRaw.lowercase(Locale.getDefault())) {
        MainViewModel.REPEAT_DAILY -> "daily"
        MainViewModel.REPEAT_WEEKLY -> "weekly(${formatWeekdayMask(weekdays)})"
        else -> "once"
    }
}

@Composable
fun ChatBubble(message: MainViewModel.ChatMessage) {
    val isUser = message.role == MainViewModel.ChatRole.USER
    val bgColor: Color
    val textColor: Color
    if (isUser) {
        bgColor = Color(0xFF1976D2)
        textColor = Color.White
    } else {
        val t = message.text
        bgColor = when {
            t.startsWith("Task submitted") || t.contains("finished successfully") -> Color(0xFFE8F5E9)
            t.startsWith("Task id:") -> Color(0xFFE3F2FD)
            t.contains("failed") || t.contains("error", ignoreCase = true) -> Color(0xFFFFEBEE)
            t.startsWith("Cancel requested") || t.contains("cancelled") -> Color(0xFFFFF3E0)
            else -> Color(0xFFE0E0E0)
        }
        textColor = if (bgColor == Color(0xFFE0E0E0)) Color.Black else Color(0xFF212121)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = bgColor
            )
        ) {
            Text(
                text = message.text,
                color = textColor,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
fun ProcessRuntimeCard(status: CoreRuntimeStatus) {
    val (bgColor, label) = if (status.ready) {
        Color(0xFF4CAF50) to tr("Core Connected")
    } else {
        Color(0xFF9E9E9E) to tr("Core Disconnected")
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, color = Color.White, style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(2.dp))
            Text(status.detail, color = Color.White.copy(alpha = 0.92f), fontSize = 12.sp)
        }
    }
}

@Composable
fun AdbStartCard(
    onStartAdb: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(tr("ADB start"), style = MaterialTheme.typography.titleSmall)
            Text(
                tr("Use Wireless ADB bootstrap path. Pair once, then tap start."),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                fontSize = 12.sp
            )
            OutlinedButton(
                onClick = onStartAdb,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(tr("Start via ADB"), fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun UnifiedStopCard(
    onStop: () -> Unit,
    onRefreshState: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(tr("Unified stop"), style = MaterialTheme.typography.titleSmall)
            Text(
                tr("Stop core process regardless of start mode."),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                fontSize = 12.sp
            )
            OutlinedButton(
                onClick = onStop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 8.dp,
                    vertical = 4.dp
                )
            ) {
                Text(tr("Stop Core Process"), fontSize = 12.sp)
            }
            OutlinedButton(
                onClick = onRefreshState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 8.dp,
                    vertical = 4.dp
                )
            ) {
                Text(tr("Refresh Core State"), fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun WirelessBootstrapCard(
    status: WirelessBootstrapStatus,
    onStartGuide: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(tr("Wireless bootstrap"), style = MaterialTheme.typography.titleSmall)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    tr("Wireless ADB setup steps:"),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    fontSize = 12.sp
                )
                Text(
                    tr("1. Ensure Developer options is enabled. If unsure, search online for your phone model."),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    fontSize = 12.sp
                )
                Text(
                    tr("2. Tap \"Open Developer Options (Start Guide)\"."),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    fontSize = 12.sp
                )
                Text(
                    tr("3. In Developer options, find Wireless debugging and turn it on."),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    fontSize = 12.sp
                )
                Text(
                    tr("4. Tap \"Pair device with pairing code\"."),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    fontSize = 12.sp
                )
                Text(
                    tr("5. After the pairing code appears, enter it in this app's notification input."),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    fontSize = 12.sp
                )
                Text(
                    tr("6. Return to the app and check whether core starts automatically. If not, tap \"Start via ADB\" manually."),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    fontSize = 12.sp
                )
            }
            Text(
                "${tr("State")}: ${status.state} | ${status.message}",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                fontSize = 12.sp
            )
            OutlinedButton(
                onClick = onStartGuide,
                modifier = Modifier.fillMaxWidth(),
                enabled = !status.running
            ) {
                Text(tr("Open Developer Options (Start Guide)"), fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun RootDirectStartCard(
    rootAvailable: Boolean,
    rootDetail: String,
    onStartRoot: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(tr("Root start"), style = MaterialTheme.typography.titleSmall)
            Text(
                tr("For rooted phones: start lxb-core directly via su. No Wireless ADB guide required."),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                fontSize = 12.sp
            )
            Text(
                tr("Requires root permission grant in superuser manager."),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                fontSize = 12.sp
            )
            Text(
                "${tr("State")}: ${if (rootAvailable) tr("Root available") else tr("Root unavailable")} ($rootDetail)",
                color = if (rootAvailable) Color(0xFF2E7D32) else Color(0xFFE65100),
                fontSize = 12.sp
            )
            OutlinedButton(
                onClick = onStartRoot,
                modifier = Modifier.fillMaxWidth(),
                enabled = rootAvailable
            ) {
                Text(tr("Start via Root"), fontSize = 12.sp)
            }
        }
    }
}

// Tab 3: Config

@Composable
fun ConfigTab(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    // simple in-tab navigation:
    // 0 = overview, 1 = control mode config, 2 = LLM, 3 = unlock policy, 4 = map sync
    var page by rememberSaveable { mutableIntStateOf(0) }

    when (page) {
        0 -> ConfigOverviewPage(
            modifier = modifier,
            viewModel = viewModel,
            onOpenDeviceCore = { page = 1 },
            onOpenLlm = { page = 2 },
            onOpenUnlockPolicy = { page = 3 },
            onOpenMapSync = { page = 4 }
        )
        1 -> SingleConfigPage(
            title = tr("Control mode config"),
            modifier = modifier,
            onBack = { page = 0 }
        ) {
            LxbCoreConfigCard(viewModel)
        }
        2 -> SingleConfigPage(
            title = tr("LLM config (device-side)"),
            modifier = modifier,
            onBack = { page = 0 }
        ) {
            LlmConfigCard(viewModel)
        }
        3 -> SingleConfigPage(
            title = tr("Unlock & lock policy"),
            modifier = modifier,
            onBack = { page = 0 }
        ) {
            UnlockPolicyConfigCard(viewModel)
        }
        4 -> SingleConfigPage(
            title = tr("Map sync & source"),
            modifier = modifier,
            onBack = { page = 0 }
        ) {
            MapSyncConfigCard(viewModel)
        }
    }
}

// Tab 3: Logs

@Composable
fun LogsTab(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val logLines by viewModel.logLines.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        LogPanel(logLines = logLines, modifier = Modifier.fillMaxSize())
    }
}

@Composable
fun LogPanel(logLines: List<String>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(logLines.size) {
        if (logLines.isNotEmpty()) {
            listState.animateScrollToItem(logLines.size - 1)
        }
    }
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .padding(8.dp)
                .fillMaxSize()
        ) {
            Text(tr("Logs"), style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(4.dp))
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.05f))
            ) {
                items(logLines) { line ->
                    Text(
                        text = line,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = when {
                            line.startsWith("[ERR]") -> Color(0xFFF44336)
                            line.startsWith("[LXB]") -> Color(0xFF2196F3)
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
        }
    }
}

// Config overview + sub-pages

@Composable
fun ConfigOverviewPage(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel,
    onOpenDeviceCore: () -> Unit,
    onOpenLlm: () -> Unit,
    onOpenUnlockPolicy: () -> Unit,
    onOpenMapSync: () -> Unit
) {
    val uiLang by viewModel.uiLang.collectAsState()
    val coreRuntime by viewModel.coreRuntimeStatus.collectAsState()
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            tr("Config center"),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = tr("Choose a category to configure. Each section opens a dedicated settings page."),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        ConfigEntryCard(
            title = tr("Control mode config"),
            description = tr("If taps/swipes are not working, adjust compatibility mode here."),
            onClick = onOpenDeviceCore
        ) {
            val statusText = if (coreRuntime.ready) tr("Core Connected") else tr("Core Disconnected")
            Text(
                text = "${tr("State")}: $statusText",
                fontSize = 12.sp,
                color = if (coreRuntime.ready) Color(0xFF2E7D32) else Color(0xFFE65100)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = onOpenDeviceCore,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(tr("Open control mode settings"))
            }
        }
        ConfigEntryCard(
            title = tr("Language"),
            description = tr("Language for app UI text."),
            onClick = {}
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.setUiLang("en") },
                    modifier = Modifier.weight(1f),
                    enabled = uiLang != "en"
                ) {
                    Text(tr("English"))
                }
                OutlinedButton(
                    onClick = { viewModel.setUiLang("zh") },
                    modifier = Modifier.weight(1f),
                    enabled = uiLang != "zh"
                ) {
                    Text(tr("Chinese"))
                }
            }
        }
        ConfigEntryCard(
            title = tr("LLM config (device-side)"),
            description = tr("Base URL, API key and model for device-side LLM/VLM calls."),
            onClick = onOpenLlm
        )
        ConfigEntryCard(
            title = tr("Unlock & lock policy"),
            description = tr("Auto unlock before route, auto lock after task, and lockscreen credentials."),
            onClick = onOpenUnlockPolicy
        )
        ConfigEntryCard(
            title = tr("Map sync & source"),
            description = tr("Sync stable maps, pull map by identifier, and choose runtime source lane."),
            onClick = onOpenMapSync
        )
    }
}

@Composable
fun ConfigEntryCard(
    title: String,
    description: String,
    onClick: () -> Unit,
    content: (@Composable () -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
            )
            if (content != null) {
                Spacer(modifier = Modifier.height(4.dp))
                content()
            }
        }
    }
}

@Composable
fun SingleConfigPage(
    title: String,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 8.dp,
                    vertical = 4.dp
                ),
                modifier = Modifier.height(32.dp)
            ) {
                Text(tr("Back"), fontSize = 12.sp)
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .weight(1f),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        content()
    }
}

@Composable
fun LxbCoreConfigCard(viewModel: MainViewModel) {
    val lxbPort by viewModel.lxbPort.collectAsState()
    val touchMode by viewModel.touchMode.collectAsState()
    val taskDndMode by viewModel.taskDndMode.collectAsState()
    val coreConfigResult by viewModel.coreConfigResult.collectAsState()
    val coreRuntime by viewModel.coreRuntimeStatus.collectAsState()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(tr("Control mode config"), style = MaterialTheme.typography.titleSmall)
                Text(
                    tr("Configure touch injection mode and compatibility."),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    fontSize = 12.sp
                )
                Text(
                    text = "${tr("State")}: ${if (coreRuntime.ready) tr("Core Connected") else tr("Core Disconnected")}",
                    fontSize = 12.sp,
                    color = if (coreRuntime.ready) Color(0xFF2E7D32) else Color(0xFFE65100)
                )
                OutlinedTextField(
                    value = lxbPort,
                    onValueChange = { viewModel.lxbPort.value = it },
                    label = { Text(tr("TCP port")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    supportingText = {
                        Text(
                            tr("TCP port listened by lxb-core on device (default 12345)"),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            fontSize = 12.sp
                        )
                    }
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(tr("Touch input mode"), style = MaterialTheme.typography.bodyMedium)
                Text(
                    tr("How taps/swipes are injected to the device."),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    fontSize = 12.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ModeChoiceButton(
                        text = tr("Shell"),
                        selected = touchMode == MainViewModel.TOUCH_MODE_SHELL,
                        onClick = { viewModel.setTouchMode(MainViewModel.TOUCH_MODE_SHELL) },
                        modifier = Modifier.weight(1f)
                    )
                    ModeChoiceButton(
                        text = tr("UIAutomator"),
                        selected = touchMode == MainViewModel.TOUCH_MODE_UIAUTOMATION,
                        onClick = { viewModel.setTouchMode(MainViewModel.TOUCH_MODE_UIAUTOMATION) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(tr("Task-time Do Not Disturb"), style = MaterialTheme.typography.bodyMedium)
                Text(
                    tr("Policy applied when a task starts."),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    fontSize = 12.sp
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeChoiceButton(
                        text = tr("Keep current DND state"),
                        selected = taskDndMode == MainViewModel.TASK_DND_MODE_SKIP,
                        onClick = { viewModel.setTaskDndMode(MainViewModel.TASK_DND_MODE_SKIP) }
                    )
                    ModeChoiceButton(
                        text = tr("Set DND to OFF (allow notifications)"),
                        selected = taskDndMode == MainViewModel.TASK_DND_MODE_OFF,
                        onClick = { viewModel.setTaskDndMode(MainViewModel.TASK_DND_MODE_OFF) }
                    )
                    ModeChoiceButton(
                        text = tr("Set DND to NONE (silence all)"),
                        selected = taskDndMode == MainViewModel.TASK_DND_MODE_NONE,
                        onClick = { viewModel.setTaskDndMode(MainViewModel.TASK_DND_MODE_NONE) }
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.applyTouchModeToCore() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(tr("Apply to core"))
                    }
                    OutlinedButton(
                        onClick = { viewModel.saveConfig() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(tr("Save all config only"))
                    }
                }
                if (coreConfigResult.isNotEmpty()) {
                    Text(
                        text = coreConfigResult,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeChoiceButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    if (selected) {
        Button(
            onClick = onClick,
            enabled = false,
            modifier = modifier
        ) {
            Text(text)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier
        ) {
            Text(text)
        }
    }
}

@Composable
fun LlmConfigCard(viewModel: MainViewModel) {
    val llmBaseUrl by viewModel.llmBaseUrl.collectAsState()
    val llmApiKey by viewModel.llmApiKey.collectAsState()
    val llmModel by viewModel.llmModel.collectAsState()
    val llmTestResult by viewModel.llmTestResult.collectAsState()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(tr("LLM config (device-side)"), style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = llmBaseUrl,
                onValueChange = { viewModel.llmBaseUrl.value = it },
                label = { Text(tr("API Base URL")) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = {
                    Text(
                        tr("Use OpenAI Chat Completions compatible endpoint. URL should end with /v1 or /v1/chat/completions."),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                }
            )
            OutlinedTextField(
                value = llmApiKey,
                onValueChange = { viewModel.llmApiKey.value = it },
                label = { Text(tr("API Key")) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = llmModel,
                onValueChange = { viewModel.llmModel.value = it },
                label = { Text(tr("Model")) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = { Text(tr("e.g. gpt-4o-mini, qwen-plus")) }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.testLlmAndSyncConfig() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(tr("Test LLM & sync to device"))
                }
                OutlinedButton(
                    onClick = { viewModel.saveConfig() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(tr("Save only"))
                }
            }
            if (llmTestResult.isNotEmpty()) {
                Text(
                    text = llmTestResult,
                    fontSize = 12.sp,
                    color = if (llmTestResult.startsWith("LLM ")) {
                        Color(0xFF4CAF50)
                    } else {
                        Color(0xFFFF9800)
                    }
                )
            }
        }
    }
}

@Composable
fun UnlockPolicyConfigCard(viewModel: MainViewModel) {
    val autoUnlockBeforeRoute by viewModel.autoUnlockBeforeRoute.collectAsState()
    val autoLockAfterTask by viewModel.autoLockAfterTask.collectAsState()
    val unlockPin by viewModel.unlockPin.collectAsState()
    val llmTestResult by viewModel.llmTestResult.collectAsState()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(tr("Unlock & lock policy"), style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(tr("Auto unlock before route"), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        tr("Check screen state and unlock before app launch/routing."),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                }
                Switch(
                    checked = autoUnlockBeforeRoute,
                    onCheckedChange = { viewModel.autoUnlockBeforeRoute.value = it }
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(tr("Auto lock after task"), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        tr("Lock screen when task ends if the FSM unlocked it."),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                }
                Switch(
                    checked = autoLockAfterTask,
                    onCheckedChange = { viewModel.autoLockAfterTask.value = it }
                )
            }
            OutlinedTextField(
                value = unlockPin,
                onValueChange = { viewModel.unlockPin.value = it },
                label = { Text(tr("Unlock PIN / password")) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = {
                    Text(tr("Used only when swipe unlock is not enough."))
                }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.syncDeviceConfigOnly() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(tr("Sync to device"))
                }
                OutlinedButton(
                    onClick = { viewModel.saveConfig() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(tr("Save only"))
                }
            }
            if (llmTestResult.isNotEmpty()) {
                Text(
                    text = llmTestResult,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )
            }
        }
    }
}

@Composable
fun MapSyncConfigCard(viewModel: MainViewModel) {
    val mapRepoRawBaseUrl by viewModel.mapRepoRawBaseUrl.collectAsState()
    val useMap by viewModel.useMap.collectAsState()
    val mapSource by viewModel.mapSource.collectAsState()
    val mapTargetPackage by viewModel.mapTargetPackage.collectAsState()
    val mapTargetId by viewModel.mapTargetId.collectAsState()
    val mapSyncResult by viewModel.mapSyncResult.collectAsState()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(tr("Map sync & lane control"), style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = mapRepoRawBaseUrl,
                onValueChange = { viewModel.mapRepoRawBaseUrl.value = it },
                label = { Text(tr("Map repo raw base URL")) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = {
                    Text(tr("e.g. https://raw.githubusercontent.com/wuwei-crg/LXB-MapRepo/main"))
                }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(tr("Use map routing"), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = if (useMap) {
                            tr("ON: route with map when available.")
                        } else {
                            tr("OFF: force no-map mode (launch then vision-only).")
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                    )
                }
                Switch(
                    checked = useMap,
                    onCheckedChange = { viewModel.setUseMap(it) }
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(tr("Map source"), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = tr("Choose which lane is applied to runtime routing map."),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.setMapSource("stable") },
                    modifier = Modifier.weight(1f),
                    enabled = mapSource != "stable"
                ) {
                    Text(tr("Stable"))
                }
                OutlinedButton(
                    onClick = { viewModel.setMapSource("candidate") },
                    modifier = Modifier.weight(1f),
                    enabled = mapSource != "candidate"
                ) {
                    Text(tr("Candidate"))
                }
                OutlinedButton(
                    onClick = { viewModel.setMapSource("burn") },
                    modifier = Modifier.weight(1f),
                    enabled = mapSource != "burn"
                ) {
                    Text(tr("Burn"))
                }
            }
            OutlinedTextField(
                value = mapTargetPackage,
                onValueChange = { viewModel.mapTargetPackage.value = it },
                label = { Text(tr("Package name")) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = {
                    Text(tr("Used for pull-by-identifier and active status."))
                }
            )
            OutlinedTextField(
                value = mapTargetId,
                onValueChange = { viewModel.mapTargetId.value = it },
                label = { Text(tr("Map ID")) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = {
                    Text(tr("Required for stable/candidate pull by identifier."))
                }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.syncStableMapsNow() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(tr("Sync Stable All"))
                }
                OutlinedButton(
                    onClick = { viewModel.pullStableByIdentifierNow() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(tr("Pull Stable by ID"))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { viewModel.pullCandidateByIdentifierNow() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(tr("Pull Candidate by ID"))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { viewModel.checkActiveMapStatus() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(tr("Show active"))
                }
                OutlinedButton(
                    onClick = { viewModel.saveConfig() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(tr("Save only"))
                }
            }
            if (mapSyncResult.isNotEmpty()) {
                Text(
                    text = mapSyncResult,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
                )
            }
        }
    }
}
