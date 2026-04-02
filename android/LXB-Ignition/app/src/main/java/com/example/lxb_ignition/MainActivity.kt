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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.material3.HorizontalDivider
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
import com.example.lxb_ignition.model.AppPackageOption
import com.example.lxb_ignition.model.CoreRuntimeStatus
import com.example.lxb_ignition.model.NotificationTriggerRuleSummary
import com.example.lxb_ignition.model.ScheduleSummary
import com.example.lxb_ignition.model.TaskSummary
import com.example.lxb_ignition.model.TraceEntry
import com.example.lxb_ignition.model.TraceMetaItem
import com.example.lxb_ignition.model.TaskRuntimeUiStatus
import com.example.lxb_ignition.model.WirelessBootstrapStatus
import com.example.lxb_ignition.ui.theme.LXBIgnitionTheme
import com.lxb.server.cortex.LlmClient
import kotlinx.coroutines.delay
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

private data class WirelessGuideUiState(
    val headline: String,
    val detail: String,
    val stepIndex: Int,
    val accentColor: Color,
    val ready: Boolean = false,
    val failed: Boolean = false,
    val paired: Boolean = false
)

private fun resolveWirelessGuideUiState(status: WirelessBootstrapStatus): WirelessGuideUiState {
    val detail = status.message.ifBlank { "Idle" }
    return when (status.state.uppercase(Locale.ROOT)) {
        "GUIDE_SETTINGS" -> WirelessGuideUiState(
            headline = "Open Developer Options",
            detail = detail,
            stepIndex = 1,
            accentColor = Color(0xFF1565C0)
        )
        "WAIT_INPUT" -> WirelessGuideUiState(
            headline = "Waiting for pairing code",
            detail = detail,
            stepIndex = 4,
            accentColor = Color(0xFF1565C0)
        )
        "PAIRING" -> WirelessGuideUiState(
            headline = "Pairing phone",
            detail = detail,
            stepIndex = 4,
            accentColor = Color(0xFFEF6C00)
        )
        "PAIRED" -> WirelessGuideUiState(
            headline = "Phone paired successfully",
            detail = detail,
            stepIndex = 4,
            accentColor = Color(0xFF2E7D32),
            paired = true
        )
        "CONNECTING" -> WirelessGuideUiState(
            headline = "Connecting phone",
            detail = detail,
            stepIndex = 4,
            accentColor = Color(0xFFEF6C00)
        )
        "STARTING_CORE" -> WirelessGuideUiState(
            headline = "Starting core service",
            detail = detail,
            stepIndex = 4,
            accentColor = Color(0xFF6A1B9A)
        )
        "RECONNECTING" -> WirelessGuideUiState(
            headline = "Recovering core connection",
            detail = detail,
            stepIndex = 4,
            accentColor = Color(0xFFEF6C00)
        )
        "RUNNING" -> WirelessGuideUiState(
            headline = "Ready to use",
            detail = detail,
            stepIndex = 4,
            accentColor = Color(0xFF2E7D32),
            ready = true
        )
        "FAILED" -> WirelessGuideUiState(
            headline = "Startup failed",
            detail = detail,
            stepIndex = 4,
            accentColor = Color(0xFFC62828),
            failed = true
        )
        "STOPPING" -> WirelessGuideUiState(
            headline = "Stopping core service",
            detail = detail,
            stepIndex = 4,
            accentColor = Color(0xFF616161)
        )
        else -> WirelessGuideUiState(
            headline = "Not started yet",
            detail = detail,
            stepIndex = 1,
            accentColor = Color(0xFF616161)
        )
    }
}

@Composable
fun ControlTab(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val coreRuntime by viewModel.coreRuntimeStatus.collectAsState()
    val wireless by viewModel.wirelessBootstrapStatus.collectAsState()
    val wirelessDebuggingEnabled by viewModel.wirelessDebuggingEnabled.collectAsState()
    val rootAvailable by viewModel.rootAvailable.collectAsState()
    val rootDetail by viewModel.rootDetail.collectAsState()
    var page by rememberSaveable { mutableIntStateOf(0) }

    when (page) {
        0 -> ControlOverviewPage(
            modifier = modifier,
            coreRuntime = coreRuntime,
            wireless = wireless,
            rootAvailable = rootAvailable,
            rootDetail = rootDetail,
            onOpenWirelessGuide = { page = 1 },
            onOpenRootGuide = { page = 2 },
            onStop = { viewModel.stopServerProcess() },
            onRefreshState = { viewModel.refreshCoreRuntimeStatusNow() }
        )
        1 -> SingleConfigPage(
            title = tr("Wireless ADB startup"),
            modifier = modifier,
            onBack = { page = 0 }
        ) {
            WirelessGuidePage(
                status = wireless,
                wirelessDebuggingEnabled = wirelessDebuggingEnabled,
                onStartGuide = { viewModel.startWirelessBootstrapGuide() },
                onStartDirect = { viewModel.startServerWithNative() },
                onOpenWirelessDebugging = { viewModel.openWirelessDebuggingSettings() },
                onRefreshState = {
                    viewModel.refreshWirelessDebuggingEnabled()
                    viewModel.refreshCoreRuntimeStatusNow()
                }
            )
        }
        2 -> SingleConfigPage(
            title = tr("Root startup"),
            modifier = modifier,
            onBack = { page = 0 }
        ) {
            RootStartGuidePage(
                rootAvailable = rootAvailable,
                rootDetail = rootDetail,
                coreRuntime = coreRuntime,
                onStartRoot = { viewModel.startServerWithRootDirect() },
                onRefreshRoot = { viewModel.refreshRootAvailability() },
                onRefreshState = { viewModel.refreshCoreRuntimeStatusNow() }
            )
        }
    }
}

@Composable
private fun ControlOverviewPage(
    modifier: Modifier = Modifier,
    coreRuntime: CoreRuntimeStatus,
    wireless: WirelessBootstrapStatus,
    rootAvailable: Boolean,
    rootDetail: String,
    onOpenWirelessGuide: () -> Unit,
    onOpenRootGuide: () -> Unit,
    onStop: () -> Unit,
    onRefreshState: () -> Unit
) {
    val scrollState = rememberScrollState()
    val wirelessUi = resolveWirelessGuideUiState(wireless)
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ProcessRuntimeCard(status = coreRuntime)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(tr("Choose startup method"), style = MaterialTheme.typography.titleSmall)
                Text(
                    tr("Pick the method that matches your phone. Detailed steps are inside each page."),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.74f),
                    fontSize = 12.sp
                )
            }
        }
        StartupMethodCard(
            title = tr("Wireless ADB startup"),
            description = tr("Recommended for most phones without root."),
            statusTitle = tr(wirelessUi.headline),
            statusDetail = wirelessUi.detail,
            accentColor = wirelessUi.accentColor,
            buttonText = tr("Open guide"),
            onClick = onOpenWirelessGuide
        )
        StartupMethodCard(
            title = tr("Root startup"),
            description = tr("For rooted phones. Starts lxb-core directly with su."),
            statusTitle = if (rootAvailable) tr("Root available") else tr("Root unavailable"),
            statusDetail = rootDetail,
            accentColor = if (rootAvailable) Color(0xFF2E7D32) else Color(0xFFE65100),
            buttonText = tr("Open root page"),
            onClick = onOpenRootGuide
        )
        UnifiedStopCard(
            onStop = onStop,
            onRefreshState = onRefreshState
        )
    }
}

@Composable
private fun StartupMethodCard(
    title: String,
    description: String,
    statusTitle: String,
    statusDetail: String,
    accentColor: Color,
    buttonText: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                description,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.74f),
                fontSize = 12.sp
            )
            Card(colors = CardDefaults.cardColors(containerColor = accentColor.copy(alpha = 0.12f))) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = statusTitle,
                        color = accentColor,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = statusDetail,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                        fontSize = 12.sp
                    )
                }
            }
            Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
                Text(buttonText)
            }
        }
    }
}

@Composable
private fun WirelessGuidePage(
    status: WirelessBootstrapStatus,
    wirelessDebuggingEnabled: Boolean,
    onStartGuide: () -> Unit,
    onStartDirect: () -> Unit,
    onOpenWirelessDebugging: () -> Unit,
    onRefreshState: () -> Unit
) {
    val ui = resolveWirelessGuideUiState(status)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = ui.accentColor.copy(alpha = 0.12f))
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(tr("This is the easiest path for most users."), style = MaterialTheme.typography.titleSmall)
                Text(
                    tr("The app will open Developer Options, then wait for you to enter the 6-digit pairing code from the notification."),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                    fontSize = 12.sp
                )
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(tr(ui.headline), style = MaterialTheme.typography.titleSmall, color = ui.accentColor)
                Text(
                    ui.detail,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
                    fontSize = 12.sp
                )
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(tr("What to do"), style = MaterialTheme.typography.titleSmall)
                GuideStepRow(
                    number = 1,
                    title = tr("Turn on Developer Options"),
                    detail = tr("If your phone has not enabled Developer Options yet, search your phone model first."),
                    active = ui.stepIndex == 1 && !ui.ready,
                    done = ui.stepIndex > 1 || ui.ready
                )
                GuideStepRow(
                    number = 2,
                    title = tr("Turn on Wireless debugging"),
                    detail = tr("Open Developer Options, find Wireless debugging, and switch it on."),
                    active = ui.stepIndex == 2 && !ui.ready,
                    done = ui.stepIndex > 2 || ui.ready
                )
                GuideStepRow(
                    number = 3,
                    title = tr("Open the pairing-code page"),
                    detail = tr("Tap \"Pair device with pairing code\" and keep that page open."),
                    active = ui.stepIndex == 3 && !ui.ready,
                    done = ui.stepIndex > 3 || ui.ready
                )
                GuideStepRow(
                    number = 4,
                    title = tr("Enter the pairing code in the notification"),
                    detail = tr("After the notification appears, enter the 6-digit pairing code there and wait for startup to finish."),
                    active = ui.stepIndex == 4 && !ui.ready && !ui.paired,
                    done = ui.ready || ui.paired
                )
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(tr("Actions"), style = MaterialTheme.typography.titleSmall)
                Button(onClick = onStartGuide, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("Open Developer Options (Start Guide)"))
                }
                OutlinedButton(
                    onClick = onStartDirect,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = wirelessDebuggingEnabled
                ) {
                    Text(tr("I already paired before, start directly"))
                }
                if (!wirelessDebuggingEnabled) {
                    Text(
                        tr("Please make sure Wireless debugging is turned on first."),
                        color = Color(0xFFE65100),
                        fontSize = 12.sp
                    )
                    OutlinedButton(onClick = onOpenWirelessDebugging, modifier = Modifier.fillMaxWidth()) {
                        Text(tr("Open Wireless debugging settings"))
                    }
                }
                OutlinedButton(onClick = onRefreshState, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("Refresh connection state"))
                }
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(tr("Need help?"), style = MaterialTheme.typography.titleSmall)
                Text(
                    tr("- USB debugging must be enabled, otherwise process keepalive may fail."),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                    fontSize = 12.sp
                )
                Text(
                    tr("- MIUI / HyperOS: enable \"USB debugging (Security settings)\". This is separate from \"USB debugging\"."),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                    fontSize = 12.sp
                )
                Text(
                    tr("- ColorOS (OPPO / OnePlus): disable \"Permission monitoring\" in Developer options."),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                    fontSize = 12.sp
                )
                Text(
                    tr("- Flyme: disable \"Flyme payment protection\" in Developer options."),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                    fontSize = 12.sp
                )
                Text(
                    tr("Reference: ROM-specific notes above are adapted from Shizuku docs."),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.64f),
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun GuideStepRow(
    number: Int,
    title: String,
    detail: String,
    active: Boolean,
    done: Boolean
) {
    val bg = when {
        done -> Color(0xFF2E7D32).copy(alpha = 0.10f)
        active -> MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when {
        done -> Color(0xFF2E7D32)
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, shape = MaterialTheme.shapes.medium)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = number.toString(),
            color = fg,
            style = MaterialTheme.typography.titleSmall
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = fg, style = MaterialTheme.typography.bodyMedium)
            Text(
                detail,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun RootStartGuidePage(
    rootAvailable: Boolean,
    rootDetail: String,
    coreRuntime: CoreRuntimeStatus,
    onStartRoot: () -> Unit,
    onRefreshRoot: () -> Unit,
    onRefreshState: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2E7D32).copy(alpha = 0.10f))
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(tr("Root startup"), style = MaterialTheme.typography.titleSmall)
                Text(
                    tr("For rooted phones. Starts lxb-core directly with su."),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                    fontSize = 12.sp
                )
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = if (rootAvailable) tr("Root available") else tr("Root unavailable"),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (rootAvailable) Color(0xFF2E7D32) else Color(0xFFE65100)
                )
                Text(rootDetail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f))
                Text(
                    "${tr("State")}: ${if (coreRuntime.ready) tr("Core Connected") else tr("Core Disconnected")}",
                    fontSize = 12.sp,
                    color = if (coreRuntime.ready) Color(0xFF2E7D32) else Color(0xFFE65100)
                )
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(tr("Before you start"), style = MaterialTheme.typography.titleSmall)
                Text(
                    tr("Requires root permission grant in superuser manager."),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                    fontSize = 12.sp
                )
                Text(
                    tr("No Wireless ADB guide is needed on rooted phones."),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                    fontSize = 12.sp
                )
                Text(
                    tr("If startup fails, check whether the root permission popup was denied or timed out."),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                    fontSize = 12.sp
                )
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(tr("Actions"), style = MaterialTheme.typography.titleSmall)
                Button(
                    onClick = onStartRoot,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = rootAvailable
                ) {
                    Text(tr("Start via Root"))
                }
                OutlinedButton(onClick = onRefreshRoot, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("Check Root"))
                }
                OutlinedButton(onClick = onRefreshState, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("Refresh connection state"))
                }
            }
        }
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
    "Core Trace" to "Core 追踪",
    "Trace Details" to "追踪详情",
    "Load older traces..." to "正在加载更早的追踪...",
    "No trace details." to "没有更多追踪详情。",
    "Key Fields" to "关键信息",
    "All Fields" to "完整字段",
    "Raw Trace" to "原始追踪",
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
    "Select App" to "选择应用",
    "Search apps" to "搜索应用",
    "No app found." to "未找到应用。",
    "Anytime" to "任意时段",
    "Package (select from local snapshot)" to "包名（从本地快照选择）",
    "Clear" to "清空",
    "No runs yet. Submit a task from Task Session or wait for schedules." to "暂无执行记录。可在任务会话中提交任务，或等待定时任务执行。",
    "Schedules" to "定时任务",
    "Notification Triggers" to "通知触发任务",
    "Recent Runs" to "最近执行",
    "Manage notification-triggered tasks and create new ones." to "管理通知触发任务并创建新规则。",
    "No notification triggers yet." to "暂无通知触发规则。",
    "Edit Notification Trigger" to "编辑通知触发任务",
    "Create Notification Trigger" to "新建通知触发任务",
    "Rule name" to "规则名称",
    "Task description (what to do after trigger)" to "任务描述（触发后执行什么）",
    "Rule settings" to "规则设置",
    "Package match (required)" to "Package 匹配（必填）",
    "Package match (optional)" to "Package 匹配（可选）",
    "Package match (select from local snapshot)" to "Package 匹配（从本地快照选择）",
    "Trigger interval (seconds)" to "触发间隔（秒）",
    "Trigger time start (HH:mm, optional)" to "触发时段开始（HH:mm，可选）",
    "Trigger time end (HH:mm, optional)" to "触发时段结束（HH:mm，可选）",
    "Leave either field empty to allow triggering at any time." to "开始或结束留空时，表示任意时段都可触发。",
    "Title match (optional)" to "标题匹配（可选）",
    "Body match (optional)" to "正文匹配（可选）",
    "LLM condition (optional)" to "LLM 条件（可选）",
    "Trigger name (optional)" to "规则名称（可选）",
    "Enable rule" to "启用规则",
    "Priority (high first)" to "优先级（值越高越先匹配）",
    "Package mode" to "包名匹配模式",
    "Any package" to "任意包名",
    "Allow list" to "仅允许列表包名",
    "Block list" to "排除列表包名",
    "Package list (comma/newline separated)" to "包名列表（逗号或换行分隔）",
    "Text match mode" to "文本匹配模式",
    "Contains" to "包含",
    "Regex" to "正则",
    "Title pattern (optional)" to "标题匹配（可选）",
    "Body pattern (optional)" to "正文匹配（可选）",
    "Enable LLM condition" to "启用 LLM 条件判断",
    "LLM condition" to "LLM 条件",
    "Yes token" to "Yes 标记",
    "No token" to "No 标记",
    "LLM timeout ms" to "LLM 超时（毫秒）",
    "Enable task rewrite" to "启用任务重写",
    "Rewrite instruction / fallback task" to "重写指令 / 回退任务描述",
    "Rewrite timeout ms" to "重写超时（毫秒）",
    "Rewrite fail policy" to "重写失败策略",
    "Fallback to raw task" to "回退到原始任务",
    "Skip trigger when rewrite fails" to "重写失败则跳过触发",
    "Cooldown ms" to "冷却时间（毫秒）",
    "Stop after matched" to "命中后停止继续匹配",
    "Action package (optional)" to "任务目标包名（可选）",
    "Action task (required if rewrite is empty)" to "任务描述（当重写为空时必填）",
    "Action playbook (optional)" to "任务操作文档（可选）",
    "Action use-map override" to "是否覆盖 Use-Map",
    "Inherit global setting" to "继承全局设置",
    "Force use map" to "强制使用地图",
    "Force no map" to "强制不使用地图",
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
    "Record screen for triggered task" to "为通知触发任务录屏",
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
    "Choose startup method" to "选择启动方式",
    "Pick the method that matches your phone. Detailed steps are inside each page." to "选择适合你手机的启动方式，详细步骤已放进各自页面。",
    "Wireless ADB startup" to "无线 ADB 启动",
    "Recommended for most phones without root." to "适合大多数未 Root 手机。",
    "Open guide" to "打开引导",
    "Root startup" to "Root 启动",
    "For rooted phones. Starts lxb-core directly with su." to "适合已 Root 手机，直接通过 su 启动 lxb-core。",
    "Open root page" to "打开 Root 页面",
    "Open Developer Options" to "打开开发者选项",
    "Waiting for pairing code" to "等待输入配对码",
    "Pairing phone" to "正在配对手机",
    "Phone paired successfully" to "手机已配对成功",
    "Connecting phone" to "正在连接手机",
    "Starting core service" to "正在启动核心服务",
    "Recovering core connection" to "正在恢复核心连接",
    "Ready to use" to "已可使用",
    "Startup failed" to "启动失败",
    "Stopping core service" to "正在停止核心服务",
    "Not started yet" to "尚未启动",
    "This is the easiest path for most users." to "这是大多数用户最省事的启动方式。",
    "The app will open Developer Options, then wait for you to enter the 6-digit pairing code from the notification." to "应用会先打开开发者选项，然后等待你在通知栏中输入 6 位配对码。",
    "What to do" to "你需要做什么",
    "Turn on Developer Options" to "打开开发者选项",
    "If your phone has not enabled Developer Options yet, search your phone model first." to "如果你的手机还没开启开发者选项，请先搜索自己机型的开启方法。",
    "Turn on Wireless debugging" to "打开无线调试",
    "Open Developer Options, find Wireless debugging, and switch it on." to "打开开发者选项，找到“无线调试”，并将其打开。",
    "Open the pairing-code page" to "打开配对码页面",
    "Tap \"Pair device with pairing code\" and keep that page open." to "点击“使用配对码配对设备”，并保持该页面开启。",
    "Enter the pairing code in the notification" to "在通知栏输入配对码",
    "After the notification appears, enter the 6-digit pairing code there and wait for startup to finish." to "通知出现后，直接在通知栏输入 6 位配对码，并等待启动完成。",
    "Actions" to "操作",
    "I already paired before, start directly" to "我之前已经配对过，直接启动",
    "Please make sure Wireless debugging is turned on first." to "请先确认已经打开无线调试。",
    "Open Wireless debugging settings" to "打开无线调试设置",
    "Refresh connection state" to "刷新连接状态",
    "Need help?" to "需要帮助？",
    "Before you start" to "开始前说明",
    "No Wireless ADB guide is needed on rooted phones." to "Root 手机不需要走无线 ADB 引导。",
    "If startup fails, check whether the root permission popup was denied or timed out." to "如果启动失败，请检查 Root 授权弹窗是否被拒绝或超时。",
    "Start Native" to "原生启动",
    "ADB start" to "ADB 启动",
    "Use Wireless ADB bootstrap path. Pair once, then tap start." to "使用无线 ADB 引导路径，先完成配对，再点击启动。",
    "Start via ADB" to "通过 ADB 启动",
    "Before starting (ADB path):" to "开始前准备（ADB 路径）：",
    "- USB debugging must be enabled, otherwise process keepalive may fail." to "- 必须开启 USB 调试，否则进程可能无法保活。",
    "- MIUI / HyperOS: enable \"USB debugging (Security settings)\". This is separate from \"USB debugging\"." to "- MIUI / HyperOS：开启“USB 调试（安全设置）”，注意它和“USB 调试”是两个选项。",
    "- ColorOS (OPPO / OnePlus): disable \"Permission monitoring\" in Developer options." to "- ColorOS（OPPO / OnePlus）：在开发者选项中关闭“权限监控”。",
    "- Flyme: disable \"Flyme payment protection\" in Developer options." to "- Flyme：在开发者选项中关闭“Flyme 支付保护”。",
    "Reference: ROM-specific notes above are adapted from Shizuku docs." to "说明：以上 ROM 特殊设置参考自 Shizuku 文档。",
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
    "Endpoint is auto-completed to /chat/completions." to "会自动补齐为 /chat/completions 端点。",
    "Resolved request URL" to "真实调用 URL",
    "Input API Base URL to preview request endpoint." to "输入 API Base URL 后，这里会实时显示最终请求地址。",
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
    val notifyRules by viewModel.notifyRuleList.collectAsState()
    val installedApps by viewModel.installedAppList.collectAsState()
    val taskRuntime by viewModel.taskRuntimeUiStatus.collectAsState()
    val scheduleName by viewModel.scheduleName.collectAsState()
    val scheduleTask by viewModel.scheduleTask.collectAsState()
    val scheduleStartAtMs by viewModel.scheduleStartAtMs.collectAsState()
    val scheduleRepeatMode by viewModel.scheduleRepeatMode.collectAsState()
    val scheduleRepeatWeekdays by viewModel.scheduleRepeatWeekdays.collectAsState()
    val schedulePackage by viewModel.schedulePackage.collectAsState()
    val schedulePlaybook by viewModel.schedulePlaybook.collectAsState()
    val scheduleRecordEnabled by viewModel.scheduleRecordEnabled.collectAsState()
    val notifyName by viewModel.notifyName.collectAsState()
    val notifyEnabled by viewModel.notifyEnabled.collectAsState()
    val notifyPriority by viewModel.notifyPriority.collectAsState()
    val notifyPackageMode by viewModel.notifyPackageMode.collectAsState()
    val notifyPackageListRaw by viewModel.notifyPackageListRaw.collectAsState()
    val notifyTextMode by viewModel.notifyTextMode.collectAsState()
    val notifyTitlePattern by viewModel.notifyTitlePattern.collectAsState()
    val notifyBodyPattern by viewModel.notifyBodyPattern.collectAsState()
    val notifyLlmConditionEnabled by viewModel.notifyLlmConditionEnabled.collectAsState()
    val notifyLlmCondition by viewModel.notifyLlmCondition.collectAsState()
    val notifyLlmYesToken by viewModel.notifyLlmYesToken.collectAsState()
    val notifyLlmNoToken by viewModel.notifyLlmNoToken.collectAsState()
    val notifyLlmTimeoutMs by viewModel.notifyLlmTimeoutMs.collectAsState()
    val notifyTaskRewriteEnabled by viewModel.notifyTaskRewriteEnabled.collectAsState()
    val notifyTaskRewriteInstruction by viewModel.notifyTaskRewriteInstruction.collectAsState()
    val notifyTaskRewriteTimeoutMs by viewModel.notifyTaskRewriteTimeoutMs.collectAsState()
    val notifyTaskRewriteFailPolicy by viewModel.notifyTaskRewriteFailPolicy.collectAsState()
    val notifyCooldownMs by viewModel.notifyCooldownMs.collectAsState()
    val notifyActiveTimeStart by viewModel.notifyActiveTimeStart.collectAsState()
    val notifyActiveTimeEnd by viewModel.notifyActiveTimeEnd.collectAsState()
    val notifyStopAfterMatched by viewModel.notifyStopAfterMatched.collectAsState()
    val notifyActionUserTask by viewModel.notifyActionUserTask.collectAsState()
    val notifyActionPackage by viewModel.notifyActionPackage.collectAsState()
    val notifyActionUserPlaybook by viewModel.notifyActionUserPlaybook.collectAsState()
    val notifyActionRecordEnabled by viewModel.notifyActionRecordEnabled.collectAsState()
    val notifyActionUseMapMode by viewModel.notifyActionUseMapMode.collectAsState()
    var page by rememberSaveable { mutableIntStateOf(0) }
    var editingScheduleId by rememberSaveable { mutableStateOf("") }
    var editingNotifyRuleId by rememberSaveable { mutableStateOf("") }
    var selectedTask by remember { mutableStateOf<TaskSummary?>(null) }

    val pageHome = 0
    val pageScheduleList = 1
    val pageNotifyRuleList = 2
    val pageRecentRuns = 3
    val pageScheduleForm = 4
    val pageNotifyRuleForm = 5

    LaunchedEffect(Unit) {
        viewModel.refreshInstalledAppSnapshotOnDevice()
        viewModel.refreshScheduleListOnDevice()
        viewModel.refreshNotifyRuleListOnDevice()
        viewModel.refreshTaskListOnDevice()
    }

    LaunchedEffect(page) {
        if (page != pageHome && page != pageRecentRuns) return@LaunchedEffect
        while (true) {
            viewModel.refreshTaskListOnDevice(silent = true)
            delay(3000)
        }
    }

    when (page) {
        pageHome -> {
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
                            viewModel.refreshInstalledAppSnapshotOnDevice()
                            viewModel.refreshScheduleListOnDevice()
                            viewModel.refreshNotifyRuleListOnDevice()
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
                    onClick = { page = pageScheduleList }
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
                    onClick = { page = pageNotifyRuleList }
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(tr("Notification Triggers"), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "${tr("Manage notification-triggered tasks and create new ones.")} (${notifyRules.size})",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                        )
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    onClick = { page = pageRecentRuns }
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

        pageScheduleList -> {
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
                        onClick = { page = pageHome },
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
                            page = pageScheduleForm
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
                                        page = pageScheduleForm
                                    },
                                    onDelete = { viewModel.removeScheduleOnDevice(schedule.scheduleId) }
                                )
                            }
                        }
                    }
                }
            }
        }

        pageNotifyRuleList -> {
            val notifyListState = rememberLazyListState()
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
                        onClick = { page = pageHome },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(tr("Back"), fontSize = 12.sp)
                    }
                    Text(
                        text = tr("Notification Triggers"),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = 6.dp)
                    )
                    OutlinedButton(
                        onClick = {
                            editingNotifyRuleId = ""
                            viewModel.resetNotifyRuleForm()
                            page = pageNotifyRuleForm
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(tr("New"), fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = { viewModel.refreshNotifyRuleListOnDevice() },
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
                    if (notifyRules.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp)
                        ) {
                            Text(
                                text = tr("No notification triggers yet."),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    } else {
                        LazyColumn(
                            state = notifyListState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(notifyRules, key = { it.id }) { rule ->
                                NotificationRuleRow(
                                    rule = rule,
                                    onEdit = {
                                        editingNotifyRuleId = rule.id
                                        viewModel.loadNotifyRuleForm(rule)
                                        page = pageNotifyRuleForm
                                    },
                                    onDelete = { viewModel.removeNotifyRuleOnDevice(rule.id) }
                                )
                            }
                        }
                    }
                }
            }
        }

        pageRecentRuns -> {
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
                        onClick = { page = pageHome },
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

        pageScheduleForm -> {
            val context = LocalContext.current
            val selectedRunAt = scheduleStartAtMs.toLongOrNull()?.takeIf { it > 0L }
                ?: (System.currentTimeMillis() + 5 * 60_000L)
            var showTimeWheel by rememberSaveable { mutableStateOf(false) }
            var showSchedulePackagePicker by rememberSaveable { mutableStateOf(false) }
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
                            page = pageScheduleList
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
                        PackageSelectField(
                            title = tr("Package (select from local snapshot)"),
                            selectedPackage = schedulePackage,
                            options = installedApps,
                            onOpen = {
                                if (installedApps.isEmpty()) {
                                    viewModel.refreshInstalledAppSnapshotOnDevice()
                                }
                                showSchedulePackagePicker = true
                            },
                            onClear = { viewModel.schedulePackage.value = "" }
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
                                    page = pageScheduleList
                                    editingScheduleId = ""
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(tr(if (isEditing) "Save" else "Submit"))
                            }
                            OutlinedButton(
                                onClick = {
                                    page = pageScheduleList
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
            if (showSchedulePackagePicker) {
                PackagePickerDialog(
                    options = installedApps,
                    onDismiss = { showSchedulePackagePicker = false },
                    onSelect = { item ->
                        viewModel.schedulePackage.value = item.packageName
                        showSchedulePackagePicker = false
                    }
                )
            }
        }

        pageNotifyRuleForm -> {
            val isEditing = editingNotifyRuleId.isNotEmpty()
            var showNotifyPackagePicker by rememberSaveable { mutableStateOf(false) }
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
                            page = pageNotifyRuleList
                            editingNotifyRuleId = ""
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(tr("Back"), fontSize = 12.sp)
                    }
                    Text(
                        text = tr(if (isEditing) "Edit Notification Trigger" else "Create Notification Trigger"),
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
                            value = notifyName,
                            onValueChange = { viewModel.notifyName.value = it },
                            label = { Text(tr("Rule name")) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = notifyActionUserTask,
                            onValueChange = { viewModel.notifyActionUserTask.value = it },
                            label = { Text(tr("Task description (what to do after trigger)")) },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 4
                        )
                        OutlinedTextField(
                            value = notifyActionUserPlaybook,
                            onValueChange = { viewModel.notifyActionUserPlaybook.value = it },
                            label = { Text(tr("Action playbook (optional)")) },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 8
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = tr("Record screen for triggered task"),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Switch(
                                checked = notifyActionRecordEnabled,
                                onCheckedChange = { viewModel.notifyActionRecordEnabled.value = it }
                            )
                        }
                        Text(
                            text = tr("Rule settings"),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = tr("Enable rule"),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Switch(
                                checked = notifyEnabled,
                                onCheckedChange = { viewModel.notifyEnabled.value = it }
                            )
                        }
                        PackageSelectField(
                            title = "${tr("Package match (required)")} - ${tr("Select App")}",
                            selectedPackage = notifyPackageListRaw,
                            options = installedApps,
                            onOpen = {
                                if (installedApps.isEmpty()) {
                                    viewModel.refreshInstalledAppSnapshotOnDevice()
                                }
                                showNotifyPackagePicker = true
                            },
                            onClear = { viewModel.notifyPackageListRaw.value = "" }
                        )
                        OutlinedTextField(
                            value = notifyCooldownMs,
                            onValueChange = { viewModel.notifyCooldownMs.value = it },
                            label = { Text(tr("Trigger interval (seconds)")) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        OutlinedTextField(
                            value = notifyActiveTimeStart,
                            onValueChange = { viewModel.notifyActiveTimeStart.value = it },
                            label = { Text(tr("Trigger time start (HH:mm, optional)")) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = notifyActiveTimeEnd,
                            onValueChange = { viewModel.notifyActiveTimeEnd.value = it },
                            label = { Text(tr("Trigger time end (HH:mm, optional)")) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Text(
                            text = tr("Leave either field empty to allow triggering at any time."),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        OutlinedTextField(
                            value = notifyTitlePattern,
                            onValueChange = { viewModel.notifyTitlePattern.value = it },
                            label = { Text(tr("Title match (optional)")) },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 2
                        )
                        OutlinedTextField(
                            value = notifyBodyPattern,
                            onValueChange = { viewModel.notifyBodyPattern.value = it },
                            label = { Text(tr("Body match (optional)")) },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 4
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = tr("Enable LLM condition"),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Switch(
                                checked = notifyLlmConditionEnabled,
                                onCheckedChange = { viewModel.notifyLlmConditionEnabled.value = it }
                            )
                        }
                        if (notifyLlmConditionEnabled) {
                            OutlinedTextField(
                                value = notifyLlmCondition,
                                onValueChange = { viewModel.notifyLlmCondition.value = it },
                                label = { Text(tr("LLM condition (optional)")) },
                                modifier = Modifier.fillMaxWidth(),
                                maxLines = 4
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    viewModel.upsertNotifyRuleOnDevice(editingNotifyRuleId)
                                    page = pageNotifyRuleList
                                    editingNotifyRuleId = ""
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(tr(if (isEditing) "Save" else "Submit"))
                            }
                            OutlinedButton(
                                onClick = {
                                    page = pageNotifyRuleList
                                    editingNotifyRuleId = ""
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(tr("Cancel"))
                            }
                        }
                    }
                }
            }
            if (showNotifyPackagePicker) {
                PackagePickerDialog(
                    options = installedApps,
                    onDismiss = { showNotifyPackagePicker = false },
                    onSelect = { item ->
                        viewModel.notifyPackageListRaw.value = item.packageName
                        showNotifyPackagePicker = false
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
fun NotificationRuleRow(
    rule: NotificationTriggerRuleSummary,
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
            val title = when {
                rule.name.isNotBlank() -> rule.name
                rule.actionUserTask.isNotBlank() -> rule.actionUserTask
                else -> "(no task)"
            }
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "enabled=${rule.enabled}, priority=${rule.priority}, package_mode=${rule.packageMode}, text_mode=${rule.textMode}",
                fontSize = 11.sp,
                color = scheme.onSurface.copy(alpha = 0.75f)
            )
            val timeWindow = if (rule.activeTimeStart.isNotBlank() && rule.activeTimeEnd.isNotBlank()) {
                "${rule.activeTimeStart}-${rule.activeTimeEnd}"
            } else {
                tr("Anytime")
            }
            Text(
                text = "cooldown=${rule.cooldownMs / 1000}s, time_window=$timeWindow, record=${if (rule.actionRecordEnabled) "on" else "off"}",
                fontSize = 11.sp,
                color = scheme.onSurface.copy(alpha = 0.75f)
            )
            if (rule.packageList.isNotEmpty()) {
                Text(
                    text = "package_list=${rule.packageList.joinToString(", ").take(100)}",
                    fontSize = 11.sp,
                    color = scheme.onSurface.copy(alpha = 0.75f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "id=${rule.id.take(8)}...",
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
private fun PackageSelectField(
    title: String,
    selectedPackage: String,
    options: List<AppPackageOption>,
    onOpen: () -> Unit,
    onClear: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onOpen,
                modifier = Modifier.weight(1f)
            ) {
                val display = if (selectedPackage.isBlank()) {
                    tr("Select App")
                } else {
                    formatPackageDisplay(selectedPackage, options)
                }
                Text(display, maxLines = 1)
            }
            OutlinedButton(
                onClick = onClear,
                enabled = selectedPackage.isNotBlank()
            ) {
                Text(tr("Clear"), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun PackagePickerDialog(
    options: List<AppPackageOption>,
    onDismiss: () -> Unit,
    onSelect: (AppPackageOption) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(options, query) {
        val key = query.trim().lowercase(Locale.getDefault())
        if (key.isEmpty()) {
            options
        } else {
            options.filter { item ->
                item.packageName.lowercase(Locale.getDefault()).contains(key) ||
                        item.label.lowercase(Locale.getDefault()).contains(key)
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Select App")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(tr("Search apps")) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                if (filtered.isEmpty()) {
                    Text(
                        text = tr("No app found."),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                    ) {
                        itemsIndexed(filtered, key = { _, it -> it.packageName }) { index, item ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(item) }
                                    .padding(vertical = 8.dp)
                            ) {
                                Text(
                                    text = formatPackageDisplay(item.packageName, options),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            if (index < filtered.lastIndex) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(tr("Close"))
            }
        }
    )
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

private fun formatPackageDisplay(packageName: String, options: List<AppPackageOption>): String {
    if (packageName.isBlank()) return ""
    val row = options.firstOrNull { it.packageName == packageName }
    val label = row?.label?.trim().orEmpty()
    return if (label.isNotEmpty()) {
        "$label ($packageName)"
    } else {
        packageName
    }
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

private data class TracePrependAnchor(
    val count: Int,
    val index: Int,
    val offset: Int,
    val oldestSeq: Long
)

@Composable
fun LogsTab(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val traceLines by viewModel.traceLines.collectAsState()
    val traceHasMoreBefore by viewModel.traceHasMoreBefore.collectAsState()
    val traceLoadingOlder by viewModel.traceLoadingOlder.collectAsState()
    val listState = rememberLazyListState()
    var selectedTrace by remember { mutableStateOf<TraceEntry?>(null) }
    var initialScrollDone by rememberSaveable { mutableStateOf(false) }
    var prependAnchor by remember { mutableStateOf<TracePrependAnchor?>(null) }

    LaunchedEffect(Unit) {
        viewModel.refreshTraceTailOnDevice(silent = true, limit = 80)
    }

    LaunchedEffect(Unit) {
        while (true) {
            viewModel.pollNewerTraceOnDevice(silent = true, limit = 80)
            delay(2000L)
        }
    }

    LaunchedEffect(traceLines.size) {
        if (!initialScrollDone && traceLines.isNotEmpty()) {
            listState.scrollToItem(traceLines.lastIndex)
            initialScrollDone = true
            return@LaunchedEffect
        }
        val layout = listState.layoutInfo
        val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
        val isNearBottom = lastVisible >= traceLines.lastIndex - 2
        if (isNearBottom && traceLines.isNotEmpty()) {
            listState.animateScrollToItem(traceLines.lastIndex)
        }
    }

    LaunchedEffect(traceLines.size, traceLines.firstOrNull()?.seq, traceLoadingOlder) {
        val anchor = prependAnchor ?: return@LaunchedEffect
        val newOldestSeq = traceLines.firstOrNull()?.seq ?: 0L
        if (newOldestSeq < anchor.oldestSeq && traceLines.size > anchor.count) {
            val delta = traceLines.size - anchor.count
            listState.scrollToItem((anchor.index + delta).coerceAtLeast(0), anchor.offset)
            prependAnchor = null
        } else if (!traceLoadingOlder) {
            prependAnchor = null
        }
    }

    LaunchedEffect(listState.firstVisibleItemIndex, traceLines.size, traceHasMoreBefore, traceLoadingOlder) {
        if (traceLines.isEmpty()) return@LaunchedEffect
        if (traceHasMoreBefore && !traceLoadingOlder && listState.firstVisibleItemIndex <= 6) {
            prependAnchor = TracePrependAnchor(
                count = traceLines.size,
                index = listState.firstVisibleItemIndex,
                offset = listState.firstVisibleItemScrollOffset,
                oldestSeq = traceLines.firstOrNull()?.seq ?: 0L
            )
            viewModel.loadOlderTraceOnDevice(silent = true, limit = 80)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        LogPanel(
            traceLines = traceLines,
            listState = listState,
            showLoadingOlder = traceLoadingOlder || (traceHasMoreBefore && listState.firstVisibleItemIndex <= 6),
            onOpenTrace = { selectedTrace = it },
            modifier = Modifier.fillMaxSize()
        )
    }

    val detail = selectedTrace
    if (detail != null) {
        AlertDialog(
            onDismissRequest = { selectedTrace = null },
            title = { Text(tr("Trace Details")) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(detail.event, style = MaterialTheme.typography.titleSmall)
                            if (detail.summary.isNotBlank()) {
                                Text(detail.summary, fontSize = 12.sp)
                            }
                            if (detail.timestamp.isNotBlank()) {
                                Text("ts: ${detail.timestamp}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (detail.taskId.isNotBlank()) {
                                Text("task_id: ${detail.taskId}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (detail.meta.isNotEmpty()) {
                        TraceDetailSection(
                            title = tr("Key Fields"),
                            items = detail.meta
                        )
                    }
                    if (detail.fields.isNotEmpty()) {
                        TraceDetailSection(
                            title = tr("All Fields"),
                            items = detail.fields
                        )
                    } else if (detail.detail.isNotBlank()) {
                        Card {
                            Text(
                                detail.detail,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    } else {
                        Text(tr("No trace details."), fontSize = 11.sp)
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.04f))) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(tr("Raw Trace"), style = MaterialTheme.typography.labelMedium)
                            Text(detail.rawLine, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            },
            confirmButton = {
                OutlinedButton(onClick = { selectedTrace = null }) {
                    Text(tr("Close"))
                }
            }
        )
    }
}

@Composable
fun LogPanel(
    traceLines: List<TraceEntry>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    showLoadingOlder: Boolean,
    onOpenTrace: (TraceEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .padding(8.dp)
                .fillMaxSize()
        ) {
            Text(tr("Core Trace"), style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(4.dp))
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.05f))
            ) {
                if (showLoadingOlder) {
                    item(key = "loading_older") {
                        Text(
                            text = tr("Load older traces..."),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                        )
                    }
                }
                items(traceLines, key = { if (it.seq > 0L) it.seq else it.rawLine.hashCode().toLong() }) { entry ->
                    TraceCard(entry = entry, onClick = { onOpenTrace(entry) })
                }
            }
        }
    }
}

@Composable
private fun TraceCard(entry: TraceEntry, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val container = when {
        entry.isError -> scheme.errorContainer
        entry.event.startsWith("notify_") -> scheme.secondaryContainer
        entry.event == "fsm_state_enter" -> scheme.primaryContainer
        else -> scheme.surface
    }
    val content = when {
        entry.isError -> scheme.onErrorContainer
        entry.event.startsWith("notify_") -> scheme.onSecondaryContainer
        entry.event == "fsm_state_enter" -> scheme.onPrimaryContainer
        else -> scheme.onSurface
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = entry.event,
                    style = MaterialTheme.typography.bodyMedium,
                    color = content
                )
                if (entry.timestamp.isNotBlank()) {
                    Text(
                        text = entry.timestamp.takeLast(12),
                        fontSize = 10.sp,
                        color = content.copy(alpha = 0.75f)
                    )
                }
            }
            if (entry.summary.isNotBlank()) {
                Text(
                    text = entry.summary,
                    fontSize = 11.sp,
                    color = content.copy(alpha = 0.92f),
                    maxLines = 2
                )
            }
            if (entry.taskId.isNotBlank()) {
                Text(
                    text = "task=${entry.taskId.take(8)}...",
                    fontSize = 10.sp,
                    color = content.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun TraceDetailSection(title: String, items: List<TraceMetaItem>) {
    if (items.isEmpty()) return
    Card {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            items.forEach { item ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = item.label,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = item.value,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface
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
    val resolvedEndpoint = remember(llmBaseUrl) {
        runCatching { LlmClient.buildEndpointUrl(llmBaseUrl) }.getOrNull().orEmpty()
    }

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
                        tr("Endpoint is auto-completed to /chat/completions."),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                }
            )
            Text(
                text = "${tr("Resolved request URL")}: " +
                    if (resolvedEndpoint.isNotBlank()) resolvedEndpoint else tr("Input API Base URL to preview request endpoint."),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                fontSize = 12.sp
            )
            OutlinedTextField(
                value = llmApiKey,
                onValueChange = { viewModel.llmApiKey.value = it },
                label = { Text(tr("API Key")) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(),
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
    val installedApps by viewModel.installedAppList.collectAsState()
    var showMapPackagePicker by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshInstalledAppSnapshotOnDevice()
    }

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
            PackageSelectField(
                title = tr("Package (select from local snapshot)"),
                selectedPackage = mapTargetPackage,
                options = installedApps,
                onOpen = {
                    if (installedApps.isEmpty()) {
                        viewModel.refreshInstalledAppSnapshotOnDevice()
                    }
                    showMapPackagePicker = true
                },
                onClear = { viewModel.mapTargetPackage.value = "" }
            )
            Text(
                text = tr("Used for pull-by-identifier and active status."),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
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
    if (showMapPackagePicker) {
        PackagePickerDialog(
            options = installedApps,
            onDismiss = { showMapPackagePicker = false },
            onSelect = { item ->
                viewModel.mapTargetPackage.value = item.packageName
                showMapPackagePicker = false
            }
        )
    }
}
