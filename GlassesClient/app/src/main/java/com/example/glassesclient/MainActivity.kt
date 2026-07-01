package com.example.glassesclient

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.glassesclient.theme.GlassesClientTheme
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus

enum class Tab {
    Home, History, Settings
}

class MainActivity : ComponentActivity() {

    // 등록 필요 브로드캐스트 수신기
    private val registrationRequiredReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == GlassesForegroundService.ACTION_REGISTRATION_REQUIRED) {
                // UI에 등록 필요 상태 알림
                WearableState.registrationState = "NEEDS_REGISTRATION"
                Toast.makeText(
                    this@MainActivity,
                    "Meta AI 앱에 이 앱을 등록해야 합니다. '등록하기' 버튼을 눌러주세요.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private val requiredPermissions: Array<String>
        get() {
            val list = mutableListOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                list.add(Manifest.permission.POST_NOTIFICATIONS)
                list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            return list.toTypedArray()
        }

    private val requestMultiplePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            startGlassesService()
        } else {
            WearableState.connectionStatus = "필수 권한이 거부되었습니다!"
            Toast.makeText(this, "블루투스 및 카메라 권한이 글래스 연동에 필요합니다.", Toast.LENGTH_LONG).show()
        }
    }

    // SDK 레벨 글래스 카메라 권한 요청 launcher (Meta AI 앱 통해 승인)
    private val requestWearableCameraPermission = registerForActivityResult(
        Wearables.RequestPermissionContract()
    ) { result ->
        val status = result.getOrNull()
        android.util.Log.d("MainActivity", "SDK 카메라 권한 결과: $status (result=$result)")
        if (status is PermissionStatus.Granted) {
            Toast.makeText(this, "글래스 카메라 권한이 승인되었습니다!", Toast.LENGTH_SHORT).show()
            WearableState.needsWearableCameraPermission = false
        } else {
            Toast.makeText(this, "글래스 카메라 권한이 거부되었습니다. 다시 시도해주세요.", Toast.LENGTH_LONG).show()
            WearableState.connectionStatus = "글래스 카메라 권한 거부됨. '카메라 권한 허용' 버튼을 다시 눌러주세요."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 등록 필요 브로드캐스트 수신 등록
        val filter = IntentFilter(GlassesForegroundService.ACTION_REGISTRATION_REQUIRED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(registrationRequiredReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(registrationRequiredReceiver, filter)
        }

        setContent {
            GlassesClientTheme {
                // SDK 카메라 권한이 필요할 때 자동으로 요청 트리거
                val needsWearablePerm = WearableState.needsWearableCameraPermission
                LaunchedEffect(needsWearablePerm) {
                    if (needsWearablePerm) {
                        requestWearableCameraPermission.launch(Permission.CAMERA)
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0B0F19)
                ) {
                    var currentTab by remember { mutableStateOf(Tab.Home) }

                    GlassesControlScreen(
                        currentTab = currentTab,
                        onTabChange = { currentTab = it },
                        status = WearableState.connectionStatus,
                        glassesConnected = WearableState.isGlassesConnected,
                        connectedGlassesName = WearableState.connectedGlassesName,
                        registrationState = WearableState.registrationState,
                        qrResult = WearableState.qrScanResult,
                        isScanning = WearableState.isScanning,
                        onRegisterClick = {
                            GlassesForegroundService.instance?.triggerRegistration(this)
                                ?: run {
                                    // 서비스가 아직 실행 안 됐을 경우 서비스 시작 후 등록
                                    startGlassesService()
                                }
                        },
                        onResumeScan = { GlassesForegroundService.instance?.resumeScanning() }
                    )
                }
            }
        }

        checkAndRequestPermissions()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(registrationRequiredReceiver)
    }

    private fun checkAndRequestPermissions() {
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            startGlassesService()
        } else {
            requestMultiplePermissions.launch(missingPermissions.toTypedArray())
        }
    }

    private fun startGlassesService() {
        val intent = Intent(this, GlassesForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassesControlScreen(
    currentTab: Tab,
    onTabChange: (Tab) -> Unit,
    status: String,
    glassesConnected: Boolean,
    connectedGlassesName: String?,
    registrationState: String,
    qrResult: String?,
    isScanning: Boolean,
    onRegisterClick: () -> Unit,
    onResumeScan: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "롯데백화점 행사안내 스마트글래스 App.",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF111827)
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF111827),
                contentColor = Color.White
            ) {
                NavigationBarItem(
                    selected = currentTab == Tab.Home,
                    onClick = { onTabChange(Tab.Home) },
                    icon = { Icon(Icons.Default.Home, contentDescription = "홈") },
                    label = { Text("홈") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFF10B981),
                        selectedTextColor = Color(0xFF10B981),
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray,
                        indicatorColor = Color(0xFF1F2937)
                    )
                )
                NavigationBarItem(
                    selected = currentTab == Tab.History,
                    onClick = { onTabChange(Tab.History) },
                    icon = { Icon(Icons.Default.List, contentDescription = "내역") },
                    label = { Text("내역") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFF10B981),
                        selectedTextColor = Color(0xFF10B981),
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray,
                        indicatorColor = Color(0xFF1F2937)
                    )
                )
                NavigationBarItem(
                    selected = currentTab == Tab.Settings,
                    onClick = { onTabChange(Tab.Settings) },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "설정") },
                    label = { Text("설정") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFF10B981),
                        selectedTextColor = Color(0xFF10B981),
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray,
                        indicatorColor = Color(0xFF1F2937)
                    )
                )
            }
        },
        containerColor = Color(0xFF0B0F19)
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (currentTab) {
                Tab.Home -> {
                    HomeTab(
                        status = status,
                        glassesConnected = glassesConnected,
                        connectedGlassesName = connectedGlassesName,
                        registrationState = registrationState,
                        qrResult = qrResult,
                        isScanning = isScanning,
                        onRegisterClick = onRegisterClick,
                        onResumeScan = onResumeScan
                    )
                }
                Tab.History -> {
                    HistoryTab()
                }
                Tab.Settings -> {
                    SettingsTab()
                }
            }
        }
    }
}

@Composable
fun HomeTab(
    status: String,
    glassesConnected: Boolean,
    connectedGlassesName: String?,
    registrationState: String,
    qrResult: String?,
    isScanning: Boolean,
    onRegisterClick: () -> Unit,
    onResumeScan: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    androidx.compose.foundation.lazy.LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── 앱 ON/OFF 스위치 + 연결 상태 카드
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2937)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 앱 ON/OFF 스위치
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "스캐너 서비스 (App ON/OFF)",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Switch(
                            checked = WearableState.isServiceActive,
                            onCheckedChange = { active ->
                                if (active) {
                                    val intent = Intent(context, GlassesForegroundService::class.java)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        context.startForegroundService(intent)
                                    } else {
                                        context.startService(intent)
                                    }
                                } else {
                                    GlassesForegroundService.instance?.stopServiceAndCleanup()
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF10B981),
                                checkedTrackColor = Color(0xFF064E3B),
                                uncheckedThumbColor = Color.Gray,
                                uncheckedTrackColor = Color.DarkGray
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = Color.DarkGray, thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "연결 및 스트림 상태",
                        color = Color.Gray,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val indicatorColor = when {
                            !WearableState.isServiceActive -> Color.Gray
                            status.contains("활성화") || status.contains("대기 중") || status.contains("완료") || status.contains("재생 중") -> Color(0xFF10B981)
                            status.contains("일시정지") -> Color(0xFF3B82F6)
                            status.contains("실패") || status.contains("오류") || status.contains("거부") -> Color(0xFFEF4444)
                            else -> Color(0xFFF59E0B)
                        }

                        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                        val alpha by infiniteTransition.animateFloat(
                            initialValue = 0.4f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1000),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "alpha"
                        )

                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(indicatorColor)
                                .alpha(alpha)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (!WearableState.isServiceActive) "스캐너 서비스 비활성화됨 (App OFF)" else status,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // ── 글래스 연결 상태 카드 (실기기)
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (glassesConnected) Color(0xFF064E3B) else Color(0xFF1F2937)
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "🕶️ Meta 글래스 연결 확인",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (glassesConnected) "연결됨 ✅" else "미연결 ⭕",
                            color = if (glassesConnected) Color(0xFF6EE7B7) else Color.Gray,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (glassesConnected && connectedGlassesName != null) {
                        Text(
                            text = "기기명: $connectedGlassesName",
                            color = Color(0xFFA7F3D0),
                            fontSize = 13.sp
                        )
                    }

                    // 등록이 필요한 경우 등록 버튼 표시
                    val needsRegistration = registrationState == "NEEDS_REGISTRATION" ||
                        registrationState == "UNAVAILABLE" ||
                        registrationState == "AVAILABLE"

                    if (!glassesConnected && WearableState.isServiceActive) {
                        Spacer(modifier = Modifier.height(4.dp))
                        if (needsRegistration) {
                            Text(
                                text = "⚠️ Meta AI 앱에 이 앱을 등록해야 글래스와 연결할 수 있습니다.",
                                color = Color(0xFFFCD34D),
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = onRegisterClick,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B)),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Meta AI 앱에 등록하기", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Text(
                                text = "글래스 전원을 켜고 블루투스가 연결되어 있는지 확인해주세요.",
                                color = Color.LightGray,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }

        // ── 실시간 글래스 카메라 프리뷰 (연결 시에만 표시)
        if (WearableState.isServiceActive && glassesConnected) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "📷 실시간 글래스 카메라 뷰",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        val frame = WearableState.cameraFrame
                        if (frame != null) {
                            Image(
                                bitmap = frame.asImageBitmap(),
                                contentDescription = "글래스 카메라 실시간 뷰",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(220.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.Black),
                                contentScale = ContentScale.Fit
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(220.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF1F2937)),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = Color(0xFF10B981))
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "글래스 카메라 프레임 수신 대기 중...",
                                        color = Color.Gray,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "글래스 카메라가 QR 코드를 향하도록 조준하세요",
                            color = Color(0xFF6EE7B7),
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        // ── 레이더 스캔 애니메이션
        item {
            Spacer(modifier = Modifier.height(8.dp))
            RadarAnimation(isScanning = isScanning && glassesConnected && WearableState.isServiceActive)
            Spacer(modifier = Modifier.height(8.dp))
        }

        // ── QR 스캔 결과 카드
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (qrResult != null) Color(0xFF065F46) else Color(0xFF1F2937)
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "QR 코드 스캔 결과",
                        color = if (qrResult != null) Color(0xFFA7F3D0) else Color.Gray,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    if (qrResult != null) {
                        Text(
                            text = qrResult,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        Button(
                            onClick = onResumeScan,
                            enabled = WearableState.isServiceActive,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("다시 스캔하기")
                        }
                    } else {
                        Text(
                            text = when {
                                !WearableState.isServiceActive -> "스캐너 서비스 비활성화됨"
                                !glassesConnected -> "글래스가 연결되어 있지 않습니다"
                                isScanning -> "글래스 카메라를 행사 QR 코드에 비춰주세요..."
                                else -> "카메라 스캐너 비활성화됨"
                            },
                            color = Color.LightGray,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }
        }

        // ── 사용 가이드 카드
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2937)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                var isExpanded by remember { mutableStateOf(false) }
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isExpanded = !isExpanded },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "💡 스마트 글래스 사용 가이드",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (isExpanded) "접기 ▲" else "펼치기 ▼",
                            color = Color(0xFF10B981),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (isExpanded) {
                        Spacer(modifier = Modifier.height(4.dp))
                        HorizontalDivider(color = Color.DarkGray, thickness = 0.5.dp)
                        Spacer(modifier = Modifier.height(4.dp))

                        GuideStep(step = "1", title = "Meta AI 앱 준비", desc = "핸드폰에 Meta AI 앱을 설치하고 Ray-Ban Meta 글래스를 페어링해주세요. 앱 정보에서 버전을 5번 탭하여 개발자 모드를 활성화하세요.")
                        GuideStep(step = "2", title = "스캐너 서비스 켜기", desc = "상단 스위치를 ON으로 켜면 백그라운드 서비스가 글래스 연동을 준비합니다.")
                        GuideStep(step = "3", title = "앱 등록 (최초 1회)", desc = "'Meta AI 앱에 등록하기' 버튼을 누르면 Meta AI 앱으로 이동합니다. 승인을 완료하면 글래스와 자동으로 연결됩니다.")
                        GuideStep(step = "4", title = "글래스 카메라 프리뷰 확인", desc = "연결 완료 후 화면의 실시간 프리뷰에서 글래스 카메라 시야를 확인할 수 있습니다.")
                        GuideStep(step = "5", title = "QR 코드 비추기", desc = "행사 안내판의 QR 코드에 글래스 카메라를 향하면 자동으로 인식합니다.")
                        GuideStep(step = "6", title = "음성 및 디스플레이 번역 확인", desc = "인식 완료 시 글래스 렌즈에 번역 텍스트가 표시되고, 글래스 스피커로 한국어 음성 안내가 재생됩니다.")
                    }
                }
            }
        }
    }
}

@Composable
fun GuideStep(step: String, title: String, desc: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(Color(0xFF10B981)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = step,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(desc, color = Color.LightGray, fontSize = 12.sp)
        }
    }
}

@Composable
fun RadarAnimation(
    modifier: Modifier = Modifier,
    isScanning: Boolean = true
) {
    val transition = rememberInfiniteTransition(label = "RadarTransition")

    val sweepAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweepAngle"
    )

    val pulseScale1 by transition.animateFloat(
        initialValue = 0.1f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = androidx.compose.animation.core.LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseScale1"
    )

    val pulseScale2 by transition.animateFloat(
        initialValue = 0.1f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, delayMillis = 1000, easing = androidx.compose.animation.core.LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseScale2"
    )

    Canvas(
        modifier = modifier.size(200.dp)
    ) {
        val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
        val maxRadius = size.minDimension / 2

        drawCircle(color = Color(0xFF10B981).copy(alpha = 0.1f), radius = maxRadius * 0.3f, center = center, style = Stroke(width = 1.dp.toPx()))
        drawCircle(color = Color(0xFF10B981).copy(alpha = 0.15f), radius = maxRadius * 0.6f, center = center, style = Stroke(width = 1.dp.toPx()))
        drawCircle(color = Color(0xFF10B981).copy(alpha = 0.2f), radius = maxRadius * 0.9f, center = center, style = Stroke(width = 1.5.dp.toPx()))

        drawLine(color = Color(0xFF10B981).copy(alpha = 0.1f), start = androidx.compose.ui.geometry.Offset(center.x - maxRadius, center.y), end = androidx.compose.ui.geometry.Offset(center.x + maxRadius, center.y), strokeWidth = 1.dp.toPx())
        drawLine(color = Color(0xFF10B981).copy(alpha = 0.1f), start = androidx.compose.ui.geometry.Offset(center.x, center.y - maxRadius), end = androidx.compose.ui.geometry.Offset(center.x, center.y + maxRadius), strokeWidth = 1.dp.toPx())

        if (isScanning) {
            drawCircle(color = Color(0xFF10B981).copy(alpha = (1.0f - pulseScale1) * 0.4f), radius = maxRadius * pulseScale1, center = center)
            drawCircle(color = Color(0xFF10B981).copy(alpha = (1.0f - pulseScale2) * 0.4f), radius = maxRadius * pulseScale2, center = center)
            drawArc(
                brush = Brush.sweepGradient(
                    colors = listOf(Color(0xFF10B981).copy(alpha = 0f), Color(0xFF10B981).copy(alpha = 0.4f), Color(0xFF10B981).copy(alpha = 0f)),
                    center = center
                ),
                startAngle = sweepAngle - 45f,
                sweepAngle = 45f,
                useCenter = true
            )
        }

        drawCircle(color = Color(0xFF10B981), radius = 8.dp.toPx(), center = center)
        drawCircle(color = Color(0xFF10B981).copy(alpha = 0.3f), radius = 14.dp.toPx(), center = center)
    }
}

@Composable
fun HistoryTab() {
    val history = WearableState.scanHistory
    if (history.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("스캔 내역 없음", color = Color.Gray, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text("홈 탭에서 QR 코드를 스캔하면 이곳에 기록이 표시됩니다.", color = Color.DarkGray, fontSize = 13.sp)
            }
        }
    } else {
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(history.size) { index ->
                val item = history[index]
                HistoryCard(item)
            }
        }
    }
}

@Composable
fun HistoryCard(item: HistoryItem) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2937)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "UUID: ${item.id.take(8)}...",
                    color = Color(0xFF10B981),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    text = item.timestamp,
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "QR 데이터: ${item.qrText}",
                color = Color.LightGray,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(6.dp))
            HorizontalDivider(color = Color.DarkGray, thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = item.translatedText,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun SettingsTab() {
    var urlText by remember { mutableStateOf(WearableState.backendUrl) }
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            text = "앱 설정",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2937)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "백엔드 서버 설정",
                    color = Color.LightGray,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )

                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    label = { Text("로컬터널(Localtunnel) URL", color = Color.Gray) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF10B981),
                        unfocusedBorderColor = Color.Gray,
                        cursorColor = Color(0xFF10B981)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = {
                        WearableState.backendUrl = urlText.trim()
                        Toast.makeText(context, "로컬터널 URL이 업데이트되었습니다!", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(text = "설정 저장")
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("시스템 정보", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("연동 모드: 실기기 연동 (AutoDeviceSelector)", color = Color.Gray, fontSize = 12.sp)
                Text("Meta Wearables DAT 버전: mwdat-core:0.7.0", color = Color.Gray, fontSize = 12.sp)
                Text("글래스 등록 상태: ${WearableState.registrationState}", color = Color.Gray, fontSize = 12.sp)
            }
        }
    }
}
