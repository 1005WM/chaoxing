// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.caochaoxing.android.state.AndroidAppState
import com.caochaoxing.android.state.AssessmentOverviewController
import com.caochaoxing.android.state.CaptchaChallengeRequest
import com.caochaoxing.android.state.CourseStudyController
import com.caochaoxing.android.ui.components.CxHairlineDivider
import com.caochaoxing.android.ui.components.CxIconTile
import com.caochaoxing.android.ui.components.CxPrimaryButton
import com.caochaoxing.android.ui.components.CxTone
import com.caochaoxing.android.ui.screens.AssessmentTodoScreen
import com.caochaoxing.android.ui.screens.AssessmentWebScreen
import com.caochaoxing.android.ui.screens.assessmentItemUrl
import com.caochaoxing.android.ui.screens.desktopAssessmentFallbackUrl
import com.caochaoxing.android.ui.screens.desktopAssessmentUrl
import com.caochaoxing.android.ui.screens.COURSE_SUBTAB_ASSESSMENT
import com.caochaoxing.android.ui.screens.COURSE_SUBTAB_SIGN
import com.caochaoxing.android.ui.screens.CourseDetailScreen
import com.caochaoxing.android.ui.screens.CourseListScreen
import com.caochaoxing.android.ui.screens.GroupDetailScreen
import com.caochaoxing.android.ui.screens.GroupListScreen
import com.caochaoxing.android.ui.screens.KeepScreenOnWhileWatching
import com.caochaoxing.android.ui.screens.LocationPresetsScreen
import com.caochaoxing.android.ui.screens.LoginScreen
import com.caochaoxing.android.ui.screens.AppUpdateHost
import com.caochaoxing.android.ui.screens.OtherUsersScreen
import com.caochaoxing.android.ui.screens.SettingsScreen
import com.caochaoxing.android.ui.screens.SignHistoryScreen
import com.caochaoxing.android.ui.screens.StandaloneQrSignScreen
import com.caochaoxing.android.ui.screens.StandaloneQrViewModel
import com.caochaoxing.android.ui.screens.signers.CaptchaSheetDialog
import com.caochaoxing.android.ui.screens.signers.SignWorkspaceScreen
import com.caochaoxing.android.ui.theme.CaochaoxingTheme
import com.caochaoxing.android.ui.theme.CxPalette
import com.caochaoxing.android.ui.theme.CxTypography
import com.caochaoxing.core.model.ChaoxingCourse
import com.caochaoxing.core.model.ChaoxingAssessmentItem
import com.caochaoxing.core.model.ChaoxingSignActivity
import com.caochaoxing.core.store.ChaoxingSnapshotJson
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Base64

/**
 * Single-top Activity host configuring edge-to-edge Compose rendering and deep-link ingestion.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = CaochaoxingApp.from(this)
        if (savedInstanceState == null) {
            dispatchIncomingIntent(app, intent)
        }

        setContent {
            CaochaoxingTheme {
                CaochaoxingMainRoot(
                    appState = app.appState,
                    courseStudyController = app.courseStudyController,
                )
                AppUpdateHost(app.updateManager)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        dispatchIncomingIntent(CaochaoxingApp.from(this), intent)
    }

    private fun dispatchIncomingIntent(app: CaochaoxingApp, intent: Intent?) {
        val uriString = intent?.dataString?.trim()?.takeIf { it.isNotEmpty() } ?: return
        app.applicationScope.launch {
            app.appState.handleIncomingIntent(uriString)
        }
    }
}

private object CxRoutes {
    const val MAIN = "main"
    const val COURSE_DETAIL = "course_detail/{courseKey}?tab={tab}"
    const val ASSESSMENT_TODO = "assessment_todo"
    const val ASSESSMENT_WEB = "assessment_web?url={url}&mobile={mobile}&title={title}"
    const val GROUP_DETAIL = "group_detail/{encodedGroupId}"
    const val OTHER_USERS = "other_users"
    const val LOCATION_PRESETS = "location_presets"
    const val SIGN_HISTORY = "sign_history"
    const val SIGN_WORKSPACE = "sign_workspace"

    fun courseDetail(courseKey: String, tab: Int? = null): String = buildString {
        append("course_detail/")
        append(Uri.encode(courseKey))
        if (tab != null) {
            append("?tab=")
            append(tab)
        }
    }

    fun assessmentWeb(url: String, mobileUrl: String? = null, title: String = ""): String = buildString {
        append("assessment_web?url=").append(Uri.encode(url))
        if (!mobileUrl.isNullOrBlank()) {
            append("&mobile=").append(Uri.encode(mobileUrl))
        }
        if (title.isNotBlank()) {
            append("&title=").append(Uri.encode(title))
        }
    }

    fun groupDetail(groupId: String): String {
        val encoded = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(groupId.toByteArray(Charsets.UTF_8))
        return "group_detail/$encoded"
    }

    fun decodeGroupId(encoded: String): String =
        runCatching {
            Base64.getUrlDecoder().decode(encoded).toString(Charsets.UTF_8)
        }.getOrDefault("")
}

/** 作业优先打开电脑版作业页，考试使用原链接。 */
private fun openAssessmentPage(
    navController: androidx.navigation.NavHostController,
    item: ChaoxingAssessmentItem,
    course: ChaoxingCourse?,
    onMissingLink: () -> Unit,
) {
    val raw = assessmentItemUrl(item) ?: return onMissingLink()
    // 单项电脑版页优先；拿不到时退回电脑版课程作业入口，保证「电脑版/手机版」切换按钮不消失。
    val desktop = desktopAssessmentUrl(item, course?.cpi) ?: desktopAssessmentFallbackUrl(item, course)
    navController.navigate(CxRoutes.assessmentWeb(desktop ?: raw, raw.takeIf { desktop != null && desktop != raw }, item.title))
}

private data class MainTabSpec(
    val index: Int,
    val label: String,
    val icon: ImageVector,
)

private val MAIN_TABS = listOf(
    MainTabSpec(AndroidAppState.TAB_COURSES, "课程", Icons.Outlined.GridView),
    MainTabSpec(AndroidAppState.TAB_GROUPS, "群聊", Icons.Outlined.ChatBubbleOutline),
    MainTabSpec(AndroidAppState.TAB_QR, "扫码", Icons.Outlined.QrCodeScanner),
    MainTabSpec(AndroidAppState.TAB_SETTINGS, "设置", Icons.Outlined.Settings),
)

/**
 * Root navigation and global feedback container managing startup restoration, persistence
 * recovery, authentication gate, 4-tab navigation, sub-routes, deep links, status pills,
 * error snackbars, and extension slots for the sign workspace and captcha dialog.
 */
@Composable
fun CaochaoxingMainRoot(
    appState: AndroidAppState,
    modifier: Modifier = Modifier,
    courseStudyController: CourseStudyController? = null,
    signWorkspaceContent: @Composable (
        activity: ChaoxingSignActivity,
        onNavigateToLocations: () -> Unit,
        onDismiss: () -> Unit,
    ) -> Unit = { activity, onNavigateToLocations, onDismiss ->
        SignWorkspaceScreen(
            appState = appState,
            activity = activity,
            onNavigateToLocations = onNavigateToLocations,
            onBack = onDismiss,
        )
    },
    captchaDialogContent: @Composable (
        challenge: CaptchaChallengeRequest,
        onDismiss: () -> Unit,
    ) -> Unit = { challenge, onDismiss ->
        CaptchaSheetDialog(
            appState = appState,
            challenge = challenge,
            onDismiss = onDismiss,
        )
    },
    assessmentOverviewController: AssessmentOverviewController? = null,
) {
    val uiState by appState.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val appScope = remember(context, scope) {
        (context.applicationContext as? CaochaoxingApp)?.applicationScope ?: scope
    }
    val resolvedCourseStudyController = remember(courseStudyController, context, appState, appScope) {
        courseStudyController
            ?: (context.applicationContext as? CaochaoxingApp)?.courseStudyController
            ?: CourseStudyController(appState = appState, scope = appScope)
    }
    LaunchedEffect(uiState.activePhoneNumber, resolvedCourseStudyController) {
        resolvedCourseStudyController.onAccountChanged(uiState.activePhoneNumber)
    }
    val studyUiState by resolvedCourseStudyController.uiState.collectAsStateWithLifecycle()
    KeepScreenOnWhileWatching(isWatching = studyUiState.isWatching)

    var showRecoveryConfirmation by remember { mutableStateOf(false) }
    var isOnMainRoute by remember { mutableStateOf(true) }

    LaunchedEffect(uiState.errorMessage) {
        val message = uiState.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            message = message,
            actionLabel = "确定",
            duration = SnackbarDuration.Long,
        )
        if (appState.uiState.value.errorMessage == message) {
            appState.clearErrorMessage()
        }
    }

    LaunchedEffect(uiState.statusMessage) {
        val status = uiState.statusMessage ?: return@LaunchedEffect
        delay(3_000L)
        if (appState.uiState.value.statusMessage == status) {
            appState.clearStatusMessage()
        }
    }

    val isAuthenticated = !uiState.isRestoring && !uiState.persistenceBlocked && uiState.activeAccount != null

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CxPalette.canvas),
    ) {
        when {
            uiState.isRestoring -> {
                RestoringStateView()
            }

            uiState.persistenceBlocked -> {
                PersistenceBlockedView(
                    errorMessage = uiState.errorMessage,
                    onRequestRecovery = { showRecoveryConfirmation = true },
                )
            }

            uiState.activeAccount == null -> {
                LoginScreen(appState = appState)
            }

            else -> {
                key(uiState.activePhoneNumber) {
                    AuthenticatedMainContainer(
                        appState = appState,
                        courseStudyController = resolvedCourseStudyController,
                        assessmentOverviewController = assessmentOverviewController,
                        onRouteMainStatusChange = { isMain -> isOnMainRoute = isMain },
                        signWorkspaceContent = signWorkspaceContent,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = !uiState.statusMessage.isNullOrBlank(),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 10.dp, start = 20.dp, end = 20.dp),
        ) {
            val status = uiState.statusMessage.orEmpty()
            StatusPill(text = status)
        }

        val snackbarBottomPadding = if (isAuthenticated && isOnMainRoute) {
            66.dp
        } else {
            16.dp
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .imePadding()
                .padding(start = 16.dp, end = 16.dp, bottom = snackbarBottomPadding),
        ) { snackbarData ->
            Snackbar(
                snackbarData = snackbarData,
                shape = RoundedCornerShape(12.dp),
                containerColor = CxPalette.cinnabar,
                contentColor = CxPalette.onAccent,
                actionColor = CxPalette.onAccent,
            )
        }
    }

    uiState.captchaChallenge?.let { challenge ->
        captchaDialogContent(challenge) {
            appState.captchaSheetDidDismiss(challenge.id)
        }
    }

    if (showRecoveryConfirmation) {
        AlertDialog(
            onDismissRequest = { showRecoveryConfirmation = false },
            containerColor = CxPalette.surface,
            titleContentColor = CxPalette.ink,
            textContentColor = CxPalette.inkSecondary,
            title = { Text("备份原文件并重置？") },
            text = {
                Text("原文件会在本机保留恢复副本。当前状态将重置为初始值；如果无法保留副本，则不会重置。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRecoveryConfirmation = false
                        appScope.launch { appState.recoverLocalState() }
                    },
                ) {
                    Text(
                        text = "备份并重置",
                        color = CxPalette.cinnabar,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showRecoveryConfirmation = false }) {
                    Text("取消", color = CxPalette.inkSecondary)
                }
            },
        )
    }
}

private val SignActivityStateSaver: Saver<ChaoxingSignActivity?, String> = Saver(
    save = { activity ->
        activity?.let {
            ChaoxingSnapshotJson.encodeToString(ChaoxingSignActivity.serializer(), it)
        }
    },
    restore = { raw ->
        runCatching {
            ChaoxingSnapshotJson.decodeFromString(ChaoxingSignActivity.serializer(), raw)
        }.getOrNull()
    },
)

@Composable
private fun AuthenticatedMainContainer(
    appState: AndroidAppState,
    courseStudyController: CourseStudyController,
    assessmentOverviewController: AssessmentOverviewController?,
    onRouteMainStatusChange: (Boolean) -> Unit,
    signWorkspaceContent: @Composable (
        activity: ChaoxingSignActivity,
        onNavigateToLocations: () -> Unit,
        onDismiss: () -> Unit,
    ) -> Unit,
) {
    val uiState by appState.uiState.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val context = LocalContext.current
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    var activeSignActivity by rememberSaveable(stateSaver = SignActivityStateSaver) {
        mutableStateOf(null)
    }

    val currentRoute = navBackStackEntry?.destination?.route
    LaunchedEffect(currentRoute) {
        onRouteMainStatusChange(currentRoute == CxRoutes.MAIN)
    }

    fun navigateToSignWorkspace(activity: ChaoxingSignActivity) {
        activeSignActivity = activity
        val hasExistingWorkspace = runCatching {
            navController.getBackStackEntry(CxRoutes.SIGN_WORKSPACE)
            true
        }.getOrDefault(false)
        navController.navigate(CxRoutes.SIGN_WORKSPACE) {
            if (hasExistingWorkspace) {
                popUpTo(CxRoutes.SIGN_WORKSPACE) { inclusive = true }
            }
            launchSingleTop = true
        }
    }

    LaunchedEffect(uiState.deepLinkActivity) {
        val activity = uiState.deepLinkActivity ?: return@LaunchedEffect
        appState.clearDeepLinkActivity()
        navigateToSignWorkspace(activity)
    }

    LaunchedEffect(uiState.pendingQrCodeInput) {
        if (uiState.pendingQrCodeInput != null) {
            activeSignActivity = null
            navController.popBackStack(CxRoutes.MAIN, inclusive = false)
        }
    }

    fun openSignActivity(activity: ChaoxingSignActivity) {
        navigateToSignWorkspace(activity)
    }

    NavHost(
        navController = navController,
        startDestination = CxRoutes.MAIN,
        modifier = Modifier.fillMaxSize(),
    ) {
        composable(CxRoutes.MAIN) {
            val qrViewModel: StandaloneQrViewModel = viewModel(
                key = "qr_${uiState.activePhoneNumber}",
            ) {
                StandaloneQrViewModel(appState)
            }
            val saveableStateHolder = rememberSaveableStateHolder()

            Scaffold(
                containerColor = CxPalette.canvas,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp)) {
                        CxHairlineDivider()
                        Row(Modifier.fillMaxWidth().selectableGroup()) {
                            MAIN_TABS.forEach { tab ->
                                val selected = uiState.selectedTab == tab.index
                                val tint = if (selected) CxPalette.accent else CxPalette.inkTertiary
                                Column(
                                    modifier = Modifier.weight(1f).defaultMinSize(minHeight = 52.dp)
                                        .selectable(selected = selected, role = Role.Tab) { appState.setSelectedTab(tab.index) }
                                        .padding(vertical = 5.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
                                ) {
                                    Icon(tab.icon, null, tint = tint, modifier = Modifier.size(20.dp))
                                    Text(
                                        text = tab.label,
                                        style = CxTypography.eyebrow,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                        color = tint,
                                    )
                                }
                            }
                        }
                    }
                },
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .consumeWindowInsets(innerPadding),
                ) {
                    saveableStateHolder.SaveableStateProvider(uiState.selectedTab) {
                        when (uiState.selectedTab) {
                            AndroidAppState.TAB_COURSES -> CourseListScreen(
                                appState = appState,
                                courseStudyController = courseStudyController,
                                assessmentOverviewController = assessmentOverviewController,
                                onCourseClick = { course ->
                                    navController.navigate(CxRoutes.courseDetail(course.id))
                                },
                                onActivityClick = ::openSignActivity,
                                onManageAccounts = {
                                    navController.navigate(CxRoutes.OTHER_USERS)
                                },
                                onOpenTodo = {
                                    navController.navigate(CxRoutes.ASSESSMENT_TODO)
                                },
                            )

                            AndroidAppState.TAB_GROUPS -> GroupListScreen(
                                appState = appState,
                                onGroupClick = { group ->
                                    navController.navigate(CxRoutes.groupDetail(group.id))
                                },
                            )

                            AndroidAppState.TAB_QR -> StandaloneQrSignScreen(
                                appState = appState,
                                viewModel = qrViewModel,
                                onManageLocations = {
                                    navController.navigate(CxRoutes.LOCATION_PRESETS)
                                },
                            )

                            AndroidAppState.TAB_SETTINGS -> SettingsScreen(
                                appState = appState,
                                onOpenAccounts = {
                                    navController.navigate(CxRoutes.OTHER_USERS)
                                },
                                onOpenLocations = {
                                    navController.navigate(CxRoutes.LOCATION_PRESETS)
                                },
                                onOpenHistory = {
                                    navController.navigate(CxRoutes.SIGN_HISTORY)
                                },
                            )

                            else -> CourseListScreen(
                                appState = appState,
                                courseStudyController = courseStudyController,
                                assessmentOverviewController = assessmentOverviewController,
                                onCourseClick = { course ->
                                    navController.navigate(CxRoutes.courseDetail(course.id))
                                },
                                onActivityClick = ::openSignActivity,
                                onManageAccounts = {
                                    navController.navigate(CxRoutes.OTHER_USERS)
                                },
                                onOpenTodo = {
                                    navController.navigate(CxRoutes.ASSESSMENT_TODO)
                                },
                            )
                        }
                    }
                }
            }
        }

        composable(
            route = CxRoutes.COURSE_DETAIL,
            arguments = listOf(
                navArgument("courseKey") { type = NavType.StringType },
                navArgument("tab") {
                    type = NavType.IntType
                    defaultValue = COURSE_SUBTAB_SIGN
                },
            ),
        ) { backStackEntry ->
            val courseKey = backStackEntry.arguments?.getString("courseKey").orEmpty()
            val initialTab = backStackEntry.arguments?.getInt("tab") ?: COURSE_SUBTAB_SIGN
            CourseDetailScreen(
                appState = appState,
                courseKey = courseKey,
                onBack = { navController.popBackStack() },
                onActivityClick = ::openSignActivity,
                courseStudyController = courseStudyController,
                initialSubTab = initialTab,
                onOpenAssessmentPage = { item ->
                    openAssessmentPage(navController, item, uiState.courses.firstOrNull { it.id == courseKey }) {
                        Toast.makeText(context, "这项待办暂无可用链接", Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }

        composable(CxRoutes.ASSESSMENT_TODO) {
            AssessmentTodoScreen(
                appState = appState,
                assessmentOverviewController = assessmentOverviewController,
                onBack = { navController.popBackStack() },
                onCourseClick = { course ->
                    navController.navigate(CxRoutes.courseDetail(course.id, tab = COURSE_SUBTAB_ASSESSMENT))
                },
                onOpenWeb = { item ->
                    val course = uiState.courses.firstOrNull { it.courseId == item.courseId && it.classId == item.classId }
                    openAssessmentPage(navController, item, course) {
                        if (course != null) {
                            navController.navigate(CxRoutes.courseDetail(course.id, tab = COURSE_SUBTAB_ASSESSMENT))
                        }
                        Toast.makeText(context, "这项待办暂无可用链接", Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }

        composable(
            route = CxRoutes.ASSESSMENT_WEB,
            arguments = listOf(
                navArgument("url") { type = NavType.StringType },
                navArgument("mobile") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("title") {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) { backStackEntry ->
            AssessmentWebScreen(
                appState = appState,
                url = backStackEntry.arguments?.getString("url").orEmpty(),
                mobileUrl = backStackEntry.arguments?.getString("mobile")?.takeIf { it.isNotBlank() },
                title = backStackEntry.arguments?.getString("title").orEmpty(),
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = CxRoutes.GROUP_DETAIL,
            arguments = listOf(navArgument("encodedGroupId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val encoded = backStackEntry.arguments?.getString("encodedGroupId").orEmpty()
            val groupId = CxRoutes.decodeGroupId(encoded)
            GroupDetailScreen(
                appState = appState,
                groupId = groupId,
                onBack = { navController.popBackStack() },
                onActivityClick = ::openSignActivity,
            )
        }

        composable(CxRoutes.OTHER_USERS) {
            OtherUsersScreen(
                appState = appState,
                onBack = { navController.popBackStack() },
            )
        }

        composable(CxRoutes.LOCATION_PRESETS) {
            LocationPresetsScreen(
                appState = appState,
                onBack = { navController.popBackStack() },
            )
        }

        composable(CxRoutes.SIGN_HISTORY) {
            SignHistoryScreen(
                appState = appState,
                onBack = { navController.popBackStack() },
            )
        }

        composable(CxRoutes.SIGN_WORKSPACE) { backStackEntry ->
            var renderedActivity by remember(backStackEntry.id) { mutableStateOf(activeSignActivity) }
            var isDismissing by remember(backStackEntry.id) { mutableStateOf(false) }
            LaunchedEffect(activeSignActivity) {
                val current = activeSignActivity
                if (current != null && !isDismissing) {
                    renderedActivity = current
                }
            }
            val activity = renderedActivity
            if (activity == null) {
                LaunchedEffect(backStackEntry) {
                    navController.popBackStack(CxRoutes.SIGN_WORKSPACE, inclusive = true)
                }
            } else {
                val dismissWorkspace: () -> Unit = {
                    if (!isDismissing) {
                        isDismissing = true
                        activeSignActivity = null
                        appState.clearDeepLinkActivity()
                        navController.popBackStack(CxRoutes.SIGN_WORKSPACE, inclusive = true)
                    }
                }
                BackHandler(enabled = currentRoute == CxRoutes.SIGN_WORKSPACE && !isDismissing) {
                    dismissWorkspace()
                }
                key(activity.id) {
                    signWorkspaceContent(
                        activity,
                        { navController.navigate(CxRoutes.LOCATION_PRESETS) },
                        dismissWorkspace,
                    )
                }
            }
        }
    }
}

@Composable
private fun RestoringStateView() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(color = CxPalette.accent)
            Text(
                text = "正在恢复本地数据…",
                style = MaterialTheme.typography.bodyMedium,
                color = CxPalette.inkSecondary,
            )
        }
    }
}

@Composable
private fun PersistenceBlockedView(
    errorMessage: String?,
    onRequestRecovery: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 400.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CxIconTile(
                icon = Icons.Rounded.Storage,
                tone = CxTone.CINNABAR,
                size = 52.dp,
            )
            Text(
                text = "无法恢复本地数据",
                style = CxTypography.title,
                color = CxPalette.ink,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "原文件已保留。你可以先备份原文件并重置本地状态，然后重新登录或导入有效备份。",
                style = MaterialTheme.typography.bodyMedium,
                color = CxPalette.inkSecondary,
                textAlign = TextAlign.Center,
            )
            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = CxPalette.cinnabar,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            CxPrimaryButton(
                onClick = onRequestRecovery,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("备份原文件并重置")
            }
        }
    }
}

@Composable
private fun StatusPill(text: String) {
    Surface(
        modifier = Modifier
            .shadow(elevation = 6.dp, shape = CircleShape)
            .border(width = 0.8.dp, color = CxPalette.border, shape = CircleShape),
        shape = CircleShape,
        color = CxPalette.surface,
        contentColor = CxPalette.ink,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(CxPalette.sage),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                color = CxPalette.ink,
            )
        }
    }
}

