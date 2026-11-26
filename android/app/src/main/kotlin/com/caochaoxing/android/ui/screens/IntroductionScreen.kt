package com.caochaoxing.android.ui.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.caochaoxing.android.R
import com.caochaoxing.android.ui.theme.CxPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class IntroductionPage(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val features: List<String>,
)

private val introductionPages = listOf(
    IntroductionPage(
        "欢迎使用\nCaochaoxing",
        "连接学习通账号，集中查看课程、作业和课堂活动。",
        Icons.AutoMirrored.Outlined.MenuBook,
        listOf("课程与待办", "多账号管理"),
    ),
    IntroductionPage(
        "AI 答题\n让思路更清楚",
        "识别题目、题图和公式，生成答案与解析。核对后可编辑、复制或回填。",
        Icons.Outlined.AutoAwesome,
        listOf("图文与公式", "编辑与回填"),
    ),
    IntroductionPage(
        "刷课系统\n学习进度随时看",
        "查看视频、文档和章节测验。按课程开始或停止任务，跟踪进度和运行记录。",
        Icons.Outlined.PlayCircleOutline,
        listOf("章节任务", "进度同步"),
    ),
    IntroductionPage(
        "签到系统\n课堂活动及时处理",
        "查看课堂活动，支持二维码、拍照、位置、手势和签到码。还可从相册识图。",
        Icons.Outlined.QrCodeScanner,
        listOf("活动查询", "多种签到方式"),
    ),
)

/** 首次介绍与账号数据分开保存，退出账号后也不会再次显示。 */
@Composable
internal fun FirstRunIntroduction(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("introduction", Context.MODE_PRIVATE) }
    var completed by remember { mutableStateOf(preferences.getBoolean("completed", false)) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    if (completed) {
        content()
    } else {
        IntroductionScreen(saving, error) {
            if (!saving) {
                saving = true
                scope.launch {
                    val saved = withContext(Dispatchers.IO) {
                        runCatching { preferences.edit().putBoolean("completed", true).commit() }.getOrDefault(false)
                    }
                    saving = false
                    if (saved) completed = true else error = "保存失败，请重试"
                }
            }
        }
    }
}

@Composable
private fun IntroductionScreen(saving: Boolean, error: String?, onFinish: () -> Unit) {
    val pager = rememberPagerState(pageCount = { introductionPages.size })
    val scope = rememberCoroutineScope()
    val lastPage = pager.currentPage == introductionPages.lastIndex
    BackHandler(enabled = pager.currentPage > 0) {
        scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
    }
    Column(
        Modifier.fillMaxSize().background(CxPalette.canvas).safeDrawingPadding()
            .widthIn(max = 560.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.School, null, tint = CxPalette.accent, modifier = Modifier.size(22.dp))
                Text("Caochaoxing", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = CxPalette.ink)
            }
            TextButton(onClick = onFinish, enabled = !saving) {
                Text("跳过", fontSize = 14.sp, color = CxPalette.inkSecondary)
            }
        }
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalAlignment = Alignment.Top,
            userScrollEnabled = !saving,
        ) { index ->
            val page = introductionPages[index]
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp),
            ) {
                Spacer(Modifier.height(12.dp))
                IntroductionIllustration(index, page.icon)
                Spacer(Modifier.height(24.dp))
                Text(page.title, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, color = CxPalette.ink)
                Spacer(Modifier.height(14.dp))
                Text(page.description, fontSize = 14.sp, lineHeight = 22.sp, color = CxPalette.inkSecondary)
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    page.features.forEach { title ->
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Outlined.CheckCircleOutline, null, tint = CxPalette.accent, modifier = Modifier.size(18.dp))
                            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = CxPalette.ink)
                        }
                    }
                }
                if (index == 1) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "使用前需配置模型接口、Key 和模型。题目与题图会发送到你配置的模型服务。",
                        fontSize = 12.sp, lineHeight = 18.sp, color = CxPalette.inkSecondary,
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp).padding(top = 12.dp, bottom = 16.dp)) {
            Row(
                Modifier.align(Alignment.CenterHorizontally).semantics {
                    contentDescription = "第 ${pager.currentPage + 1} 页，共 4 页"
                },
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(introductionPages.size) { index ->
                    Box(Modifier.size(width = if (index == pager.currentPage) 22.dp else 6.dp, height = 6.dp)
                        .background(if (index == pager.currentPage) CxPalette.accent else CxPalette.border, CircleShape))
                }
            }
            error?.let {
                Text(it, modifier = Modifier.padding(top = 8.dp), fontSize = 12.sp, color = CxPalette.cinnabar)
            }
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = {
                    if (lastPage) onFinish() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                enabled = !saving && !pager.isScrollInProgress,
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(if (saving) "正在进入…" else if (lastPage) "开始使用" else "下一页", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(10.dp))
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun IntroductionIllustration(index: Int, icon: ImageVector) {
    Column(
        Modifier.fillMaxWidth().height(188.dp)
            .background(CxPalette.surfaceSecondary, RoundedCornerShape(28.dp)).padding(18.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(64.dp).background(CxPalette.accent, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (index == 0) {
                Image(painterResource(R.drawable.ic_launcher_foreground), null,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)))
            } else {
                Icon(icon, null, tint = CxPalette.onAccent, modifier = Modifier.size(40.dp))
            }
        }
        when (index) {
            0 -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                IllustrationLabel(Icons.AutoMirrored.Outlined.MenuBook, "课程")
                IllustrationLabel(Icons.AutoMirrored.Outlined.Assignment, "作业")
                IllustrationLabel(Icons.Outlined.EventAvailable, "活动")
            }
            1 -> Column(Modifier.fillMaxWidth().background(CxPalette.surface, RoundedCornerShape(14.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("x + 2 = 5", fontSize = 14.sp, color = CxPalette.ink)
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = CxPalette.accent, modifier = Modifier.size(18.dp))
                    Text("x = 3", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = CxPalette.accent)
                }
                Text("示例解析  两边减去 2，得到答案。", fontSize = 12.sp, color = CxPalette.inkSecondary)
            }
            2 -> Column(Modifier.fillMaxWidth().background(CxPalette.surface, RoundedCornerShape(14.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("章节学习", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = CxPalette.ink)
                    Icon(Icons.Outlined.OndemandVideo, null, tint = CxPalette.accent, modifier = Modifier.size(20.dp))
                }
                LinearProgressIndicator(progress = { 0.6f }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                    color = CxPalette.accent, trackColor = CxPalette.surfaceSecondary, drawStopIndicator = {})
            }
            else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                IllustrationLabel(Icons.Outlined.PhotoCamera, "拍照")
                IllustrationLabel(Icons.Outlined.LocationOn, "位置")
                IllustrationLabel(Icons.Outlined.Gesture, "手势")
            }
        }
    }
}

@Composable
private fun IllustrationLabel(icon: ImageVector, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Icon(icon, null, tint = CxPalette.accent, modifier = Modifier.size(24.dp))
        Text(label, fontSize = 12.sp, color = CxPalette.inkSecondary)
    }
}
