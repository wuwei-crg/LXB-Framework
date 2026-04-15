package com.example.lxb_ignition

import android.Manifest
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
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
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.lxb_ignition.model.AppPackageOption
import com.example.lxb_ignition.model.CoreRuntimeStatus
import com.example.lxb_ignition.model.NotificationTriggerRuleSummary
import com.example.lxb_ignition.model.ScheduleSummary
import com.example.lxb_ignition.model.TaskMapDetail
import com.example.lxb_ignition.model.TaskMapSegmentSnapshot
import com.example.lxb_ignition.model.TaskMapSnapshot
import com.example.lxb_ignition.model.TaskMapStepSnapshot
import com.example.lxb_ignition.model.TaskRouteActionSnapshot
import com.example.lxb_ignition.model.TaskRouteRecordSnapshot
import com.example.lxb_ignition.model.TaskSummary
import com.example.lxb_ignition.model.TraceEntry
import com.example.lxb_ignition.model.TraceMetaItem
import com.example.lxb_ignition.model.TaskRuntimeUiStatus
import com.example.lxb_ignition.model.WirelessBootstrapStatus
import com.example.lxb_ignition.ui.theme.LXBIgnitionTheme
import com.example.lxb_ignition.ui.theme.AppError
import com.example.lxb_ignition.ui.theme.AppErrorSoft
import com.example.lxb_ignition.ui.theme.AppSuccess
import com.example.lxb_ignition.ui.theme.AppSuccessSoft
import com.example.lxb_ignition.ui.theme.AppWarning
import com.example.lxb_ignition.ui.theme.AppWarningSoft
import com.lxb.server.cortex.LlmClient
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    companion object {
        private const val ACTION_WIRELESS_DEBUGGING_SETTINGS = "android.settings.WIRELESS_DEBUGGING_SETTINGS"
    }

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

    private fun openSystemSettings(action: String): Boolean {
        return runCatching {
            startActivity(
                Intent(action).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            true
        }.getOrDefault(false)
    }

    private fun openDeveloperOptionsSettings() {
        val ok = openSystemSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        if (!ok) {
            Toast.makeText(
                this,
                "Failed to open Developer Options automatically.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun openWirelessDebuggingSettingsFromUi() {
        val ok = openSystemSettings(ACTION_WIRELESS_DEBUGGING_SETTINGS)
            || openSystemSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        if (!ok) {
            Toast.makeText(
                this,
                "Failed to open Wireless debugging settings automatically.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureNotificationPermissionOnLaunch()
        enableEdgeToEdge()
        setContent {
            LXBIgnitionTheme {
                LXBIgnitionApp(
                    onOpenDeveloperOptionsSettings = { openDeveloperOptionsSettings() },
                    onOpenWirelessDebuggingSettings = { openWirelessDebuggingSettingsFromUi() }
                )
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
fun LXBIgnitionApp(
    viewModel: MainViewModel = viewModel(),
    onOpenDeveloperOptionsSettings: () -> Unit = {},
    onOpenWirelessDebuggingSettings: () -> Unit = {}
) {
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
        val tabs = listOf(
            "Control" to "⌂",
            "Tasks" to "✓",
            "Config" to "⚙",
            "Logs" to "≡"
        )
        Scaffold(
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        actionIconContentColor = MaterialTheme.colorScheme.primary
                    ),
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
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp
                ) {
                    tabs.forEachIndexed { index, tab ->
                        val rawLabel = tab.first
                        val label = tr(rawLabel)
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                                unselectedTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
                            ),
                            icon = { Text(tab.second, fontSize = 18.sp) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        ) { innerPadding ->
            when (selectedTab) {
                0 -> ControlTab(
                    viewModel = viewModel,
                    modifier = Modifier.padding(innerPadding),
                    onOpenConfig = { selectedTab = 2 },
                    onOpenDeveloperOptionsSettings = onOpenDeveloperOptionsSettings,
                    onOpenWirelessDebuggingSettings = onOpenWirelessDebuggingSettings
                )
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
fun ControlTab(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    onOpenConfig: () -> Unit = {},
    onOpenDeveloperOptionsSettings: () -> Unit = {},
    onOpenWirelessDebuggingSettings: () -> Unit = {}
) {
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
            onOpenWirelessGuide = { page = 1 },
            onOpenRootGuide = { page = 2 },
            onStop = { viewModel.stopServerProcess() },
            onRefreshState = { viewModel.refreshCoreRuntimeStatusNow() },
            onOpenConfig = onOpenConfig
        )
        1 -> SingleConfigPage(
            title = tr("Wireless ADB startup"),
            modifier = modifier,
            onBack = { page = 0 }
        ) {
            WirelessGuidePage(
                status = wireless,
                wirelessDebuggingEnabled = wirelessDebuggingEnabled,
                onStartGuide = {
                    onOpenDeveloperOptionsSettings()
                    viewModel.startWirelessBootstrapGuide()
                },
                onStartDirect = { viewModel.startServerWithNative() },
                onOpenWirelessDebugging = onOpenWirelessDebuggingSettings,
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
    onOpenWirelessGuide: () -> Unit,
    onOpenRootGuide: () -> Unit,
    onStop: () -> Unit,
    onRefreshState: () -> Unit,
    onOpenConfig: () -> Unit
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        if (coreRuntime.ready) {
            CompactCoreStatusPanel(
                detail = coreRuntime.detail,
                onStop = onStop,
                onRefreshState = onRefreshState
            )
        } else {
            CompactStartupPanel(
                onOpenWirelessGuide = onOpenWirelessGuide,
                onOpenRootGuide = onOpenRootGuide
            )
        }

        InlineInfoStrip(
            glyph = "⚙",
            accentColor = MaterialTheme.colorScheme.primary,
            title = tr("First-time setup"),
            detail = tr("Before your first task, complete the model configuration once. After that, you can come back here and start core directly."),
            actionLabel = tr("Open model config"),
            onAction = onOpenConfig
        )

        SurfacePanel(
            modifier = Modifier.fillMaxWidth(),
            background = MaterialTheme.colorScheme.surface,
            borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SheetHeader(
                    title = tr("What you can do next"),
                    subtitle = tr("The app is built for direct tasks, timed automation, and notification-triggered automation.")
                )
                FeatureBullet(
                    title = tr("Direct task"),
                    detail = tr("Type a requirement and let the phone complete it for you."),
                    glyph = "⌨"
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.24f))
                FeatureBullet(
                    title = tr("Scheduled task"),
                    detail = tr("Run the same task later or on a recurring schedule."),
                    glyph = "⏰"
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.24f))
                FeatureBullet(
                    title = tr("Notification trigger"),
                    detail = tr("Listen to one app's notifications and fire a task automatically."),
                    glyph = "✉"
                )
            }
        }
    }
}

@Composable
private fun ProductSectionLabel(
    title: String,
    subtitle: String? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun ProductSectionCard(
    title: String,
    subtitle: String? = null,
    glyph: String = "•",
    content: @Composable () -> Unit
) {
    SurfacePanel(
        modifier = Modifier.fillMaxWidth(),
        background = MaterialTheme.colorScheme.surface,
        borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                GlyphBadge(
                    glyph = glyph,
                    accentColor = MaterialTheme.colorScheme.primary,
                    size = 36.dp
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ProductSectionLabel(title = title, subtitle = subtitle)
                    content()
                }
            }
        }
    }
}

@Composable
private fun FeatureBullet(title: String, detail: String, glyph: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        GlyphBadge(glyph = glyph, accentColor = MaterialTheme.colorScheme.primary, size = 34.dp)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = detail,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun ProductEntryCard(
    title: String,
    description: String,
    meta: String,
    glyph: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    SurfacePanel(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        background = MaterialTheme.colorScheme.surface,
        borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                GlyphBadge(glyph = glyph, accentColor = MaterialTheme.colorScheme.primary)
                StatusTag(text = meta, accentColor = MaterialTheme.colorScheme.primary)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = tr("Tap to open"),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
                Text(
                    text = "${tr("Open")} ›",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun SurfacePanel(
    modifier: Modifier = Modifier,
    background: Color,
    borderColor: Color,
    shape: Shape = RoundedCornerShape(24.dp),
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier,
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = background),
        border = BorderStroke(1.dp, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp)
    ) {
        content()
    }
}

@Composable
private fun PageActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        modifier = modifier.height(32.dp)
    ) {
        Text(text, fontSize = 12.sp)
    }
}

@Composable
private fun PageHeaderBlock(
    title: String,
    subtitle: String,
    glyph: String,
    onBack: (() -> Unit)? = null,
    primaryActionLabel: String? = null,
    onPrimaryAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null
) {
    val hasPrimaryAction = !primaryActionLabel.isNullOrBlank() && onPrimaryAction != null
    val hasSecondaryAction = !secondaryActionLabel.isNullOrBlank() && onSecondaryAction != null
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            if (onBack != null) {
                PageActionButton(text = tr("Back"), onClick = onBack)
            }
            GlyphBadge(
                glyph = glyph,
                accentColor = MaterialTheme.colorScheme.primary,
                size = 34.dp
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
                )
            }
        }
        if (hasPrimaryAction || hasSecondaryAction) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (hasPrimaryAction && hasSecondaryAction) Arrangement.spacedBy(8.dp) else Arrangement.End
            ) {
                if (hasPrimaryAction) {
                    PageActionButton(
                        text = primaryActionLabel!!,
                        onClick = onPrimaryAction!!,
                        modifier = if (hasSecondaryAction) Modifier.weight(1f) else Modifier
                    )
                }
                if (hasSecondaryAction) {
                    PageActionButton(
                        text = secondaryActionLabel!!,
                        onClick = onSecondaryAction!!,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryInfoStrip(
    glyph: String,
    title: String,
    primaryMetric: String,
    secondaryMetric: String,
    accentColor: Color = MaterialTheme.colorScheme.primary
) {
    val stripBackground = accentColor.copy(alpha = 0.10f).compositeOver(MaterialTheme.colorScheme.surface)
    SurfacePanel(
        modifier = Modifier.fillMaxWidth(),
        background = stripBackground,
        borderColor = accentColor.copy(alpha = 0.24f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlyphBadge(glyph = glyph, accentColor = accentColor, size = 32.dp)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                Text(
                    text = primaryMetric,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f)
                )
            }
            StatusTag(text = secondaryMetric, accentColor = accentColor)
        }
    }
}

@Composable
private fun GlyphBadge(
    glyph: String,
    accentColor: Color,
    size: androidx.compose.ui.unit.Dp = 40.dp
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(accentColor.copy(alpha = 0.12f))
            .border(BorderStroke(1.dp, accentColor.copy(alpha = 0.18f)), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = glyph,
            color = accentColor,
            fontSize = 16.sp
        )
    }
}

@Composable
private fun EmptyStateBox(title: String, detail: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        GlyphBadge(glyph = "○", accentColor = MaterialTheme.colorScheme.primary, size = 44.dp)
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = detail,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
        )
    }
}

@Composable
private fun StatusTag(text: String, accentColor: Color) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = accentColor.copy(alpha = 0.14f).compositeOver(MaterialTheme.colorScheme.surface)
        ),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.24f))
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            color = accentColor,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun PrimaryActionTile(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    glyph: String,
    accentColor: Color,
    onClick: () -> Unit
) {
    val tileBackground = accentColor.copy(alpha = 0.08f).compositeOver(MaterialTheme.colorScheme.surface)
    SurfacePanel(
        modifier = modifier.clickable(onClick = onClick),
        background = tileBackground,
        borderColor = accentColor.copy(alpha = 0.28f),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .height(132.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            GlyphBadge(glyph = glyph, accentColor = accentColor)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
                )
            }
        }
    }
}

@Composable
private fun InlineInfoStrip(
    glyph: String,
    accentColor: Color,
    title: String,
    detail: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    val stripBackground = accentColor.copy(alpha = 0.10f).compositeOver(MaterialTheme.colorScheme.surface)
    SurfacePanel(
        modifier = Modifier.fillMaxWidth(),
        background = stripBackground,
        borderColor = accentColor.copy(alpha = 0.24f),
        shape = RoundedCornerShape(26.dp)
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlyphBadge(glyph = glyph, accentColor = accentColor, size = 40.dp)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = detail,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                )
            }
            OutlinedButton(
                onClick = onAction,
                border = BorderStroke(1.dp, accentColor.copy(alpha = 0.28f))
            ) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun CompactCoreStatusPanel(
    detail: String,
    onStop: () -> Unit,
    onRefreshState: () -> Unit
) {
    SurfacePanel(
        modifier = Modifier.fillMaxWidth(),
        background = MaterialTheme.colorScheme.surface,
        borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlyphBadge(glyph = "✓", accentColor = AppSuccess, size = 34.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(tr("LXB Core is running"), style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = tr("Core is ready"),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
                        )
                    }
                }
                StatusTag(text = tr("Core Connected"), accentColor = AppSuccess)
            }
            Text(
                text = detail,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.76f)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onStop,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = AppError)
                ) {
                    Text(tr("Stop Core"))
                }
                OutlinedButton(
                    onClick = onRefreshState,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(tr("Refresh status"))
                }
            }
        }
    }
}

@Composable
private fun CompactStartupPanel(
    onOpenWirelessGuide: () -> Unit,
    onOpenRootGuide: () -> Unit
) {
    SurfacePanel(
        modifier = Modifier.fillMaxWidth(),
        background = MaterialTheme.colorScheme.surface,
        borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlyphBadge(glyph = "⚡", accentColor = MaterialTheme.colorScheme.primary, size = 34.dp)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(tr("Start LXB Core in one tap"), style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = tr("Choose the startup method that matches your phone. Root is the shortest path, and Wireless ADB is the easiest path for most phones."),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PrimaryActionTile(
                    modifier = Modifier.weight(1f),
                    title = tr("ADB start"),
                    subtitle = tr("Recommended for most phones"),
                    glyph = "⌁",
                    accentColor = MaterialTheme.colorScheme.primary,
                    onClick = onOpenWirelessGuide
                )
                PrimaryActionTile(
                    modifier = Modifier.weight(1f),
                    title = tr("Root start"),
                    subtitle = tr("For rooted phones"),
                    glyph = "#",
                    accentColor = MaterialTheme.colorScheme.tertiary,
                    onClick = onOpenRootGuide
                )
            }
            Text(
                text = tr("Detailed steps stay inside each startup page."),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
            )
        }
    }
}

@Composable
private fun CompactGuideStatusPanel(
    eyebrow: String,
    title: String,
    detail: String,
    accentColor: Color,
    glyph: String,
    statusText: String
) {
    SurfacePanel(
        modifier = Modifier.fillMaxWidth(),
        background = MaterialTheme.colorScheme.surface,
        borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlyphBadge(glyph = glyph, accentColor = accentColor, size = 34.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = eyebrow,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
                        )
                    }
                }
                StatusTag(text = statusText, accentColor = accentColor)
            }
            Text(
                text = detail,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.76f)
            )
        }
    }
}

@Composable
private fun SheetHeader(
    title: String,
    subtitle: String? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f)
            )
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
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        CompactGuideStatusPanel(
            eyebrow = tr("Recommended for most phones"),
            title = tr("Wireless ADB startup"),
            detail = ui.detail,
            accentColor = ui.accentColor,
            glyph = "⌁",
            statusText = tr(ui.headline)
        )

        SurfacePanel(
            modifier = Modifier.fillMaxWidth(),
            background = MaterialTheme.colorScheme.surface,
            borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SheetHeader(
                    title = tr("Follow these steps"),
                    subtitle = tr("Stay on this page while you complete the steps on your phone.")
                )
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

        SurfacePanel(
            modifier = Modifier.fillMaxWidth(),
            background = MaterialTheme.colorScheme.primaryContainer,
            borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SheetHeader(
                    title = tr("Actions"),
                    subtitle = tr("Use the first button for first-time setup. If you already paired before, you can try starting directly.")
                )
                Button(onClick = onStartGuide, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("Open Developer Options (Start Guide)"))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onStartDirect,
                        modifier = Modifier.weight(1f),
                        enabled = wirelessDebuggingEnabled
                    ) {
                        Text(tr("I already paired before, start directly"))
                    }
                    OutlinedButton(
                        onClick = onRefreshState,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(tr("Refresh connection state"))
                    }
                }
                if (!wirelessDebuggingEnabled) {
                    Text(
                        tr("Please make sure Wireless debugging is turned on first."),
                        color = AppWarning,
                        fontSize = 12.sp
                    )
                    OutlinedButton(onClick = onOpenWirelessDebugging, modifier = Modifier.fillMaxWidth()) {
                        Text(tr("Open Wireless debugging settings"))
                    }
                }
            }
        }

        SurfacePanel(
            modifier = Modifier.fillMaxWidth(),
            background = MaterialTheme.colorScheme.surface,
            borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SheetHeader(
                    title = tr("Need help?"),
                    subtitle = tr("These are the most common ROM-specific requirements before startup can succeed.")
                )
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
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
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
    val accent = when {
        done -> AppSuccess
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.48f)
    }
    val background = when {
        done -> AppSuccessSoft
        active -> MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    SurfacePanel(
        modifier = Modifier.fillMaxWidth(),
        background = background,
        borderColor = accent.copy(alpha = 0.18f),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            GlyphBadge(glyph = number.toString(), accentColor = accent, size = 34.dp)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Text(title, color = accent, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    if (done) {
                        StatusTag(text = tr("Done"), accentColor = AppSuccess)
                    } else if (active) {
                        StatusTag(text = tr("Current status"), accentColor = MaterialTheme.colorScheme.primary)
                    }
                }
                Text(
                    detail,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                    fontSize = 12.sp
                )
            }
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
    val accent = if (rootAvailable) AppSuccess else AppWarning
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        CompactGuideStatusPanel(
            eyebrow = tr("For rooted phones"),
            title = tr("Root startup"),
            detail = rootDetail,
            accentColor = accent,
            glyph = "#",
            statusText = if (rootAvailable) tr("Root available") else tr("Root unavailable")
        )

        SurfacePanel(
            modifier = Modifier.fillMaxWidth(),
            background = if (coreRuntime.ready) AppSuccessSoft else AppWarningSoft,
            borderColor = (if (coreRuntime.ready) AppSuccess else AppWarning).copy(alpha = 0.20f)
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlyphBadge(
                    glyph = if (coreRuntime.ready) "✓" else "!",
                    accentColor = if (coreRuntime.ready) AppSuccess else AppWarning,
                    size = 32.dp
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "${tr("State")}: ${if (coreRuntime.ready) tr("Core Connected") else tr("Core Disconnected")}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = if (coreRuntime.ready) tr("LXB Core is running") else tr("Get started"),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f)
                    )
                }
            }
        }

        SurfacePanel(
            modifier = Modifier.fillMaxWidth(),
            background = MaterialTheme.colorScheme.surface,
            borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SheetHeader(
                    title = tr("Before you start"),
                    subtitle = tr("Root startup is shorter, but it still depends on root permission being granted in time.")
                )
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

        SurfacePanel(
            modifier = Modifier.fillMaxWidth(),
            background = if (rootAvailable) AppSuccessSoft else AppWarningSoft,
            borderColor = accent.copy(alpha = 0.20f)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SheetHeader(
                    title = tr("Actions"),
                    subtitle = tr("Use root startup only after you confirm root is available on this phone.")
                )
                Button(
                    onClick = onStartRoot,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = rootAvailable
                ) {
                    Text(tr("Start via Root"))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(onClick = onRefreshRoot, modifier = Modifier.weight(1f)) {
                        Text(tr("Check Root"))
                    }
                    OutlinedButton(onClick = onRefreshState, modifier = Modifier.weight(1f)) {
                        Text(tr("Refresh connection state"))
                    }
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

    SurfacePanel(
        modifier = modifier.fillMaxWidth(),
        background = MaterialTheme.colorScheme.surface,
        borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f),
        shape = RoundedCornerShape(28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SurfacePanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                background = MaterialTheme.colorScheme.surfaceVariant,
                borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.20f),
                shape = RoundedCornerShape(22.dp)
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (chatMessages.isEmpty()) {
                        item {
                            Text(
                                text = tr("Your recent task conversation will appear here after you run a task."),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                            )
                        }
                    } else {
                        items(chatMessages) { msg ->
                            ChatBubble(message = msg)
                        }
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = requirement,
                    onValueChange = { viewModel.requirement.value = it },
                    label = { Text(tr("Describe what you want to do")) },
                    modifier = Modifier.weight(1f),
                    maxLines = 3
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.runRequirementOnDevice() },
                        modifier = Modifier.height(46.dp)
                    ) {
                        Text(tr("Run"))
                    }
                }
            }
        }
    }
}

@Composable
fun TaskRuntimeStatusCard(
    status: TaskRuntimeUiStatus,
    modifier: Modifier = Modifier,
    onStop: (() -> Unit)? = null
) {
    val accentColor = if (status.running) AppSuccess else MaterialTheme.colorScheme.primary
    val background = if (status.running) AppSuccessSoft else MaterialTheme.colorScheme.surfaceVariant
    SurfacePanel(
        modifier = modifier.fillMaxWidth(),
        background = background,
        borderColor = accentColor.copy(alpha = 0.18f),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlyphBadge(
                        glyph = if (status.running) "▶" else "○",
                        accentColor = accentColor,
                        size = 34.dp
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(tr("Task Runtime"), style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = if (status.running) tr("A task is currently running on the phone.") else tr("No task is running right now."),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                        )
                    }
                }
                StatusTag(text = if (status.running) tr("RUNNING") else tr("IDLE"), accentColor = accentColor)
            }
            Text(
                text = "${tr("Current task")}: ${if (status.taskId.isNotEmpty()) status.taskId.take(8) + "..." else "-"}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f)
            )
            Text(
                text = "${tr("Phase")}: ${status.phase}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f)
            )
            Text(
                text = if (status.running) status.detail else tr("No task is running."),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
            )
            if (status.running && onStop != null) {
                OutlinedButton(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(tr("Stop"))
                }
            }
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
    "Inspect live core traces, open structured details, and export the full trace when you need to debug." to "查看实时 Core 追踪、打开结构化详情，并在排查问题时导出完整追踪。",
    "Trace stream" to "追踪流",
    "Exported" to "已导出",
    "Scroll for older" to "上滑加载更早记录",
    "Live" to "实时",
    "Latest traces stay at the bottom. Scroll upward to load older ones." to "最新追踪会停留在底部，向上滚动即可加载更早记录。",
    "Export" to "导出",
    "Exporting..." to "导出中...",
    "Exporting trace..." to "正在导出追踪...",
    "Trace exported" to "追踪已导出",
    "Trace export failed" to "追踪导出失败",
    "No trace to export." to "没有可导出的追踪。",
    "Saved to" to "已保存到",
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
    "Manage scheduled tasks and create new ones." to "管理定时任务并创建新任务。",
    "View recent execution records." to "查看最近执行记录。",
    "No runs yet. Submit a task from Task Session or wait for schedules." to "暂无执行记录。可在任务会话中提交任务，或等待定时任务执行。",
    "Schedules" to "定时任务",
    "Notification Triggers" to "通知触发任务",
    "Recent Runs" to "最近执行",
    "Schedule overview" to "定时任务概览",
    "Trigger overview" to "通知规则概览",
    "Run history" to "执行记录概览",
    "No schedules yet." to "暂无定时任务。",
    "Edit Schedule" to "编辑定时任务",
    "Create Schedule" to "新建定时任务",
    "Run time" to "运行时间",
    "Repeat" to "重复",
    "Once" to "单次",
    "Daily" to "每天",
    "Weekly" to "每周",
    "Mon" to "周一",
    "Tue" to "周二",
    "Wed" to "周三",
    "Thu" to "周四",
    "Fri" to "周五",
    "Sat" to "周六",
    "Sun" to "周日",
    "Selected days" to "已选日期",
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
    "Close" to "关闭",
    "New" to "新建",
    "Refresh" to "刷新",
    "Task Details" to "任务详情",
    "Task route detail" to "任务路线详情",
    "Task route key" to "任务路线键",
    "Task route mode" to "按路线执行",
    "No task summary available yet." to "暂时没有任务摘要。",
    "Loading task route details..." to "正在加载任务路线详情...",
    "Task ID" to "任务 ID",
    "Timestamp" to "时间",
    "Task" to "任务",
    "Reason" to "原因",
    "Target page" to "目标页面",
    "Source" to "来源",
    "Source ID" to "来源 ID",
    "Schedule ID" to "定时任务 ID",
    "Memory applied" to "已应用记忆",
    "Record enabled" to "已开启录屏",
    "Record file" to "录屏文件",
    "Created at" to "创建时间",
    "Finished at" to "结束时间",
    "Saved task route" to "已保存任务路线",
    "Latest success trace" to "最近成功轨迹",
    "Latest attempt trace" to "最近尝试轨迹",
    "Delete task route" to "删除任务路线",
    "Task Route Editor" to "任务路线编辑",
    "Open task route editor" to "打开任务路线编辑页",
    "Task route editing is available after the task config has been saved once." to "任务配置至少保存一次后，才能编辑对应的任务路线。",
    "No task route data yet." to "暂时还没有任务路线数据。",
    "Tap a card to view full details." to "点击卡片查看完整详情。",
    "Review the latest captured path, delete noisy actions, and save only the useful route." to "查看最近一次记录到的路径，删除噪声动作，只保留有用路线。",
    "Editable Trace" to "可编辑轨迹",
    "The newest captured trace is editable, even if the task did not finish successfully." to "最近一次记录到的轨迹都可以编辑，即使任务最终没有成功完成。",
    "No captured trace yet." to "暂时还没有可编辑的轨迹。",
    "Summary" to "摘要",
    "Task route target" to "任务路线目标",
    "Refresh detail" to "刷新详情",
    "Saved Route" to "已保存路线",
    "No saved route yet." to "暂时还没有已保存路线。",
    "Latest Success Trace" to "最近成功轨迹",
    "Latest Attempt Trace" to "最近尝试轨迹",
    "No latest success trace yet." to "暂时还没有最近成功轨迹。",
    "No latest attempt trace yet." to "暂时还没有最近尝试轨迹。",
    "Task description" to "任务描述",
    "Root task" to "根任务",
    "Created from task" to "来源任务",
    "Last replay status" to "最近回放状态",
    "Segment" to "片段",
    "Segment ID" to "片段 ID",
    "Sub-task ID" to "子任务 ID",
    "Sub-task index" to "子任务序号",
    "Success criteria" to "成功条件",
    "Inputs" to "输入",
    "Outputs" to "输出",
    "Step" to "步骤",
    "Step ID" to "步骤 ID",
    "Source action ID" to "来源动作 ID",
    "Action" to "动作",
    "Action ID" to "动作 ID",
    "Operation" to "操作",
    "Arguments" to "参数",
    "Fallback point" to "兜底坐标",
    "Semantic note" to "语义备注",
    "Expected result" to "预期结果",
    "Locator" to "定位器",
    "Vision fields" to "视觉字段",
    "Turn" to "轮次",
    "Command" to "指令",
    "Page semantics" to "页面语义",
    "Execution result" to "执行结果",
    "Execution error" to "执行错误",
    "Selected deletions" to "已选删除项",
    "Delete from saved route" to "从已保存路线中删除",
    "Save manual route" to "手动保存路线",
    "Saving..." to "保存中...",
    "Finish after replay" to "回放结束后直接结束任务",
    "If enabled, a successful task-route replay skips VISION_ACT and finishes the current sub-task directly." to "开启后，路线回放成功将直接结束当前任务，不再进入后续视觉执行。",
    "No trace actions yet." to "暂时还没有轨迹动作。",
    "App label" to "应用名称",
    "Status" to "状态",
    "Final state" to "最终状态",
    "Next run" to "下次运行",
    "Triggered count" to "触发次数",
    "Record" to "录屏",
    "Cooldown" to "冷却",
    "Time window" to "生效时段",
    "Package list" to "包名列表",
    "Priority" to "优先级",
    "ID" to "ID",
    "Yes" to "是",
    "No" to "否",
    "On" to "开",
    "Off" to "关",
    "(no task)" to "（无任务）",
    "(no task description)" to "（无任务描述）",
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
    "COMPLETED" to "已完成",
    "CANCELLED" to "已取消",
    "FAILED" to "失败",
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
    "Current setup" to "当前设置概览",
    "Recommended first" to "建议先配这些",
    "Most people only need model and control settings before starting tasks." to "大多数人开始用之前，只需要先配模型和操控方式。",
    "If taps, swipes, or typing are not behaving well on your phone, adjust them here." to "如果点击、滑动或输入在你的手机上表现不好，就来这里调整。",
    "Ready" to "已就绪",
    "Needs sync" to "待同步",
    "Device behavior" to "设备行为",
    "Language, unlock, and other phone-side behavior live here." to "语言、解锁以及其他手机侧行为都在这里。",
    "Map routing" to "地图路由",
    "Route asset source, sync, and identifier pull controls." to "路线资产来源、同步以及按标识拉取，都在这里管理。",
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
    "Open" to "打开",
    "Tap to open" to "点击进入",
    "Done" to "完成",
    "Endpoint is auto-completed to /chat/completions." to "会自动补齐为 /chat/completions 端点。",
    "Resolved request URL" to "真实调用 URL",
    "Input API Base URL to preview request endpoint." to "输入 API Base URL 后，这里会实时显示最终请求地址。",
    "lxb-core server" to "lxb-core 服务",
    "TCP port" to "TCP 端口",
    "TCP port listened by lxb-core on device (default 12345)" to "设备端 lxb-core 监听的 TCP 端口（默认 12345）",
    "Controls native start endpoint and click/swipe compatibility path." to "配置原生启动端点与点击/滑动兼容路径。",
    "Configure touch injection mode and compatibility." to "配置触摸注入模式与兼容策略。",
    "Choose how touch, typing, and task-time phone behavior should work on this device." to "选择这个设备上的触控、输入以及任务期间手机行为应如何工作。",
    "Set the model endpoint used for device-side planning and visual understanding." to "配置设备侧用于规划与视觉理解的模型端点。",
    "Control how the phone is unlocked before a task and locked again afterward." to "控制任务开始前如何解锁，以及结束后如何重新锁屏。",
    "Manage route assets, active map lane, and pull-by-identifier actions." to "管理路线资产、当前地图轨道以及按标识拉取相关操作。",
    "Device control" to "设备操控",
    "Connection" to "连接",
    "Device-side core connection settings." to "设备侧 Core 连接相关设置。",
    "Touch input mode" to "触摸注入模式",
    "Choose the injection strategy used for taps and swipes." to "选择点击与滑动所使用的注入策略。",
    "ADB Keyboard is recommended because it gives the most reliable input experience." to "推荐使用 ADB Keyboard，因为它通常能提供最稳定的输入体验。",
    "Task-time Do Not Disturb" to "任务期间免打扰",
    "Choose what happens to DND automatically when a task begins." to "选择任务开始时要自动如何处理免打扰。",
    "Apply changes" to "应用更改",
    "Push the current control configuration to core now, or only save it locally." to "现在就把当前操控配置推送到 Core，或者只保存在本地。",
    "Touch mode profile" to "触摸策略档位",
    "Choose preferred injection pipeline for tap/swipe/long press." to "为点击/滑动/长按选择优先注入链路。",
    "Compatibility mode (Shell first)" to "兼容模式（Shell 优先）",
    "Precision mode (UiAutomation first)" to "精细模式（UiAutomation 优先）",
    "Policy applied when a task starts." to "任务启动时应用此策略。",
    "Keep current DND state" to "不调整免打扰（保持当前状态）",
    "Set DND to OFF (allow notifications)" to "设置任务期 DND=OFF（允许提醒）",
    "Set DND to NONE (silence all)" to "设置任务期 DND=NONE（全部静音）",
    "Shell" to "Shell",
    "allowlist" to "允许列表",
    "blocklist" to "屏蔽列表",
    "contains" to "包含",
    "regex" to "正则",
    "any" to "任意",
    "UIAutomator" to "UIAutomator",
    "How taps/swipes are injected to the device." to "控制点击/滑动如何注入到设备。",
    "Text input mode" to "文字输入方式",
    "Recommended: install ADB Keyboard. If detected, core will switch to it only while typing, then restore your original keyboard automatically." to "推荐安装 ADB Keyboard。检测到后，core 会只在输入文字时临时切换到它，输入完再自动切回你原来的输入法。",
    "If ADB Keyboard is not detected, fallback input will still work, but some apps may fail to accept Chinese text." to "如果没检测到 ADB Keyboard，兜底输入仍然可用，但有些应用里中文输入可能会失败。",
    "Check ADB Keyboard" to "检查 ADB Keyboard",
    "Detected" to "已检测到",
    "Not detected" to "未检测到",
    "Download ADB Keyboard" to "下载 ADB Keyboard",
    "Current keyboard" to "当前输入法",
    "Auto-switch input channel is ready." to "自动切换输入通道已就绪。",
    "Core will use fallback text input for now." to "当前将使用兜底文字输入方案。",
    "Shell input first" to "Shell 优先",
    "UiAutomation first" to "UiAutomation 优先",
    "Apply to core" to "应用到 Core",
    "API Base URL" to "API Base URL",
    "API Key" to "API Key",
    "Model" to "模型",
    "On" to "开",
    "Please use a model with image recognition capability." to "请使用具有图像识别能力的模型。",
    "e.g. gpt-4o-mini, qwen-plus" to "例如：gpt-4o-mini、qwen-plus",
    "Model routing" to "模型路由",
    "No model selected" to "尚未选择模型",
    "Draft only" to "仅草稿",
    "Base endpoint, API key, and model used for device-side requests." to "配置设备侧请求所使用的基础端点、API Key 与模型。",
    "Save reusable model presets locally and switch between them quickly." to "将可复用的模型预设保存到本地，并快速切换。",
    "Test LLM & sync to device" to "测试 LLM 并同步到设备",
    "Save only" to "仅保存",
    "Save all config only" to "仅保存（保存所有配置）",
    "Saved config" to "已保存配置",
    "Saved local configs" to "本地已保存配置",
    "Config name" to "配置名称",
    "Save as new" to "另存为新配置",
    "Update selected" to "更新当前选中配置",
    "No saved LLM configs yet." to "还没有保存过 LLM 配置。",
    "Use this config" to "使用这套配置",
    "Selected config" to "当前选中配置",
    "Please enter a config name first." to "请先输入配置名称。",
    "Please select a saved config first." to "请先选择一套已保存配置。",
    "Selected config was not found." to "当前选中的配置不存在。",
    "Saved new LLM config: " to "已保存新的 LLM 配置：",
    "Updated LLM config: " to "已更新 LLM 配置：",
    "Loaded LLM config: " to "已加载 LLM 配置：",
    "Deleted LLM config: " to "已删除 LLM 配置：",
    "Auto unlock before route" to "路由前自动解锁",
    "Check screen state and unlock before app launch/routing." to "在应用启动/路由前检查屏幕状态并执行解锁。",
    "Auto lock after task" to "任务后自动锁屏",
    "Lock screen when task ends if the FSM unlocked it." to "若由 FSM 解锁，任务结束后自动锁屏。",
    "Unlock policy" to "解锁策略",
    "Unlock before route is ON" to "路由前自动解锁：开启",
    "Unlock before route is OFF" to "路由前自动解锁：关闭",
    "Auto lock ON" to "自动锁屏：开启",
    "Auto lock OFF" to "自动锁屏：关闭",
    "Behavior" to "行为设置",
    "These switches control whether the phone unlocks before a task and locks after it." to "这些开关控制任务前是否自动解锁，以及任务后是否自动锁屏。",
    "Lockscreen credentials" to "锁屏凭据",
    "Only needed when swipe unlock alone is not enough." to "只有上滑解锁不够用时才需要填写。",
    "Sync the latest unlock settings to device now, or only save them locally." to "现在就把最新解锁设置同步到设备，或者只保存在本地。",
    "Unlock PIN / password" to "解锁 PIN / 密码",
    "Used only when swipe unlock is not enough." to "仅在上滑解锁不足时使用。",
    "Sync to device" to "同步到设备",
    "Map sync & lane control" to "地图同步与轨道控制",
    "Map repo raw base URL" to "地图仓库 Raw 基础链接",
    "Use map routing" to "使用 Map 路由",
    "Use map routing is ON" to "地图路由：开启",
    "Use map routing is OFF" to "地图路由：关闭",
    "Source & repository" to "来源与仓库",
    "Choose where maps come from and which runtime lane stays active." to "选择地图的来源，以及运行时保持生效的轨道。",
    "e.g. https://raw.githubusercontent.com/wuwei-crg/LXB-MapRepo/main" to "例如：https://raw.githubusercontent.com/wuwei-crg/LXB-MapRepo/main",
    "Identifier pull" to "按标识拉取",
    "Pick the app and map identifier used for pull-by-ID and active-map checks." to "选择用于按 ID 拉取和检查当前生效地图的应用与地图标识。",
    "Sync or pull route assets, inspect the active map, and save the current settings." to "同步或拉取路线资产、查看当前生效地图，并保存当前设置。",
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
    "Show active" to "查看当前生效",
    "Quick task" to "快速任务",
    "Describe what you want the phone to do right now. This is the fastest way to try the product." to "描述你现在想让手机做什么。这是体验产品最快的方式。",
    "Your recent task conversation will appear here after you run a task." to "运行任务后，最近一次任务对话会显示在这里。",
    "A task is currently running on the phone." to "当前有任务正在手机上执行。",
    "No task is running right now." to "当前没有正在执行的任务。",
    "Automation" to "自动化",
    "Recent results" to "最近结果",
    "Run a task now, manage automation, or review recent results." to "立即执行任务、管理自动化，或查看最近结果。",
    "Build repeatable workflows after you are comfortable with direct tasks." to "熟悉直接任务后，再把常用流程做成自动化。",
    "items" to "项",
    "Repeat a task later or keep it running on a schedule." to "让同一任务稍后执行，或按计划重复执行。",
    "Watch one app's notifications and launch a task when a rule matches." to "监听某个应用的通知，并在命中规则时自动执行任务。",
    "Review what just ran, what failed, and what route data was captured." to "查看刚刚执行了什么、哪里失败了，以及沉淀了哪些路线数据。",
    "Review recent task runs, failures, and learned routes." to "查看最近任务执行、失败情况，以及路线数据。",
    "Create one when you want the phone to repeat the same task automatically." to "当你希望手机自动重复执行同一任务时，在这里创建。",
    "Create one after you know which app and message pattern should trigger the task." to "当你确定要监听哪个应用、哪类消息时，在这里创建。",
    "Submit a quick task, or wait for a schedule or notification trigger to fire." to "先提交一次快速任务，或等待定时任务或通知触发任务执行。",
    "No runs yet." to "暂无运行记录。",
    "Enabled" to "已启用",
    "Paused" to "已暂停",
    "App" to "应用",
    "Listening app" to "监听应用",
    "Match" to "匹配条件",
    "Trigger interval" to "触发间隔",
    "(not set)" to "（未设置）",
    "Get started" to "开始使用",
    "Core is ready" to "核心已就绪",
    "LXB Core is running" to "LXB Core 正在运行",
    "Start LXB Core in one tap" to "一键启动 LXB Core",
    "Choose the startup method that matches your phone. Root is the shortest path, and Wireless ADB is the easiest path for most phones." to "选择适合你手机的启动方式。Root 路径最短，Wireless ADB 更适合大多数非 Root 手机。",
    "Stop Core" to "停止 Core",
    "Refresh status" to "刷新状态",
    "First-time setup" to "首次使用准备",
    "Before your first task, complete the model configuration once. After that, you can come back here and start core directly." to "首次使用前，请先完成一次模型配置。之后就可以回到这里直接启动 core。",
    "Open model config" to "打开模型配置",
    "What you can do next" to "接下来你可以做什么",
    "The app is built for direct tasks, timed automation, and notification-triggered automation." to "这个应用主要支持直接任务、定时自动化和通知触发自动化。",
    "Direct task" to "直接任务",
    "Type a requirement and let the phone complete it for you." to "输入一句需求，让手机自己完成操作。",
    "Open the quick task page when you want to tell the phone what to do right now." to "如果你现在就想告诉手机去做什么，就从这里进入快速任务页。",
    "Scheduled task" to "定时任务",
    "Run the same task later or on a recurring schedule." to "让同一个任务稍后执行，或按周期重复执行。",
    "Notification trigger" to "通知触发任务",
    "Listen to one app's notifications and fire a task automatically." to "监听某个应用的通知，并自动触发任务。",
    "Detailed steps stay inside each startup page." to "详细步骤保留在各自的启动页面中。",
    "Current status" to "当前状态",
    "Idle" to "空闲",
    "Recommended for most phones" to "适合大多数手机",
    "Follow these steps" to "按下面步骤操作",
    "Stay on this page while you complete the steps on your phone." to "在手机上完成步骤时，请保持本页面打开。",
    "Use the first button for first-time setup. If you already paired before, you can try starting directly." to "首次使用请先点第一个按钮。如果之前已经配对过，可以尝试直接启动。",
    "These are the most common ROM-specific requirements before startup can succeed." to "以下是最常见的 ROM 额外要求，满足后启动更容易成功。",
    "For rooted phones" to "适用于已 Root 手机",
    "Root startup is shorter, but it still depends on root permission being granted in time." to "Root 启动路径更短，但仍依赖系统及时授予 root 权限。",
    "Use root startup only after you confirm root is available on this phone." to "请在确认本机 root 可用后，再使用 root 启动。",
    "Set what to do, when to run it, and which app it should open." to "设置要做什么、什么时候执行，以及需要先打开哪个应用。",
    "Basic info" to "基本信息",
    "Name the rule and describe what the phone should do." to "给规则命名，并描述手机要执行的任务。",
    "Execution target" to "执行目标",
    "Choose when the task runs and which app it should open first." to "选择任务的执行时间，以及它应先打开哪个应用。",
    "Execution preference" to "执行偏好",
    "These settings affect recording and manual route review for this schedule." to "这些设置会影响该定时任务的录屏和手动路线审查。",
    "Save schedule" to "保存定时任务",
    "Your schedule will appear in the list after it is saved." to "保存后，这条定时任务会出现在列表中。",
    "Set which notifications to listen for, then define what task should run." to "先设置监听哪些通知，再定义命中后执行什么任务。",
    "Name the rule and describe the task that should run after a match." to "给规则命名，并描述命中后要执行的任务。",
    "Trigger conditions" to "触发条件",
    "The rule checks package first, then title/body matching, then optional LLM condition." to "规则会先检查包名，再检查标题/正文匹配，最后再看可选的 LLM 条件。",
    "These settings affect manual route review and recording after the trigger is matched." to "这些设置会影响规则命中后的手动路线审查和录屏行为。",
    "Save trigger" to "保存触发规则",
    "The rule will appear in the notification trigger list after it is saved." to "保存后，这条规则会出现在通知触发任务列表中。",
    "This page edits the route asset for one exact task only." to "这个页面只编辑某一个精确任务对应的路线资产。",
    "Replay behavior" to "回放结束后直接结束任务",
    "Choose whether a successful replay should finish the current sub-task immediately." to "控制路线回放成功后，是否直接结束当前任务。"
)

private data class RouteEditorTarget(
    val title: String,
    val source: String,
    val sourceId: String,
    val packageName: String = "",
    val userTask: String = "",
    val userPlaybook: String = "",
    val mode: String = ""
)

// Tab 2: Tasks

@Composable
fun TasksTab(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val tasks by viewModel.taskList.collectAsState()
    val taskMapDetail by viewModel.taskMapDetail.collectAsState()
    val taskMapDetailLoading by viewModel.taskMapDetailLoading.collectAsState()
    val taskMapSaving by viewModel.taskMapSaving.collectAsState()
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
    val scheduleTaskMapMode by viewModel.scheduleTaskMapMode.collectAsState()
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
    val notifyActionTaskMapMode by viewModel.notifyActionTaskMapMode.collectAsState()
    val notifyActionUseMapMode by viewModel.notifyActionUseMapMode.collectAsState()
    var page by rememberSaveable { mutableIntStateOf(0) }
    var editingScheduleId by rememberSaveable { mutableStateOf("") }
    var editingNotifyRuleId by rememberSaveable { mutableStateOf("") }
    var selectedTask by remember { mutableStateOf<TaskSummary?>(null) }
    var routeEditorTarget by remember { mutableStateOf<RouteEditorTarget?>(null) }

    val pageHome = 0
    val pageScheduleList = 1
    val pageNotifyRuleList = 2
    val pageRecentRuns = 3
    val pageScheduleForm = 4
    val pageNotifyRuleForm = 5
    val pageTaskRouteEditor = 6
    val pageQuickTask = 7

    LaunchedEffect(Unit) {
        viewModel.refreshInstalledAppSnapshotOnDevice()
        viewModel.refreshScheduleListOnDevice()
        viewModel.refreshNotifyRuleListOnDevice()
        viewModel.refreshTaskListOnDevice()
    }

    LaunchedEffect(selectedTask?.taskId, selectedTask?.taskKeyHash) {
        val task = selectedTask
        if (task == null) {
            viewModel.clearTaskMapDetail()
        } else {
            viewModel.loadTaskMapDetail(task)
        }
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
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                PageHeaderBlock(
                    title = tr("Tasks"),
                    subtitle = tr("Run a task now, manage automation, or review recent results."),
                    glyph = "☰",
                    primaryActionLabel = tr("Refresh All"),
                    onPrimaryAction = {
                        viewModel.refreshInstalledAppSnapshotOnDevice()
                        viewModel.refreshScheduleListOnDevice()
                        viewModel.refreshNotifyRuleListOnDevice()
                        viewModel.refreshTaskListOnDevice()
                    }
                )

                TaskRuntimeStatusCard(
                    status = taskRuntime,
                    onStop = { viewModel.cancelCurrentTaskOnDevice() }
                )

                SheetHeader(
                    title = tr("Direct task"),
                    subtitle = tr("Open the quick task page when you want to tell the phone what to do right now.")
                )
                ProductEntryCard(
                    title = tr("Quick task"),
                    description = tr("Describe what you want the phone to do right now. This is the fastest way to try the product."),
                    meta = tr("Direct task"),
                    glyph = "⌨",
                    onClick = { page = pageQuickTask }
                )

                SheetHeader(
                    title = tr("Automation"),
                    subtitle = tr("Build repeatable workflows after you are comfortable with direct tasks.")
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ProductEntryCard(
                        title = tr("Schedules"),
                        description = tr("Manage scheduled tasks and create new ones."),
                        meta = "${schedules.size} ${tr("items")}",
                        glyph = "⏰",
                        onClick = { page = pageScheduleList },
                        modifier = Modifier.weight(1f)
                    )
                    ProductEntryCard(
                        title = tr("Notification Triggers"),
                        description = tr("Manage notification-triggered tasks and create new ones."),
                        meta = "${notifyRules.size} ${tr("items")}",
                        glyph = "✉",
                        onClick = { page = pageNotifyRuleList },
                        modifier = Modifier.weight(1f)
                    )
                }
                SheetHeader(
                    title = tr("Recent results"),
                    subtitle = tr("Review recent task runs, failures, and learned routes.")
                )
                ProductEntryCard(
                    title = tr("Recent Runs"),
                    description = tr("View recent execution records."),
                    meta = "${tasks.size} ${tr("items")}",
                    glyph = "☰",
                    onClick = { page = pageRecentRuns }
                )
            }
        }

        pageQuickTask -> {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                PageHeaderBlock(
                    title = tr("Quick task"),
                    subtitle = tr("Describe what you want the phone to do right now. This is the fastest way to try the product."),
                    glyph = "⌨",
                    onBack = { page = pageHome }
                )
                TaskSessionCard(viewModel = viewModel)
            }
        }

        pageScheduleList -> {
            val scheduleListState = rememberLazyListState()
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                PageHeaderBlock(
                    title = tr("Schedules"),
                    subtitle = tr("Repeat a task later or keep it running on a schedule."),
                    glyph = "⏰",
                    onBack = { page = pageHome },
                    primaryActionLabel = tr("New"),
                    onPrimaryAction = {
                        editingScheduleId = ""
                        viewModel.resetScheduleForm()
                        page = pageScheduleForm
                    },
                    secondaryActionLabel = tr("Refresh"),
                    onSecondaryAction = { viewModel.refreshScheduleListOnDevice() }
                )

                SummaryInfoStrip(
                    glyph = "⏰",
                    title = tr("Schedule overview"),
                    primaryMetric = "${tr("items")}: ${schedules.size}",
                    secondaryMetric = "${tr("Enabled")}: ${schedules.count { it.enabled }}"
                )

                SurfacePanel(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    background = MaterialTheme.colorScheme.surface,
                    borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f)
                ) {
                    if (schedules.isEmpty()) {
                        EmptyStateBox(
                            title = tr("No schedules yet."),
                            detail = tr("Create one when you want the phone to repeat the same task automatically.")
                        )
                    } else {
                        LazyColumn(
                            state = scheduleListState,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            itemsIndexed(schedules, key = { _, it -> it.scheduleId }) { index, schedule ->
                                ScheduleRow(
                                    schedule = schedule,
                                    onToggleEnabled = { checked ->
                                        viewModel.toggleScheduleEnabledOnDevice(schedule, checked)
                                    },
                                    onEdit = {
                                        editingScheduleId = schedule.scheduleId
                                        viewModel.loadScheduleForm(schedule)
                                        page = pageScheduleForm
                                    },
                                    onDelete = { viewModel.removeScheduleOnDevice(schedule.scheduleId) }
                                )
                                if (index < schedules.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 18.dp),
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
                                    )
                                }
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
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                PageHeaderBlock(
                    title = tr("Notification Triggers"),
                    subtitle = tr("Watch one app's notifications and launch a task when a rule matches."),
                    glyph = "✉",
                    onBack = { page = pageHome },
                    primaryActionLabel = tr("New"),
                    onPrimaryAction = {
                        editingNotifyRuleId = ""
                        viewModel.resetNotifyRuleForm()
                        page = pageNotifyRuleForm
                    },
                    secondaryActionLabel = tr("Refresh"),
                    onSecondaryAction = { viewModel.refreshNotifyRuleListOnDevice() }
                )

                SummaryInfoStrip(
                    glyph = "✉",
                    title = tr("Trigger overview"),
                    primaryMetric = "${tr("items")}: ${notifyRules.size}",
                    secondaryMetric = "${tr("Enabled")}: ${notifyRules.count { it.enabled }}"
                )

                SurfacePanel(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    background = MaterialTheme.colorScheme.surface,
                    borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f)
                ) {
                    if (notifyRules.isEmpty()) {
                        EmptyStateBox(
                            title = tr("No notification triggers yet."),
                            detail = tr("Create one after you know which app and message pattern should trigger the task.")
                        )
                    } else {
                        LazyColumn(
                            state = notifyListState,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            itemsIndexed(notifyRules, key = { _, it -> it.id }) { index, rule ->
                                NotificationRuleRow(
                                    rule = rule,
                                    onToggleEnabled = { checked ->
                                        viewModel.toggleNotifyRuleEnabledOnDevice(rule, checked)
                                    },
                                    onEdit = {
                                        editingNotifyRuleId = rule.id
                                        viewModel.loadNotifyRuleForm(rule)
                                        page = pageNotifyRuleForm
                                    },
                                    onDelete = { viewModel.removeNotifyRuleOnDevice(rule.id) }
                                )
                                if (index < notifyRules.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 18.dp),
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
                                    )
                                }
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
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                PageHeaderBlock(
                    title = tr("Recent Runs"),
                    subtitle = tr("Review what just ran, what failed, and what route data was captured."),
                    glyph = "☰",
                    onBack = { page = pageHome },
                    primaryActionLabel = tr("Refresh"),
                    onPrimaryAction = { viewModel.refreshTaskListOnDevice() }
                )

                SummaryInfoStrip(
                    glyph = "☰",
                    title = tr("Run history"),
                    primaryMetric = "${tr("items")}: ${tasks.size}",
                    secondaryMetric = "${tr("FAILED")}: ${tasks.count { it.state == "FAILED" }}",
                    accentColor = if (tasks.any { it.state == "FAILED" }) AppWarning else MaterialTheme.colorScheme.primary
                )

                SurfacePanel(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    background = MaterialTheme.colorScheme.surface,
                    borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f)
                ) {
                    if (tasks.isEmpty()) {
                        EmptyStateBox(
                            title = tr("No runs yet."),
                            detail = tr("Submit a quick task, or wait for a schedule or notification trigger to fire.")
                        )
                    } else {
                        LazyColumn(
                            state = taskListState,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            itemsIndexed(tasks, key = { _, it -> it.taskId }) { index, task ->
                                TaskRow(task = task, onClick = { selectedTask = task })
                                if (index < tasks.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 18.dp),
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
                                    )
                                }
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
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                PageHeaderBlock(
                    title = if (isEditing) tr("Edit Schedule") else tr("Create Schedule"),
                    subtitle = tr("Set what to do, when to run it, and which app it should open."),
                    glyph = "⏰",
                    onBack = {
                        page = pageScheduleList
                        editingScheduleId = ""
                    }
                )

                SurfacePanel(
                    modifier = Modifier.fillMaxWidth(),
                    background = MaterialTheme.colorScheme.surface,
                    borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        SheetHeader(
                            title = tr("Basic info"),
                            subtitle = tr("Name the rule and describe what the phone should do.")
                        )
                        OutlinedTextField(
                            value = scheduleName,
                            onValueChange = { viewModel.scheduleName.value = it },
                            label = { Text(tr("Name (optional)")) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = scheduleTask,
                            onValueChange = { viewModel.scheduleTask.value = it },
                            label = { Text(tr("Task description (required)")) },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 3
                        )
                        OutlinedTextField(
                            value = schedulePlaybook,
                            onValueChange = { viewModel.schedulePlaybook.value = it },
                            label = { Text(tr("User playbook (optional)")) },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 4
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.20f))

                        SheetHeader(
                            title = tr("Execution target"),
                            subtitle = tr("Choose when the task runs and which app it should open first.")
                        )
                        Text(
                            text = "${tr("Run time")}: ${formatTsFull(selectedRunAt)}",
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
                                onClick = { showTimeWheel = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(tr("Pick Time"))
                            }
                        }
                        Text(
                            text = tr("Repeat"),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            RepeatModeButton(
                                text = tr("Once"),
                                selected = scheduleRepeatMode == MainViewModel.REPEAT_ONCE,
                                onClick = { viewModel.scheduleRepeatMode.value = MainViewModel.REPEAT_ONCE },
                                modifier = Modifier.weight(1f)
                            )
                            RepeatModeButton(
                                text = tr("Daily"),
                                selected = scheduleRepeatMode == MainViewModel.REPEAT_DAILY,
                                onClick = { viewModel.scheduleRepeatMode.value = MainViewModel.REPEAT_DAILY },
                                modifier = Modifier.weight(1f)
                            )
                            RepeatModeButton(
                                text = tr("Weekly"),
                                selected = scheduleRepeatMode == MainViewModel.REPEAT_WEEKLY,
                                onClick = { viewModel.scheduleRepeatMode.value = MainViewModel.REPEAT_WEEKLY },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (scheduleRepeatMode == MainViewModel.REPEAT_WEEKLY) {
                            val labels = listOf(
                                tr("Mon"),
                                tr("Tue"),
                                tr("Wed"),
                                tr("Thu"),
                                tr("Fri"),
                                tr("Sat"),
                                tr("Sun")
                            )
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
                                text = "${tr("Selected days")}: ${formatWeekdayMask(scheduleRepeatWeekdays)}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                            )
                        }
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

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.20f))

                        SheetHeader(
                            title = tr("Execution preference"),
                            subtitle = tr("These settings affect recording and manual route review for this schedule.")
                        )
                        Text(
                            text = tr("Task route mode"),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            RepeatModeButton(
                                text = tr("Off"),
                                selected = scheduleTaskMapMode == MainViewModel.TASK_MAP_MODE_OFF,
                                onClick = { viewModel.scheduleTaskMapMode.value = MainViewModel.TASK_MAP_MODE_OFF },
                                modifier = Modifier.weight(1f)
                            )
                            RepeatModeButton(
                                text = tr("On"),
                                selected = scheduleTaskMapMode == MainViewModel.TASK_MAP_MODE_MANUAL,
                                onClick = { viewModel.scheduleTaskMapMode.value = MainViewModel.TASK_MAP_MODE_MANUAL },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (isEditing) {
                            OutlinedButton(
                                onClick = {
                                    routeEditorTarget = RouteEditorTarget(
                                        title = if (scheduleName.isNotBlank()) scheduleName else scheduleTask,
                                        source = "schedule",
                                        sourceId = editingScheduleId,
                                        packageName = schedulePackage,
                                        mode = scheduleTaskMapMode
                                    )
                                    page = pageTaskRouteEditor
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(tr("Open task route editor"))
                            }
                        } else {
                            Text(
                                text = tr("Task route editing is available after the task config has been saved once."),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
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
                    }
                }

                SurfacePanel(
                    modifier = Modifier.fillMaxWidth(),
                    background = MaterialTheme.colorScheme.primaryContainer,
                    borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SheetHeader(
                            title = tr("Save schedule"),
                            subtitle = tr("Your schedule will appear in the list after it is saved.")
                        )
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
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                PageHeaderBlock(
                    title = tr(if (isEditing) "Edit Notification Trigger" else "Create Notification Trigger"),
                    subtitle = tr("Set which notifications to listen for, then define what task should run."),
                    glyph = "✉",
                    onBack = {
                        page = pageNotifyRuleList
                        editingNotifyRuleId = ""
                    }
                )

                SurfacePanel(
                    modifier = Modifier.fillMaxWidth(),
                    background = MaterialTheme.colorScheme.surface,
                    borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        SheetHeader(
                            title = tr("Basic info"),
                            subtitle = tr("Name the rule and describe the task that should run after a match.")
                        )
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

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.20f))

                        SheetHeader(
                            title = tr("Trigger conditions"),
                            subtitle = tr("The rule checks package first, then title/body matching, then optional LLM condition.")
                        )
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

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.20f))

                        SheetHeader(
                            title = tr("Execution preference"),
                            subtitle = tr("These settings affect manual route review and recording after the trigger is matched.")
                        )
                        Text(
                            text = tr("Task route mode"),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            RepeatModeButton(
                                text = tr("Off"),
                                selected = notifyActionTaskMapMode == MainViewModel.TASK_MAP_MODE_OFF,
                                onClick = { viewModel.notifyActionTaskMapMode.value = MainViewModel.TASK_MAP_MODE_OFF },
                                modifier = Modifier.weight(1f)
                            )
                            RepeatModeButton(
                                text = tr("On"),
                                selected = notifyActionTaskMapMode == MainViewModel.TASK_MAP_MODE_MANUAL,
                                onClick = { viewModel.notifyActionTaskMapMode.value = MainViewModel.TASK_MAP_MODE_MANUAL },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (isEditing) {
                            OutlinedButton(
                                onClick = {
                                    routeEditorTarget = RouteEditorTarget(
                                        title = if (notifyName.isNotBlank()) notifyName else notifyActionUserTask,
                                        source = "notify_trigger",
                                        sourceId = editingNotifyRuleId,
                                        packageName = notifyActionPackage,
                                        mode = notifyActionTaskMapMode
                                    )
                                    page = pageTaskRouteEditor
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(tr("Open task route editor"))
                            }
                        } else {
                            Text(
                                text = tr("Task route editing is available after the task config has been saved once."),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
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
                    }
                }

                SurfacePanel(
                    modifier = Modifier.fillMaxWidth(),
                    background = MaterialTheme.colorScheme.primaryContainer,
                    borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SheetHeader(
                            title = tr("Save trigger"),
                            subtitle = tr("The rule will appear in the notification trigger list after it is saved.")
                        )
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

        pageTaskRouteEditor -> {
            val target = routeEditorTarget
            LaunchedEffect(target?.source, target?.sourceId, target?.packageName, target?.userTask, target?.userPlaybook, target?.mode) {
                if (target != null) {
                    viewModel.clearTaskMapDetail()
                    viewModel.loadTaskMapDetailByQuery(
                        source = target.source,
                        sourceId = target.sourceId,
                        packageName = target.packageName,
                        userTask = target.userTask,
                        userPlaybook = target.userPlaybook,
                        mode = target.mode
                    )
                }
            }
            if (target == null) {
                Column(
                    modifier = modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = { page = pageHome }) {
                        Text(tr("Back"))
                    }
                    Text(
                        text = tr("No task route data yet."),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            } else {
                TaskRouteEditorPage(
                    modifier = modifier,
                    target = target,
                    routeDetail = taskMapDetail,
                    loading = taskMapDetailLoading,
                    saving = taskMapSaving,
                    onBack = {
                        page = when (target.source) {
                            "schedule" -> pageScheduleForm
                            "notify_trigger" -> pageNotifyRuleForm
                            else -> pageHome
                        }
                    },
                    onRefresh = {
                        viewModel.loadTaskMapDetailByQuery(
                            source = target.source,
                            sourceId = target.sourceId,
                            packageName = target.packageName,
                            userTask = target.userTask,
                            userPlaybook = target.userPlaybook,
                            mode = target.mode
                        )
                    },
                    onDeleteTaskMap = {
                        val key = taskMapDetail?.taskKeyHash.orEmpty()
                        viewModel.deleteTaskMapByQuery(
                            taskKeyHash = key,
                            source = target.source,
                            sourceId = target.sourceId,
                            packageName = target.packageName,
                            userTask = target.userTask,
                            userPlaybook = target.userPlaybook,
                            mode = target.mode
                        ) {
                            viewModel.loadTaskMapDetailByQuery(
                                source = target.source,
                                sourceId = target.sourceId,
                                packageName = target.packageName,
                                userTask = target.userTask,
                                userPlaybook = target.userPlaybook,
                                mode = target.mode
                            )
                        }
                    },
                    onSaveManualTaskMap = { deleteIds, finishAfterReplay ->
                        val key = taskMapDetail?.taskKeyHash.orEmpty()
                        viewModel.saveManualTaskMapByKey(
                            taskKeyHash = key,
                            deleteActionIds = deleteIds,
                            finishAfterReplay = finishAfterReplay
                        ) {
                            viewModel.loadTaskMapDetailByQuery(
                                source = target.source,
                                sourceId = target.sourceId,
                                packageName = target.packageName,
                                userTask = target.userTask,
                                userPlaybook = target.userPlaybook,
                                mode = target.mode
                            )
                        }
                    }
                )
            }
        }
    }

    val detail = selectedTask
    if (detail != null) {
        TaskDetailDialog(
            task = detail,
            routeDetail = taskMapDetail,
            loading = taskMapDetailLoading,
            onDismiss = { selectedTask = null },
            onRefresh = { viewModel.loadTaskMapDetail(detail) }
        )
    }
}

@Composable
private fun TaskDetailDialog(
    task: TaskSummary,
    routeDetail: TaskMapDetail?,
    loading: Boolean,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit
) {
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Task Details")) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TaskSummarySection(task = task)
                if (loading) {
                    Text(
                        text = tr("Loading task route details..."),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                    )
                }
                val effectiveDetail = routeDetail
                if (effectiveDetail != null) {
                    TaskMapMetaSection(detail = effectiveDetail)
                    SavedTaskMapSection(taskMap = effectiveDetail.taskMap)
                    TaskRouteRecordSection(
                        title = tr("Latest Success Trace"),
                        emptyText = tr("No latest success trace yet."),
                        record = effectiveDetail.latestSuccessRecord,
                        editable = false,
                        saving = false,
                        selectedDeleteIds = emptySet(),
                        onToggleDelete = {},
                        onSaveManual = {}
                    )
                    if (effectiveDetail.latestAttemptRecord != null) {
                        TaskRouteRecordSection(
                            title = tr("Latest Attempt Trace"),
                            emptyText = tr("No latest attempt trace yet."),
                            record = effectiveDetail.latestAttemptRecord,
                            editable = false,
                            saving = false,
                            selectedDeleteIds = emptySet(),
                            onToggleDelete = {},
                            onSaveManual = {}
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRefresh) {
                    Text(tr("Refresh detail"))
                }
                OutlinedButton(onClick = onDismiss) {
                    Text(tr("Close"))
                }
            }
        }
    )
}

@Composable
private fun TaskRouteEditorPage(
    modifier: Modifier = Modifier,
    target: RouteEditorTarget,
    routeDetail: TaskMapDetail?,
    loading: Boolean,
    saving: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onDeleteTaskMap: () -> Unit,
    onSaveManualTaskMap: (List<String>, Boolean) -> Unit
) {
    val scrollState = rememberScrollState()
    val editableRecord = routeDetail?.latestAttemptRecord ?: routeDetail?.latestSuccessRecord
    val showSeparateSuccessRecord = routeDetail?.latestSuccessRecord != null &&
        !isSameTaskRouteRecord(routeDetail.latestSuccessRecord, editableRecord)
    var deleteActionIds by remember(editableRecord?.createdAtMs, editableRecord?.actions?.size) {
        mutableStateOf(setOf<String>())
    }
    var finishAfterReplay by remember(routeDetail?.taskMap?.createdAtMs, routeDetail?.taskMap?.finishAfterReplay) {
        mutableStateOf(routeDetail?.taskMap?.finishAfterReplay ?: false)
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onBack,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Text(tr("Back"), fontSize = 12.sp)
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = tr("Task Route Editor"),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = tr("Review the latest captured path, delete noisy actions, and save only the useful route."),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                )
            }
            OutlinedButton(
                onClick = onRefresh,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Text(tr("Refresh"), fontSize = 12.sp)
            }
        }

        SurfacePanel(
            modifier = Modifier.fillMaxWidth(),
            background = MaterialTheme.colorScheme.primaryContainer,
            borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SheetHeader(
                    title = tr("Task route target"),
                    subtitle = tr("This page edits the route asset for one exact task only.")
                )
                DetailTextLine(tr("Summary"), target.title.ifBlank { tr("(no task description)") })
                if (target.source.isNotBlank()) DetailTextLine(tr("Source"), target.source)
                if (target.sourceId.isNotBlank()) DetailTextLine(tr("Source ID"), target.sourceId)
                if (target.packageName.isNotBlank()) DetailTextLine(tr("Package name"), target.packageName)
                if (target.mode.isNotBlank()) DetailTextLine(tr("Task route mode"), formatTaskRouteMode(target.mode))
                Text(
                    text = tr("Tap a card to view full details."),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }

        SurfacePanel(
            modifier = Modifier.fillMaxWidth(),
            background = MaterialTheme.colorScheme.surface,
            borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f)
        ) {
            Row(
                modifier = Modifier.padding(18.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(tr("Replay behavior"), style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = tr("If enabled, a successful task-route replay skips VISION_ACT and finishes the current sub-task directly."),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                Switch(
                    checked = finishAfterReplay,
                    onCheckedChange = { finishAfterReplay = it }
                )
            }
        }

        if (loading) {
            Text(
                text = tr("Loading task route details..."),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
            )
        }
        if (!loading && routeDetail == null) {
            Text(
                text = tr("No task route data yet."),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
            )
        }
        if (routeDetail != null) {
            TaskMapMetaSection(detail = routeDetail)
            SavedTaskMapSection(taskMap = routeDetail.taskMap)
            TaskRouteRecordSection(
                title = tr("Editable Trace"),
                emptyText = tr("No captured trace yet."),
                record = editableRecord,
                editable = true,
                saving = saving,
                selectedDeleteIds = deleteActionIds,
                onToggleDelete = { actionId ->
                    deleteActionIds = if (deleteActionIds.contains(actionId)) {
                        deleteActionIds - actionId
                    } else {
                        deleteActionIds + actionId
                    }
                },
                onSaveManual = { onSaveManualTaskMap(deleteActionIds.toList(), finishAfterReplay) }
            )
            Text(
                text = tr("The newest captured trace is editable, even if the task did not finish successfully."),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f)
            )
            if (showSeparateSuccessRecord) {
                TaskRouteRecordSection(
                    title = tr("Latest Success Trace"),
                    emptyText = tr("No latest success trace yet."),
                    record = routeDetail.latestSuccessRecord,
                    editable = false,
                    saving = false,
                    selectedDeleteIds = emptySet(),
                    onToggleDelete = {},
                    onSaveManual = {}
                )
            }
            if (routeDetail.hasMap) {
                SurfacePanel(
                    modifier = Modifier.fillMaxWidth(),
                    background = AppErrorSoft,
                    borderColor = AppError.copy(alpha = 0.18f)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(tr("Delete task route"), style = MaterialTheme.typography.titleSmall, color = AppError)
                        OutlinedButton(onClick = onDeleteTaskMap, modifier = Modifier.fillMaxWidth()) {
                            Text(tr("Delete task route"))
                        }
                    }
                }
            }
        }
    }
}

private fun isSameTaskRouteRecord(
    left: TaskRouteRecordSnapshot?,
    right: TaskRouteRecordSnapshot?
): Boolean {
    if (left == null || right == null) return false
    if (left === right) return true
    return left.taskId == right.taskId &&
        left.createdAtMs == right.createdAtMs &&
        left.actions.size == right.actions.size
}

@Composable
private fun TaskSummarySection(task: TaskSummary) {
    Card {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = if (task.taskSummary.isNotBlank()) task.taskSummary else tr("No task summary available yet."),
                style = MaterialTheme.typography.bodyMedium
            )
            DetailTextLine(tr("Task ID"), task.taskId)
            DetailTextLine(
                tr("State"),
                "${tr(task.state)}" + if (task.finalState.isNotBlank()) " / ${tr(task.finalState)}" else ""
            )
            if (task.reason.isNotBlank()) {
                Text(
                    text = "${tr("Reason")}=${task.reason}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (task.userTask.isNotBlank()) DetailTextLine(tr("Task"), task.userTask)
            if (task.packageName.isNotBlank()) DetailTextLine(tr("Package name"), task.packageName)
            if (task.targetPage.isNotBlank()) DetailTextLine(tr("Target page"), task.targetPage)
            if (task.source.isNotBlank()) DetailTextLine(tr("Source"), task.source)
            if (task.taskMapMode.isNotBlank()) DetailTextLine(tr("Task route mode"), formatTaskRouteMode(task.taskMapMode))
            DetailTextLine(tr("Saved task route"), if (task.hasTaskMap) tr("Yes") else tr("No"))
            if (task.scheduleId.isNotBlank()) DetailTextLine(tr("Schedule ID"), task.scheduleId)
            DetailTextLine(tr("Memory applied"), if (task.memoryApplied) tr("Yes") else tr("No"))
            DetailTextLine(tr("Record enabled"), if (task.recordEnabled) tr("Yes") else tr("No"))
            if (task.recordFile.isNotBlank()) DetailTextLine(tr("Record file"), task.recordFile)
            if (task.createdAt > 0L) DetailTextLine(tr("Created at"), formatTsFull(task.createdAt))
            if (task.finishedAt > 0L) DetailTextLine(tr("Finished at"), formatTsFull(task.finishedAt))
        }
    }
}

@Composable
private fun TaskMapMetaSection(detail: TaskMapDetail) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(tr("Task route detail"), style = MaterialTheme.typography.labelLarge)
            if (detail.taskKeyHash.isNotBlank()) DetailTextLine(tr("Task route key"), detail.taskKeyHash)
            if (detail.mode.isNotBlank()) DetailTextLine(tr("Task route mode"), formatTaskRouteMode(detail.mode))
            if (detail.source.isNotBlank()) DetailTextLine(tr("Source"), detail.source)
            if (detail.sourceId.isNotBlank()) DetailTextLine(tr("Source ID"), detail.sourceId)
            if (detail.packageName.isNotBlank()) DetailTextLine(tr("Package name"), detail.packageName)
            if (detail.userTask.isNotBlank()) DetailTextLine(tr("Task"), detail.userTask)
            DetailTextLine(tr("Saved task route"), if (detail.hasMap) tr("Yes") else tr("No"))
            DetailTextLine(tr("Finish after replay"), if (detail.taskMap?.finishAfterReplay == true) tr("Yes") else tr("No"))
            DetailTextLine(tr("Latest success trace"), if (detail.hasLatestSuccessRecord) tr("Yes") else tr("No"))
            DetailTextLine(tr("Latest attempt trace"), if (detail.hasLatestAttemptRecord) tr("Yes") else tr("No"))
        }
    }
}

@Composable
private fun SavedTaskMapSection(taskMap: TaskMapSnapshot?) {
    Card {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(tr("Saved Route"), style = MaterialTheme.typography.titleSmall)
            if (taskMap == null) {
                Text(tr("No saved route yet."), fontSize = 12.sp)
                return@Column
            }
            if (taskMap.packageName.isNotBlank()) DetailTextLine(tr("Package name"), taskMap.packageName)
            if (taskMap.packageLabel.isNotBlank()) DetailTextLine(tr("App label"), taskMap.packageLabel)
            if (taskMap.createdFromTaskId.isNotBlank()) DetailTextLine(tr("Created from task"), taskMap.createdFromTaskId)
            if (taskMap.createdAtMs > 0L) DetailTextLine(tr("Created at"), formatTsFull(taskMap.createdAtMs))
            if (taskMap.lastReplayStatus.isNotBlank()) DetailTextLine(tr("Last replay status"), taskMap.lastReplayStatus)
            DetailTextLine(tr("Finish after replay"), if (taskMap.finishAfterReplay) tr("Yes") else tr("No"))
            taskMap.segments.forEachIndexed { index, segment ->
                TaskMapSegmentCard(index = index, segment = segment)
            }
        }
    }
}

@Composable
private fun TaskMapSegmentCard(index: Int, segment: TaskMapSegmentSnapshot) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("${tr("Segment")} ${index + 1}", style = MaterialTheme.typography.labelLarge)
            if (segment.segmentId.isNotBlank()) DetailTextLine(tr("Segment ID"), segment.segmentId)
            if (segment.subTaskId.isNotBlank()) DetailTextLine(tr("Sub-task ID"), segment.subTaskId)
            DetailTextLine(tr("Sub-task index"), segment.subTaskIndex.toString())
            if (segment.subTaskDescription.isNotBlank()) DetailTextLine(tr("Task description"), segment.subTaskDescription)
            if (segment.successCriteria.isNotBlank()) DetailTextLine(tr("Success criteria"), segment.successCriteria)
            if (segment.packageName.isNotBlank()) DetailTextLine(tr("Package name"), segment.packageName)
            if (segment.packageLabel.isNotBlank()) DetailTextLine(tr("App label"), segment.packageLabel)
            if (segment.inputs.isNotEmpty()) DetailTextLine(tr("Inputs"), segment.inputs.joinToString(", "))
            if (segment.outputs.isNotEmpty()) DetailTextLine(tr("Outputs"), segment.outputs.joinToString(", "))
            segment.steps.forEachIndexed { stepIndex, step ->
                TaskMapStepCard(index = stepIndex, step = step)
            }
        }
    }
}

@Composable
private fun TaskMapStepCard(index: Int, step: TaskMapStepSnapshot) {
    Card {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("${tr("Step")} ${index + 1}", style = MaterialTheme.typography.labelMedium)
            if (step.stepId.isNotBlank()) DetailTextLine(tr("Step ID"), step.stepId)
            if (step.sourceActionId.isNotBlank()) DetailTextLine(tr("Source action ID"), step.sourceActionId)
            if (step.op.isNotBlank()) DetailTextLine(tr("Operation"), step.op)
            if (step.args.isNotEmpty()) DetailTextLine(tr("Arguments"), step.args.joinToString(" "))
            if (step.fallbackPoint.isNotBlank()) DetailTextLine(tr("Fallback point"), step.fallbackPoint)
            if (step.semanticNote.isNotBlank()) DetailTextLine(tr("Semantic note"), step.semanticNote)
            if (step.expected.isNotBlank()) DetailTextLine(tr("Expected result"), step.expected)
            if (step.locatorFields.isNotEmpty()) {
                TraceDetailSection(
                    title = tr("Locator"),
                    items = step.locatorFields
                )
            }
        }
    }
}

@Composable
private fun TaskRouteRecordSection(
    title: String,
    emptyText: String,
    record: TaskRouteRecordSnapshot?,
    editable: Boolean,
    saving: Boolean,
    selectedDeleteIds: Set<String>,
    onToggleDelete: (String) -> Unit,
    onSaveManual: () -> Unit
) {
    var selectedAction by remember(record?.createdAtMs, record?.actions?.size) {
        mutableStateOf<TaskRouteActionSnapshot?>(null)
    }
    Card {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (record == null) {
                Text(emptyText, fontSize = 12.sp)
                return@Column
            }
            if (record.taskId.isNotBlank()) DetailTextLine(tr("Task ID"), record.taskId)
            if (record.rootTask.isNotBlank()) DetailTextLine(tr("Root task"), record.rootTask)
            if (record.packageName.isNotBlank()) DetailTextLine(tr("Package name"), record.packageName)
            if (record.packageLabel.isNotBlank()) DetailTextLine(tr("App label"), record.packageLabel)
            if (record.createdAtMs > 0L) DetailTextLine(tr("Created at"), formatTsFull(record.createdAtMs))
            if (record.status.isNotBlank()) DetailTextLine(tr("Status"), record.status)
            if (record.finalState.isNotBlank()) DetailTextLine(tr("Final state"), record.finalState)
            if (record.reason.isNotBlank()) DetailTextLine(tr("Reason"), record.reason)
            if (editable) {
                DetailTextLine(tr("Selected deletions"), selectedDeleteIds.size.toString())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onSaveManual,
                        enabled = !saving && record.actions.isNotEmpty()
                    ) {
                        Text(if (saving) tr("Saving...") else tr("Save manual route"))
                    }
                }
            }
            if (record.actions.isEmpty()) {
                Text(tr("No trace actions yet."), fontSize = 12.sp)
            } else {
                record.actions.forEachIndexed { index, action ->
                    TaskRouteActionCard(
                        index = index,
                        action = action,
                        editable = editable,
                        checked = selectedDeleteIds.contains(action.actionId),
                        onToggleDelete = { onToggleDelete(action.actionId) },
                        onOpenDetail = { selectedAction = action }
                    )
                }
            }
        }
    }
    selectedAction?.let { action ->
        TaskRouteActionDetailDialog(
            action = action,
            onDismiss = { selectedAction = null }
        )
    }
}

@Composable
private fun TaskRouteActionCard(
    index: Int,
    action: TaskRouteActionSnapshot,
    editable: Boolean,
    checked: Boolean,
    onToggleDelete: () -> Unit,
    onOpenDetail: () -> Unit
) {
    val summary = when {
        action.createdPageSemantics.isNotBlank() -> action.createdPageSemantics
        action.rawCommand.isNotBlank() -> action.rawCommand
        action.args.isNotEmpty() -> action.args.joinToString(" ")
        else -> action.op
    }
    val accentColor = if (action.execError.isNotBlank()) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    SurfacePanel(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenDetail() },
        background = MaterialTheme.colorScheme.surface,
        borderColor = accentColor.copy(alpha = 0.18f)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            GlyphBadge(
                glyph = when (action.op.uppercase(Locale.ROOT)) {
                    "TAP" -> "⌖"
                    "SWIPE" -> "↕"
                    "INPUT" -> "⌨"
                    "BACK" -> "←"
                    else -> "•"
                },
                accentColor = accentColor
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${tr("Action")} ${index + 1}", style = MaterialTheme.typography.labelLarge)
                    if (editable) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { onToggleDelete() }
                            )
                            Text(tr("Delete from saved route"), fontSize = 12.sp)
                        }
                    }
                }
                Text(
                    text = "${tr("Operation")}=${action.op.ifBlank { "-" }}  •  ${tr("Turn")}=${action.turn}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )
                if (action.execResult.isNotBlank() || action.execError.isNotBlank()) {
                    Text(
                        text = if (action.execError.isNotBlank()) {
                            "${tr("Execution error")}=${action.execError}"
                        } else {
                            "${tr("Execution result")}=${action.execResult}"
                        },
                        fontSize = 11.sp,
                        color = if (action.execError.isNotBlank()) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                        }
                    )
                }
                Text(
                    text = "${tr("Summary")}=${summary.take(160)}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
                )
            }
        }
    }
}

@Composable
private fun TaskRouteActionDetailDialog(
    action: TaskRouteActionSnapshot,
    onDismiss: () -> Unit
) {
    val scrollState = rememberScrollState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Action")) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (action.actionId.isNotBlank()) DetailTextLine(tr("Action ID"), action.actionId)
                if (action.subTaskId.isNotBlank()) DetailTextLine(tr("Sub-task ID"), action.subTaskId)
                DetailTextLine(tr("Turn"), action.turn.toString())
                if (action.op.isNotBlank()) DetailTextLine(tr("Operation"), action.op)
                if (action.args.isNotEmpty()) DetailTextLine(tr("Arguments"), action.args.joinToString(" "))
                if (action.rawCommand.isNotBlank()) DetailTextLine(tr("Command"), action.rawCommand)
                if (action.createdPageSemantics.isNotBlank()) DetailTextLine(tr("Page semantics"), action.createdPageSemantics)
                if (action.execResult.isNotBlank()) DetailTextLine(tr("Execution result"), action.execResult)
                if (action.execError.isNotBlank()) DetailTextLine(tr("Execution error"), action.execError)
                if (action.locatorFields.isNotEmpty()) {
                    TraceDetailSection(title = tr("Locator"), items = action.locatorFields)
                }
                if (action.visionFields.isNotEmpty()) {
                    TraceDetailSection(title = tr("Vision fields"), items = action.visionFields)
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
private fun DetailTextLine(label: String, value: String) {
    if (value.isBlank()) return
    Text("$label=$value", fontSize = 11.sp)
}

@Composable
fun ScheduleRow(
    schedule: ScheduleSummary,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val listToggleColors = SwitchDefaults.colors(
        checkedThumbColor = scheme.surface,
        checkedTrackColor = AppSuccess,
        checkedBorderColor = AppSuccess,
        uncheckedThumbColor = scheme.surface,
        uncheckedTrackColor = scheme.primary.copy(alpha = 0.22f).compositeOver(scheme.surface),
        uncheckedBorderColor = scheme.primary.copy(alpha = 0.45f)
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val title = if (schedule.name.isNotEmpty()) schedule.name else schedule.userTask
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (schedule.name.isNotEmpty() && schedule.userTask != title) {
                        Text(
                            text = schedule.userTask,
                            fontSize = 10.sp,
                            color = scheme.onSurface.copy(alpha = 0.72f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Switch(
                    checked = schedule.enabled,
                    onCheckedChange = onToggleEnabled,
                    colors = listToggleColors
                )
            }
            Text(
                text = "${tr("Repeat")}: ${formatRepeat(schedule.repeatMode, schedule.repeatWeekdays)}  •  ${tr("Next run")}: ${formatTsFull(schedule.nextRunAt)}",
                fontSize = 10.sp,
                color = scheme.onSurface.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val meta = buildList {
                    if (schedule.packageName.isNotEmpty()) add("${tr("App")}: ${schedule.packageName}")
                    add("${tr("Record")}: ${if (schedule.recordEnabled) tr("On") else tr("Off")}")
                    add("${tr("Triggered count")}: ${schedule.triggerCount}")
                }.joinToString("  •  ")
                Text(
                    text = meta,
                    modifier = Modifier.weight(1f),
                    fontSize = 10.sp,
                    color = scheme.onSurface.copy(alpha = 0.66f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                TextButton(
                    onClick = onDelete,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier.height(26.dp)
                ) {
                    Text(tr("Delete"), fontSize = 10.sp, color = scheme.error)
                }
            }
        }
    }
}

@Composable
fun NotificationRuleRow(
    rule: NotificationTriggerRuleSummary,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val listToggleColors = SwitchDefaults.colors(
        checkedThumbColor = scheme.surface,
        checkedTrackColor = AppSuccess,
        checkedBorderColor = AppSuccess,
        uncheckedThumbColor = scheme.surface,
        uncheckedTrackColor = scheme.primary.copy(alpha = 0.22f).compositeOver(scheme.surface),
        uncheckedBorderColor = scheme.primary.copy(alpha = 0.45f)
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val title = when {
                rule.name.isNotBlank() -> rule.name
                rule.actionUserTask.isNotBlank() -> rule.actionUserTask
                else -> tr("(no task)")
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (rule.actionUserTask.isNotBlank() && rule.actionUserTask != title) {
                        Text(
                            text = rule.actionUserTask,
                            fontSize = 10.sp,
                            color = scheme.onSurface.copy(alpha = 0.72f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = onToggleEnabled,
                    colors = listToggleColors
                )
            }
            Text(
                text = "${tr("Listening app")}: ${rule.packageList.joinToString(", ").ifBlank { tr("(not set)") }}",
                fontSize = 10.sp,
                color = scheme.onSurface.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val timeWindow = if (rule.activeTimeStart.isNotBlank() && rule.activeTimeEnd.isNotBlank()) {
                "${rule.activeTimeStart}-${rule.activeTimeEnd}"
            } else {
                tr("Anytime")
            }
            Text(
                text = "${tr("Match")}: ${rule.titlePattern.ifBlank { "-" }} / ${rule.bodyPattern.ifBlank { "-" }}",
                fontSize = 10.sp,
                color = scheme.onSurface.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val meta = "${tr("Trigger interval")}: ${rule.cooldownMs / 1000}s  •  ${tr("Time window")}: $timeWindow  •  ${tr("Record")}: ${if (rule.actionRecordEnabled) tr("On") else tr("Off")}  •  LLM: ${if (rule.llmConditionEnabled) tr("On") else tr("Off")}"
                Text(
                    text = meta,
                    modifier = Modifier.weight(1f),
                    fontSize = 10.sp,
                    color = scheme.onSurface.copy(alpha = 0.66f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                TextButton(
                    onClick = onDelete,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier.height(26.dp)
                ) {
                    Text(tr("Delete"), fontSize = 10.sp, color = scheme.error)
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        val accentColor = when (task.state) {
            "FAILED" -> scheme.error
            "RUNNING" -> scheme.primary
            "COMPLETED" -> AppSuccess
            else -> scheme.onSurface.copy(alpha = 0.55f)
        }
        GlyphBadge(
            glyph = when (task.state) {
                "FAILED" -> "!"
                "RUNNING" -> "▶"
                "COMPLETED" -> "✓"
                else -> "•"
            },
            accentColor = accentColor
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = if (task.userTask.isNotEmpty()) task.userTask else tr("(no task description)"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (task.state == "FAILED") scheme.error else scheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                StatusTag(
                    text = tr(task.state),
                    accentColor = accentColor
                )
            }
            val stateParts = mutableListOf(tr(task.state))
            if (task.finalState.isNotEmpty()) {
                stateParts += tr(task.finalState)
            }
            if (task.packageName.isNotEmpty()) {
                stateParts += task.packageName
            }
            if (task.source.isNotEmpty()) {
                stateParts += task.source
            }
            if (task.memoryApplied) {
                stateParts += tr("Memory applied")
            }
            Text(
                text = stateParts.joinToString(" / "),
                fontSize = 11.sp,
                color = scheme.onSurface.copy(alpha = 0.75f)
            )
            if (task.reason.isNotEmpty()) {
                Text(
                    text = "${tr("Reason")}: ${task.reason}",
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

@Composable
private fun formatTaskRouteMode(modeRaw: String): String {
    return when (modeRaw.trim().lowercase(Locale.getDefault())) {
        MainViewModel.TASK_MAP_MODE_MANUAL, MainViewModel.TASK_MAP_MODE_AI -> tr("On")
        else -> tr("Off")
    }
}

@Composable
private fun formatWeekdayMask(mask: Int): String {
    if ((mask and 0x7F) == 0) return "-"
    val labels = listOf(tr("Mon"), tr("Tue"), tr("Wed"), tr("Thu"), tr("Fri"), tr("Sat"), tr("Sun"))
    val selected = mutableListOf<String>()
    for (i in 0..6) {
        if (((mask shr i) and 1) == 1) {
            selected.add(labels[i])
        }
    }
    return if (selected.isEmpty()) "-" else selected.joinToString(", ")
}

@Composable
private fun formatRepeat(modeRaw: String, weekdays: Int): String {
    return when (modeRaw.lowercase(Locale.getDefault())) {
        MainViewModel.REPEAT_DAILY -> tr("Daily")
        MainViewModel.REPEAT_WEEKLY -> "${tr("Weekly")}(${formatWeekdayMask(weekdays)})"
        else -> tr("Once")
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
            subtitle = tr("Choose how touch, typing, and task-time phone behavior should work on this device."),
            glyph = "⌁",
            modifier = modifier,
            onBack = { page = 0 }
        ) {
            LxbCoreConfigCard(viewModel)
        }
        2 -> SingleConfigPage(
            title = tr("LLM config (device-side)"),
            subtitle = tr("Set the model endpoint used for device-side planning and visual understanding."),
            glyph = "✦",
            modifier = modifier,
            onBack = { page = 0 }
        ) {
            LlmConfigCard(viewModel)
        }
        3 -> SingleConfigPage(
            title = tr("Unlock & lock policy"),
            subtitle = tr("Control how the phone is unlocked before a task and locked again afterward."),
            glyph = "🔐",
            modifier = modifier,
            onBack = { page = 0 }
        ) {
            UnlockPolicyConfigCard(viewModel)
        }
        4 -> SingleConfigPage(
            title = tr("Map sync & source"),
            subtitle = tr("Manage route assets, active map lane, and pull-by-identifier actions."),
            glyph = "➜",
            modifier = modifier,
            onBack = { page = 0 }
        ) {
            MapSyncConfigCard(viewModel)
        }
    }
}

// Logs

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
    val traceExportUiState by viewModel.traceExportUiState.collectAsState()
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
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        PageHeaderBlock(
            title = tr("Logs"),
            subtitle = tr("Inspect live core traces, open structured details, and export the full trace when you need to debug."),
            glyph = "≡",
            primaryActionLabel = tr("Export"),
            onPrimaryAction = { viewModel.exportAllTraceToDevice() },
            secondaryActionLabel = tr("Refresh"),
            onSecondaryAction = { viewModel.refreshTraceTailOnDevice(limit = 80) }
        )
        LogPanel(
            traceLines = traceLines,
            listState = listState,
            showLoadingOlder = traceLoadingOlder || (traceHasMoreBefore && listState.firstVisibleItemIndex <= 6),
            traceExportUiState = traceExportUiState,
            onExportTrace = { viewModel.exportAllTraceToDevice() },
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
                    SurfacePanel(
                        modifier = Modifier.fillMaxWidth(),
                        background = MaterialTheme.colorScheme.surfaceVariant,
                        borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(detail.event, style = MaterialTheme.typography.titleSmall)
                            if (detail.summary.isNotBlank()) {
                                Text(detail.summary, fontSize = 12.sp)
                            }
                            if (detail.timestamp.isNotBlank()) {
                                Text("${tr("Timestamp")}: ${detail.timestamp}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (detail.taskId.isNotBlank()) {
                                Text("${tr("Task ID")}: ${detail.taskId}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        SurfacePanel(
                            modifier = Modifier.fillMaxWidth(),
                            background = MaterialTheme.colorScheme.surface,
                            borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Text(
                                detail.detail,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    } else {
                        Text(tr("No trace details."), fontSize = 11.sp)
                    }
                    SurfacePanel(
                        modifier = Modifier.fillMaxWidth(),
                        background = MaterialTheme.colorScheme.surfaceVariant,
                        borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(20.dp)
                    ) {
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
    traceExportUiState: MainViewModel.TraceExportUiState,
    onExportTrace: () -> Unit,
    onOpenTrace: (TraceEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when {
            traceExportUiState.exporting -> {
                StatusNotice(
                    text = tr("Exporting trace..."),
                    accentColor = MaterialTheme.colorScheme.primary
                )
            }

            traceExportUiState.status == "success" -> {
                StatusNotice(
                    text = "${tr("Trace exported")}: ${tr("Saved to")} ${traceExportUiState.savedPath}",
                    accentColor = AppSuccess
                )
            }

            traceExportUiState.status == "empty" -> {
                StatusNotice(
                    text = tr("No trace to export."),
                    accentColor = MaterialTheme.colorScheme.secondary
                )
            }

            traceExportUiState.status == "failure" -> {
                StatusNotice(
                    text = "${tr("Trace export failed")}: ${traceExportUiState.error}",
                    accentColor = AppError
                )
            }
        }
        SurfacePanel(
            modifier = Modifier.fillMaxSize(),
            background = MaterialTheme.colorScheme.surface,
            borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f),
            shape = RoundedCornerShape(24.dp)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
            ) {
                item(key = "trace_hint") {
                    Text(
                        text = tr("Latest traces stay at the bottom. Scroll upward to load older ones."),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
                if (showLoadingOlder) {
                    item(key = "loading_older") {
                        Text(
                            text = tr("Load older traces..."),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
                        )
                    }
                }
                items(traceLines, key = { if (it.seq > 0L) it.seq else it.rawLine.hashCode().toLong() }) { entry ->
                    TraceCard(entry = entry, onClick = { onOpenTrace(entry) })
                }
                item(key = "trace_footer_space") {
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun TraceCard(entry: TraceEntry, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val accent = when {
        entry.isError -> scheme.error
        entry.event.startsWith("notify_") -> scheme.secondary
        entry.event == "fsm_state_enter" -> scheme.primary
        else -> scheme.onSurface.copy(alpha = 0.42f)
    }
    SurfacePanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 4.dp),
        background = if (entry.isError) AppErrorSoft else scheme.surface,
        borderColor = accent.copy(alpha = 0.18f),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlyphBadge(
                        glyph = when {
                            entry.isError -> "!"
                            entry.event.startsWith("notify_") -> "✉"
                            entry.event == "fsm_state_enter" -> "↺"
                            else -> "•"
                        },
                        accentColor = accent,
                        size = 28.dp
                    )
                    Text(
                        text = entry.event,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurface
                    )
                }
                if (entry.timestamp.isNotBlank()) {
                    StatusTag(
                        text = entry.timestamp.takeLast(12),
                        accentColor = accent
                    )
                }
            }
            if (entry.summary.isNotBlank()) {
                Text(
                    text = entry.summary,
                    fontSize = 11.sp,
                    color = scheme.onSurface.copy(alpha = 0.92f),
                    maxLines = 2
                )
            }
            if (entry.taskId.isNotBlank()) {
                Text(
                    text = "${tr("Task")}=${entry.taskId.take(8)}...",
                    fontSize = 10.sp,
                    color = scheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun TraceDetailSection(title: String, items: List<TraceMetaItem>) {
    if (items.isEmpty()) return
    SurfacePanel(
        modifier = Modifier.fillMaxWidth(),
        background = MaterialTheme.colorScheme.surface,
        borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f),
        shape = RoundedCornerShape(20.dp)
    ) {
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
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        PageHeaderBlock(
            title = tr("Config center"),
            subtitle = tr("Choose a category to configure. Each section opens a dedicated settings page."),
            glyph = "⚙"
        )

        SummaryInfoStrip(
            glyph = if (coreRuntime.ready) "✓" else "!",
            title = tr("Current setup"),
            primaryMetric = "${tr("State")}: ${if (coreRuntime.ready) tr("Core Connected") else tr("Core Disconnected")}",
            secondaryMetric = if (uiLang == "zh") tr("Chinese") else tr("English"),
            accentColor = if (coreRuntime.ready) AppSuccess else AppWarning
        )

        ProductSectionCard(
            title = tr("Recommended first"),
            subtitle = tr("Most people only need model and control settings before starting tasks."),
            glyph = "★"
        ) {
            ConfigEntryCard(
                title = tr("LLM config (device-side)"),
                description = tr("Base URL, API key and model for device-side LLM/VLM calls."),
                onClick = onOpenLlm
            )
            ConfigEntryCard(
                title = tr("Control mode config"),
                description = tr("If taps, swipes, or typing are not behaving well on your phone, adjust them here."),
                onClick = onOpenDeviceCore,
                badgeText = if (coreRuntime.ready) tr("Ready") else tr("Needs sync")
            )
        }

        ProductSectionCard(
            title = tr("Device behavior"),
            subtitle = tr("Language, unlock, and other phone-side behavior live here."),
            glyph = "☑"
        ) {
            SurfacePanel(
                modifier = Modifier.fillMaxWidth(),
                background = MaterialTheme.colorScheme.surfaceVariant,
                borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.20f),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SheetHeader(
                        title = tr("Language"),
                        subtitle = tr("Language for app UI text.")
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ModeChoiceButton(
                            text = tr("English"),
                            selected = uiLang == "en",
                            onClick = { viewModel.setUiLang("en") },
                            modifier = Modifier.weight(1f)
                        )
                        ModeChoiceButton(
                            text = tr("Chinese"),
                            selected = uiLang == "zh",
                            onClick = { viewModel.setUiLang("zh") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            ConfigEntryCard(
                title = tr("Unlock & lock policy"),
                description = tr("Auto unlock before route, auto lock after task, and lockscreen credentials."),
                onClick = onOpenUnlockPolicy
            )
        }

        ProductSectionCard(
            title = tr("Map routing"),
            subtitle = tr("Route asset source, sync, and identifier pull controls."),
            glyph = "➜"
        ) {
            ConfigEntryCard(
                title = tr("Map sync & source"),
                description = tr("Sync stable maps, pull map by identifier, and choose runtime source lane."),
                onClick = onOpenMapSync
            )
        }
    }
}

@Composable
fun ConfigEntryCard(
    title: String,
    description: String,
    onClick: () -> Unit,
    badgeText: String? = null
) {
    SurfacePanel(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        background = MaterialTheme.colorScheme.surfaceVariant,
        borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.20f),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (!badgeText.isNullOrBlank()) {
                    StatusTag(text = badgeText, accentColor = MaterialTheme.colorScheme.primary)
                }
            }
            Text(
                text = description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
            )
            Text(
                text = "${tr("Open")} ›",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun SingleConfigPage(
    title: String,
    subtitle: String? = null,
    glyph: String = "⚙",
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
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (!subtitle.isNullOrBlank()) {
            PageHeaderBlock(
                title = title,
                subtitle = subtitle,
                glyph = glyph,
                onBack = onBack
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PageActionButton(text = tr("Back"), onClick = onBack)
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier
                        .weight(1f),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        content()
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    subtitle: String? = null,
    glyph: String = "•",
    content: @Composable () -> Unit
) {
    SurfacePanel(
        modifier = Modifier.fillMaxWidth(),
        background = MaterialTheme.colorScheme.surface,
        borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                GlyphBadge(
                    glyph = glyph,
                    accentColor = MaterialTheme.colorScheme.primary,
                    size = 34.dp
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                        )
                    }
                }
            }
            content()
        }
    }
}

@Composable
private fun PreferenceSwitchRow(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = detail,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun StatusNotice(
    text: String,
    accentColor: Color
) {
    SurfacePanel(
        modifier = Modifier.fillMaxWidth(),
        background = accentColor.copy(alpha = 0.09f).compositeOver(MaterialTheme.colorScheme.surface),
        borderColor = accentColor.copy(alpha = 0.22f),
        shape = RoundedCornerShape(18.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            fontSize = 11.sp,
            color = accentColor
        )
    }
}

@Composable
fun LxbCoreConfigCard(viewModel: MainViewModel) {
    val lxbPort by viewModel.lxbPort.collectAsState()
    val touchMode by viewModel.touchMode.collectAsState()
    val taskDndMode by viewModel.taskDndMode.collectAsState()
    val adbKeyboardUiState by viewModel.adbKeyboardUiState.collectAsState()
    val coreConfigResult by viewModel.coreConfigResult.collectAsState()
    val coreRuntime by viewModel.coreRuntimeStatus.collectAsState()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SummaryInfoStrip(
            glyph = if (coreRuntime.ready) "✓" else "!",
            title = tr("Device control"),
            primaryMetric = "${tr("State")}: ${if (coreRuntime.ready) tr("Core Connected") else tr("Core Disconnected")}",
            secondaryMetric = if (touchMode == MainViewModel.TOUCH_MODE_SHELL) tr("Shell") else tr("UIAutomator"),
            accentColor = if (coreRuntime.ready) AppSuccess else AppWarning
        )

        SettingsSectionCard(
            title = tr("Connection"),
            subtitle = tr("Device-side core connection settings."),
            glyph = "⌁"
        ) {
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

        SettingsSectionCard(
            title = tr("Touch input mode"),
            subtitle = tr("Choose the injection strategy used for taps and swipes."),
            glyph = "☞"
        ) {
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

        SettingsSectionCard(
            title = tr("Text input mode"),
            subtitle = tr("ADB Keyboard is recommended because it gives the most reliable input experience."),
            glyph = "⌨"
        ) {
            Text(
                tr("Recommended: install ADB Keyboard. If detected, core will switch to it only while typing, then restore your original keyboard automatically."),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                fontSize = 12.sp
            )
            Text(
                tr("If ADB Keyboard is not detected, fallback input will still work, but some apps may fail to accept Chinese text."),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                fontSize = 12.sp
            )
            StatusNotice(
                text = buildString {
                    append(tr(if (adbKeyboardUiState.installed) "Detected" else "Not detected"))
                    if (adbKeyboardUiState.installed && adbKeyboardUiState.label.isNotBlank()) {
                        append(": ")
                        append(adbKeyboardUiState.label)
                    }
                },
                accentColor = if (adbKeyboardUiState.installed) AppSuccess else AppWarning
            )
            if (adbKeyboardUiState.currentImeId.isNotBlank()) {
                Text(
                    text = "${tr("Current keyboard")}: ${adbKeyboardUiState.currentImeId}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )
            }
            Text(
                text = tr(
                    if (adbKeyboardUiState.installed) {
                        "Auto-switch input channel is ready."
                    } else {
                        "Core will use fallback text input for now."
                    }
                ),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
            )
            OutlinedButton(
                onClick = { viewModel.refreshAdbKeyboardStatus() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(tr("Check ADB Keyboard"))
            }
            if (adbKeyboardUiState.checked && !adbKeyboardUiState.installed) {
                OutlinedButton(
                    onClick = { viewModel.openAdbKeyboardReleasePage() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(tr("Download ADB Keyboard"))
                }
            }
        }

        SettingsSectionCard(
            title = tr("Task-time Do Not Disturb"),
            subtitle = tr("Choose what happens to DND automatically when a task begins."),
            glyph = "☾"
        ) {
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

        SettingsSectionCard(
            title = tr("Apply changes"),
            subtitle = tr("Push the current control configuration to core now, or only save it locally."),
            glyph = "✓"
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
                StatusNotice(
                    text = coreConfigResult,
                    accentColor = if (coreConfigResult.contains("ok", ignoreCase = true) || coreConfigResult.contains("success", ignoreCase = true)) AppSuccess else MaterialTheme.colorScheme.secondary
                )
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
    val llmProfileDraftName by viewModel.llmProfileDraftName.collectAsState()
    val llmProfiles by viewModel.llmProfiles.collectAsState()
    val activeLlmProfileId by viewModel.activeLlmProfileId.collectAsState()
    val llmProfileResult by viewModel.llmProfileResult.collectAsState()
    val resolvedEndpoint = remember(llmBaseUrl) {
        runCatching { LlmClient.buildEndpointUrl(llmBaseUrl) }.getOrNull().orEmpty()
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SummaryInfoStrip(
            glyph = "✦",
            title = tr("Model routing"),
            primaryMetric = if (llmModel.isNotBlank()) llmModel else tr("No model selected"),
            secondaryMetric = if (activeLlmProfileId.isNotBlank()) tr("Saved config") else tr("Draft only"),
            accentColor = MaterialTheme.colorScheme.primary
        )

        SettingsSectionCard(
            title = tr("Connection"),
            subtitle = tr("Base endpoint, API key, and model used for device-side requests."),
            glyph = "🌐"
        ) {
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
            StatusNotice(
                text = "${tr("Resolved request URL")}: " +
                    if (resolvedEndpoint.isNotBlank()) resolvedEndpoint else tr("Input API Base URL to preview request endpoint."),
                accentColor = MaterialTheme.colorScheme.secondary
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
                supportingText = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(tr("e.g. gpt-4o-mini, qwen-plus"))
                        Text(tr("Please use a model with image recognition capability."))
                    }
                }
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
                StatusNotice(
                    text = llmTestResult,
                    accentColor = if (llmTestResult.startsWith("LLM OK")) AppSuccess else AppWarning
                )
            }
        }

        SettingsSectionCard(
            title = tr("Saved local configs"),
            subtitle = tr("Save reusable model presets locally and switch between them quickly."),
            glyph = "☁"
        ) {
            OutlinedTextField(
                value = llmProfileDraftName,
                onValueChange = { viewModel.llmProfileDraftName.value = it },
                label = { Text(tr("Config name")) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.saveCurrentLlmAsNewProfile() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(tr("Save as new"))
                }
                OutlinedButton(
                    onClick = { viewModel.updateSelectedLlmProfile() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(tr("Update selected"))
                }
            }
            if (llmProfileResult.isNotEmpty()) {
                StatusNotice(
                    text = llmProfileResult,
                    accentColor = MaterialTheme.colorScheme.secondary
                )
            }
            if (llmProfiles.isEmpty()) {
                Text(
                    text = tr("No saved LLM configs yet."),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    llmProfiles.forEach { profile ->
                        SurfacePanel(
                            modifier = Modifier.fillMaxWidth(),
                            background = if (profile.id == activeLlmProfileId) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            borderColor = if (profile.id == activeLlmProfileId) MaterialTheme.colorScheme.primary.copy(alpha = 0.20f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = profile.name,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    if (profile.id == activeLlmProfileId) {
                                        StatusTag(
                                            text = tr("Selected config"),
                                            accentColor = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                if (profile.model.isNotBlank()) {
                                    Text(
                                        text = profile.model,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
                                    )
                                }
                                if (profile.apiBaseUrl.isNotBlank()) {
                                    Text(
                                        text = profile.apiBaseUrl,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = { viewModel.applyLlmProfile(profile.id) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(tr("Use this config"))
                                    }
                                    OutlinedButton(
                                        onClick = { viewModel.deleteLlmProfile(profile.id) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(tr("Delete"))
                                    }
                                }
                            }
                        }
                    }
                }
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

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SummaryInfoStrip(
            glyph = "🔐",
            title = tr("Unlock policy"),
            primaryMetric = if (autoUnlockBeforeRoute) tr("Unlock before route is ON") else tr("Unlock before route is OFF"),
            secondaryMetric = if (autoLockAfterTask) tr("Auto lock ON") else tr("Auto lock OFF"),
            accentColor = MaterialTheme.colorScheme.primary
        )

        SettingsSectionCard(
            title = tr("Behavior"),
            subtitle = tr("These switches control whether the phone unlocks before a task and locks after it."),
            glyph = "☑"
        ) {
            PreferenceSwitchRow(
                title = tr("Auto unlock before route"),
                detail = tr("Check screen state and unlock before app launch/routing."),
                checked = autoUnlockBeforeRoute,
                onCheckedChange = { viewModel.autoUnlockBeforeRoute.value = it }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
            PreferenceSwitchRow(
                title = tr("Auto lock after task"),
                detail = tr("Lock screen when task ends if the FSM unlocked it."),
                checked = autoLockAfterTask,
                onCheckedChange = { viewModel.autoLockAfterTask.value = it }
            )
        }

        SettingsSectionCard(
            title = tr("Lockscreen credentials"),
            subtitle = tr("Only needed when swipe unlock alone is not enough."),
            glyph = "•"
        ) {
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
        }

        SettingsSectionCard(
            title = tr("Apply changes"),
            subtitle = tr("Sync the latest unlock settings to device now, or only save them locally."),
            glyph = "✓"
        ) {
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
                StatusNotice(
                    text = llmTestResult,
                    accentColor = MaterialTheme.colorScheme.secondary
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

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SummaryInfoStrip(
            glyph = "➜",
            title = tr("Map routing"),
            primaryMetric = if (useMap) tr("Use map routing is ON") else tr("Use map routing is OFF"),
            secondaryMetric = tr(
                when (mapSource) {
                    "candidate" -> "Candidate"
                    "burn" -> "Burn"
                    else -> "Stable"
                }
            ),
            accentColor = if (useMap) MaterialTheme.colorScheme.primary else AppWarning
        )

        SettingsSectionCard(
            title = tr("Source & repository"),
            subtitle = tr("Choose where maps come from and which runtime lane stays active."),
            glyph = "☁"
        ) {
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
            PreferenceSwitchRow(
                title = tr("Use map routing"),
                detail = if (useMap) {
                    tr("ON: route with map when available.")
                } else {
                    tr("OFF: force no-map mode (launch then vision-only).")
                },
                checked = useMap,
                onCheckedChange = { viewModel.setUseMap(it) }
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Map source"), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = tr("Choose which lane is applied to runtime routing map."),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeChoiceButton(
                        text = tr("Stable"),
                        selected = mapSource == "stable",
                        onClick = { viewModel.setMapSource("stable") },
                        modifier = Modifier.weight(1f)
                    )
                    ModeChoiceButton(
                        text = tr("Candidate"),
                        selected = mapSource == "candidate",
                        onClick = { viewModel.setMapSource("candidate") },
                        modifier = Modifier.weight(1f)
                    )
                    ModeChoiceButton(
                        text = tr("Burn"),
                        selected = mapSource == "burn",
                        onClick = { viewModel.setMapSource("burn") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        SettingsSectionCard(
            title = tr("Identifier pull"),
            subtitle = tr("Pick the app and map identifier used for pull-by-ID and active-map checks."),
            glyph = "⌕"
        ) {
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
        }

        SettingsSectionCard(
            title = tr("Actions"),
            subtitle = tr("Sync or pull route assets, inspect the active map, and save the current settings."),
            glyph = "✓"
        ) {
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
                StatusNotice(
                    text = mapSyncResult,
                    accentColor = MaterialTheme.colorScheme.secondary
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
