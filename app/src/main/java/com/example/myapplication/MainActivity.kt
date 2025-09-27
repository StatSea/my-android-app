package com.example.myapplication

// ⬅️ import
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.example.myapplication.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 🎨 다크모드 색상 정의
val DarkBackground = Color(0xFF1A1A1A)   // 덜 진한 다크톤
val CardBackground = Color(0xFF2A2A2A)   // 박스 배경

// ✅ 데이터 모델
data class AppUsageInfo(
    val appName: String,
    val usageTime: Long
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ✅ API 키 확인 로그
        Log.d("API_KEY", "Key: ${BuildConfig.OPENAI_API_KEY}")

        // ✅ 알림 권한 요청 (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // ✅ 사용량 권한 없으면 설정창으로
        if (!hasUsagePermission(this)) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        // ✅ ForegroundService 실행
        val serviceIntent = Intent(this, UsageTrackingService::class.java)
        startService(serviceIntent)

        setContent {
            MyApplicationTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    NavHost(
                        navController = navController,
                        startDestination = "splash"
                    ) {
                        composable("splash") { SplashScreen(navController) }
                        composable("home") { HomeScreen(navController, this@MainActivity) }
                        composable("usage") { UsageScreen(this@MainActivity, navController) }
                    }
                }
            }
        }
    }

    // ✅ 알림 권한 요청 런처
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            println("❌ 알림 권한 거부됨 - 상단바 알림이 표시되지 않습니다.")
        }
    }
}

// ✅ 권한 확인
fun hasUsagePermission(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = appOps.checkOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        Process.myUid(),
        context.packageName
    )
    return mode == AppOpsManager.MODE_ALLOWED
}

// ✅ 앱 사용량 가져오기
fun getUsageStats(context: Context): List<AppUsageInfo> {
    val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val endTime = System.currentTimeMillis()
    val startTime = endTime - 1000 * 60 * 60 // 최근 1시간

    val stats = usageStatsManager.queryUsageStats(
        UsageStatsManager.INTERVAL_DAILY,
        startTime,
        endTime
    )

    return stats.map {
        AppUsageInfo(
            appName = simplifyAppName(it.packageName),
            usageTime = it.totalTimeInForeground
        )
    }.filter { it.usageTime > 0 }
        .sortedByDescending { it.usageTime }
}

// ✅ 패키지명 마지막 단어만 표시
fun simplifyAppName(packageName: String): String {
    return packageName.substringAfterLast(".")
}

// ✅ 사용량 요약
fun summarizeUsage(usageList: List<AppUsageInfo>): String {
    return usageList.take(5).joinToString(", ") { app ->
        "${app.appName} ${formatTime(app.usageTime)}"
    }
}

// ✅ GPT 코멘트 생성
suspend fun getAiComment(usageList: List<AppUsageInfo>): String {
    return withContext(Dispatchers.IO) {
        try {
            val apiKey = BuildConfig.OPENAI_API_KEY.ifBlank { "sk-발급받은_API_키" }
            val openAI = OpenAI(apiKey)
            val summary = summarizeUsage(usageList)

            val prompt = """
                오늘 앱 사용 기록: $summary
                위 데이터를 바탕으로 하루 사용 습관에 대해 대화하는 것 처럼 짧게 조언해줘.
                예시 : 오늘 틱톡 2시간 넘으셨어요. 내일은 1시간 줄여볼까요?
                데이터 디톡스를 중점으로 앱을 만든거라, 데이터 디톡스를 기반으로 고쳐야 할 점을 말해줘야 해.
                사용한 시간이 많을수록 따끔하게 정곡을 찔러줘
            """.trimIndent()

            val request = ChatCompletionRequest(
                model = ModelId("gpt-3.5-turbo-0125"),
                messages = listOf(ChatMessage(ChatRole.User, prompt)),
                maxTokens = 400,
                temperature = 0.7
            )

            val response = openAI.chatCompletion(request)
            response.choices.first().message?.content ?: "AI 코멘트를 가져오지 못했습니다."
        } catch (e: Exception) {
            Log.e("OpenAI", "오류 발생", e)
            "AI 코멘트 생성 중 오류 발생"
        }
    }
}

// ✅ GPT 추천 활동 생성
suspend fun getRecommendedActivities(): List<String> {
    return withContext(Dispatchers.IO) {
        try {
            val apiKey = BuildConfig.OPENAI_API_KEY.ifBlank { "sk-발급받은_API_키" }
            val openAI = OpenAI(apiKey)

            val prompt = """
                사용자에게 추천할 활동 3개를 제안해줘.
                - 각 활동은 10자 이내
                - 불렛포인트 없이 줄바꿈으로만 구분
                예: 30분 자전거타기, 20분 독서하기, 20분 달리기
            """.trimIndent()

            val request = ChatCompletionRequest(
                model = ModelId("gpt-3.5-turbo-0125"),
                messages = listOf(ChatMessage(ChatRole.User, prompt)),
                maxTokens = 100,
                temperature = 0.7
            )

            val response = openAI.chatCompletion(request)
            val content = response.choices.first().message?.content ?: ""

            content.lines().map { it.trim() }.filter { it.isNotBlank() }.take(3)
        } catch (e: Exception) {
            Log.e("OpenAI", "추천 활동 오류", e)
            listOf("10분 스트레칭", "산책하기", "명상 5분")
        }
    }
}

//
// 🔹 UI Composables
//

@Composable
fun SplashScreen(navController: NavController) {
    LaunchedEffect(Unit) {
        delay(1500)
        navController.navigate("home") {
            popUpTo("splash") { inclusive = true }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.app_logo),
            contentDescription = "앱 로고",
            modifier = Modifier.size(150.dp),
            contentScale = ContentScale.Fit
        )
    }
}

// ✅ 스크롤 가능한 AI 코멘트 박스
@Composable
fun AiCommentBox(comment: String) {
    val scrollState = rememberScrollState()
    val gradientBorder = Brush.linearGradient(
        colors = listOf(
            Color(0xFF9C27B0), // 보라
            Color(0xFFE91E63), // 핑크
            Color(0xFF3F51B5)  // 블루
        )
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .padding(8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(CardBackground)
            .border(2.dp, gradientBorder, RoundedCornerShape(12.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp)
        ) {
            Text("AI 코멘트", style = MaterialTheme.typography.titleMedium, color = Color.White)
            Spacer(Modifier.height(8.dp))
            Text(
                text = comment.ifEmpty { "AI 코멘트를 불러오는 중..." },
                color = Color(0xFFCCCCCC),
                fontSize = 16.sp,
                lineHeight = 22.sp
            )
        }
    }
}

// ✅ 추천 활동 박스 (중앙 정렬 + 그라디언트 테두리)
@Composable
fun RecommendedActivitiesBox(activities: List<String>) {
    val gradientBorder = Brush.linearGradient(
        colors = listOf(
            Color(0xFF9C27B0), // 보라
            Color(0xFFE91E63), // 핑크
            Color(0xFF3F51B5)  // 블루
        )
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        Text("추천 활동", style = MaterialTheme.typography.titleMedium, color = Color.White)

        activities.forEach { activity ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(CardBackground)
                    .border(2.dp, gradientBorder, RoundedCornerShape(12.dp))
                    .padding(16.dp),
                contentAlignment = Alignment.Center   // 중앙 정렬
            ) {
                Text(activity, color = Color.White)
            }
        }
    }
}

@Composable
fun HomeScreen(navController: NavController, context: Context) {
    var aiComment by remember { mutableStateOf("") }
    var activities by remember { mutableStateOf(listOf<String>()) }
    var showContent by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Spacer(Modifier.height(16.dp))

            // ✅ 강조된 "AI 코멘트 보기" 버튼 (흰색 배경 + 검정 글씨 + 알약 스타일)
            Button(
                onClick = {
                    showContent = true
                    val usage = getUsageStats(context)
                    coroutineScope.launch {
                        aiComment = getAiComment(usage)
                        activities = getRecommendedActivities()
                    }
                },
                shape = RoundedCornerShape(50.dp), // 알약 모양
                colors = ButtonDefaults.buttonColors(containerColor = Color.White)
            ) { Text("AI 코멘트 보기", color = Color.Black) }

            Spacer(Modifier.height(16.dp))

            if (showContent) {
                AiCommentBox(comment = aiComment)
                Spacer(Modifier.height(16.dp))
                RecommendedActivitiesBox(activities = activities)
            }
        }

        // ✅ 하단 버튼 고정 (기존 다크톤 유지)
        Row(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { navController.navigate("home") },
                modifier = Modifier.weight(1f).height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CardBackground)
            ) { Text("홈", color = Color.White) }

            Button(
                onClick = { navController.navigate("usage") },
                modifier = Modifier.weight(1f).height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CardBackground)
            ) { Text("사용량", color = Color.White) }
        }
    }
}

@Composable
fun UsageScreen(context: Context, navController: NavController) {
    var usageList by remember { mutableStateOf(listOf<AppUsageInfo>()) }

    LaunchedEffect(Unit) {
        while (true) {
            usageList = getUsageStats(context)
            delay(5000)
        }
    }

    val top5 = usageList.take(5)
    val maxTime = top5.maxOfOrNull { it.usageTime } ?: 1L

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "오늘 앱 사용시간",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentWidth(Alignment.CenterHorizontally)
                    .padding(top = 24.dp)
            )

            Spacer(Modifier.height(16.dp))
            UsageSummaryCard(top5, maxTime)
            Spacer(Modifier.height(8.dp))
            Divider(color = Color.Gray, thickness = 1.dp)
            Spacer(Modifier.height(8.dp))

            Text("전체 앱 사용 기록", style = MaterialTheme.typography.titleMedium, color = Color.White)

            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                items(usageList) { app ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        colors = CardDefaults.cardColors(containerColor = CardBackground)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(app.appName, color = Color.White)
                            Text("사용시간: ${formatTime(app.usageTime)}", color = Color.LightGray)
                        }
                    }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { navController.navigate("home") },
                modifier = Modifier.weight(1f).height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CardBackground)
            ) { Text("홈", color = Color.White) }

            Button(
                onClick = { navController.navigate("usage") },
                modifier = Modifier.weight(1f).height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CardBackground)
            ) { Text("사용량", color = Color.White) }
        }
    }
}

@Composable
fun UsageSummaryCard(top5: List<AppUsageInfo>, maxTime: Long) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("상위 5개 앱", style = MaterialTheme.typography.titleMedium, color = Color.White)
            Spacer(Modifier.height(8.dp))

            top5.forEach { app ->
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(app.appName, color = Color.White)
                        Text(formatTime(app.usageTime), color = Color.Gray)
                    }

                    LinearProgressIndicator(
                        progress = app.usageTime.toFloat() / maxTime.toFloat(),
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = Color(0xFF9C27B0),
                        trackColor = Color(0xFF444444)
                    )
                }
            }
        }
    }
}
