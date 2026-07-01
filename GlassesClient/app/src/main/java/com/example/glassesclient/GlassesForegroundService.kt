package com.example.glassesclient

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addStream          // extension fun: DeviceSession.addStream(...)
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
// import com.meta.wearable.dat.display.Display
// import com.meta.wearable.dat.display.addDisplay         // extension fun: DeviceSession.addDisplay(...)
// MockDeviceKit은 현재 미사용 (실제 Meta AI 앱 등록 흐름 사용)
// import com.meta.wearable.dat.mockdevice.MockDeviceKit
// import com.meta.wearable.dat.mockdevice.api.MockDeviceKitConfig
// import com.meta.wearable.dat.display.types.DisplayConfiguration
// import com.meta.wearable.dat.display.views.Direction
// import com.meta.wearable.dat.display.views.TextStyle
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.File
import java.io.FileOutputStream
import android.speech.tts.TextToSpeech
import android.os.Bundle

class GlassesForegroundService : Service(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private var activeStream: Stream? = null
    private var activeDisplay: Any? = null
    private var activeSession: com.meta.wearable.dat.core.session.Session? = null
    private var mediaPlayer: MediaPlayer? = null
    @Volatile
    private var isSpeechPlaying = false

    inner class LocalBinder : Binder() {
        fun getService(): GlassesForegroundService = this@GlassesForegroundService
    }

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        instance = this
        WearableState.isServiceActive = true
        WearableState.isScanning = true
        WearableState.qrScanResult = null
        
        // Google TTS 엔진으로 강제 지정하여 품질 및 정확도 극대화
        tts = TextToSpeech(this, this, "com.google.android.tts")
        
        createNotificationChannel()
        startServiceForeground()
        setupWearablesSDK()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.KOREAN
            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.d(TAG, "TTS 발화 시작: $utteranceId")
                    isSpeechPlaying = true
                }
                override fun onDone(utteranceId: String?) {
                    // onDone은 TTS 엔진이 발화를 완전히 끝낸 시점에 호출됨.
                    // 블루투스 A2DP 버퍼 전송 완료를 위해 고정 1.5초 대기 후 종료 처리.
                    Log.d(TAG, "TTS 발화 완료: $utteranceId — 블루투스 버퍼 대기 1.5초")
                    serviceScope.launch(Dispatchers.Main) {
                        kotlinx.coroutines.delay(1500L)
                        isSpeechPlaying = false
                        onSpeechFinished()
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    Log.e(TAG, "TTS 에러: $utteranceId")
                    serviceScope.launch(Dispatchers.Main) {
                        kotlinx.coroutines.delay(500)
                        isSpeechPlaying = false
                        onSpeechFinished()
                    }
                }
            })
            Log.d(TAG, "Android Native TTS 초기화 성공")
        } else {
            Log.e(TAG, "Android Native TTS 초기화 실패")
        }
    }

    private fun sanitizeText(text: String): String {
        return text
            // 1. HTML 태그 제거
            .replace(Regex("<[^>]*>"), "")
            // 2. 불가시 유니코드 전체 제거 (범위 확장)
            //    \u00AD 소프트 하이픈
            //    \u200B ZWSP, \u200C ZWNJ, \u200D ZWJ, \u200E LRM, \u200F RLM
            //    \u2028 LS, \u2029 PS
            //    \u202A-\u202F 방향 제어 문자
            //    \uFEFF BOM
            .replace(Regex("[\u00AD\u200B-\u200F\u2028\u2029\u202A-\u202F\uFEFF]"), "")
            // 3. 다중 공백 단일 공백으로 치환
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun speakText(text: String, langCode: String = "ko-KR") {
        try {
            stopAudio(keepSpeechPlayingState = true)
            isSpeechPlaying = true

            // 오디오 텍스트 정제
            val cleanText = sanitizeText(text)
            if (cleanText.isBlank()) {
                isSpeechPlaying = false
                onSpeechFinished()
                return
            }

            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.mode = AudioManager.MODE_NORMAL

            // 번역 언어에 맞춰 TTS 언어 동적 설정
            val locale = Locale.forLanguageTag(langCode)
            val result = tts?.setLanguage(locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "TTS 언어가 지원되지 않거나 데이터가 없습니다 ($langCode). 기본 한국어로 시도합니다.")
                tts?.language = Locale.KOREAN
            }

            // 발음의 선명도를 위해 말하기 속도 조절
            tts?.setSpeechRate(0.92f)
            tts?.setPitch(1.0f)

            Log.i(TAG, "Android Native TTS 음성 출력 시작 (언어: $langCode): $cleanText")
            val params = Bundle().apply {
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            }
            tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, params, "GlassesTTS")
        } catch (e: Exception) {
            Log.e(TAG, "TTS 재생 실패", e)
            isSpeechPlaying = false
            WearableState.connectionStatus = "TTS 재생 오류 발생"
            serviceScope.launch {
                kotlinx.coroutines.delay(3000)
                onSpeechFinished()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        
        // TTS 리소스 해제
        tts?.stop()
        tts?.shutdown()
        tts = null
        
        // 현재 인스턴스가 활성 인스턴스인 경우에만 공유 상태 및 레퍼런스 정리
        if (instance == this) {
            stopAudio()
            WearableState.isServiceActive = false
            WearableState.isScanning = false
            instance = null
        } else {
            Log.d(TAG, "이전 서비스 인스턴스의 onDestroy 호출됨 — 상태 초기화 건너뜀")
        }
        
        serviceJob.cancel()
    }



    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "스마트 글래스 서비스 채널",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "백그라운드 카메라 스트리밍 및 QR 코드 감지 실행 중"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun startServiceForeground() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else
                PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AI 글래스 스캐너 활성")
            .setContentText("스마트 글래스 연결 대기 중 - 백그라운드 스캔 활성")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun setupWearablesSDK() {
        WearableState.connectionStatus = "SDK 초기화 중..."
        try {
            Wearables.initialize(applicationContext)

            serviceScope.launch {
                // 등록 상태 실시간 감시
                launch {
                    Wearables.registrationState.collect { state ->
                        val stateStr = when (state) {
                            is com.meta.wearable.dat.core.types.RegistrationState.Registered -> "REGISTERED"
                            is com.meta.wearable.dat.core.types.RegistrationState.Available -> "AVAILABLE"
                            is com.meta.wearable.dat.core.types.RegistrationState.Registering -> "REGISTERING"
                            is com.meta.wearable.dat.core.types.RegistrationState.Unregistering -> "UNREGISTERING"
                            is com.meta.wearable.dat.core.types.RegistrationState.Unavailable -> "UNAVAILABLE"
                            else -> "UNKNOWN"
                        }
                        WearableState.registrationState = stateStr
                        Log.d(TAG, "현재 등록 상태: $stateStr")
                    }
                }
                waitForRegistrationAndConnect()
            }

        } catch (e: Exception) {
            WearableState.connectionStatus = "SDK 초기화 오류: ${e.message}"
            Log.e(TAG, "setupWearablesSDK 오류", e)
        }
    }

    private suspend fun waitForRegistrationAndConnect() {
        try {
            // 현재 등록 상태 즉시 확인
            val currentState = Wearables.registrationState.first()
            Log.d(TAG, "초기 등록 상태 확인: $currentState")

            if (currentState !is com.meta.wearable.dat.core.types.RegistrationState.Registered) {
                // 미등록 상태 → UI에 등록 버튼 표시
                Log.d(TAG, "미등록 상태($currentState) — 등록 필요. UI에 등록 버튼 표시")
                WearableState.registrationState = "NEEDS_REGISTRATION"
                WearableState.connectionStatus = "Meta AI 앱에 앱 등록이 필요합니다.\n'등록하기' 버튼을 눌러주세요."
                sendRegistrationRequiredBroadcast()
            }

            // Registered 상태까지 대기 (버튼을 누른 후 등록이 완료될 때까지)
            Wearables.registrationState.first { state ->
                state is com.meta.wearable.dat.core.types.RegistrationState.Registered
            }
            WearableState.connectionStatus = "등록 완료! 글래스 기기 탐색 중..."
            waitForDeviceAndConnect()
        } catch (e: Exception) {
            WearableState.connectionStatus = "등록 대기 중 오류: ${e.message}"
            Log.e(TAG, "waitForRegistrationAndConnect 오류", e)
        }
    }

    private suspend fun waitForDeviceAndConnect() {
        try {
            WearableState.connectionStatus = "블루투스로 연결된 글래스 탐색 중..."

            // 기기가 한 개 이상 감지될 때까지 대기
            val deviceIds = Wearables.devices.first { it.isNotEmpty() }
            Log.d(TAG, "감지된 기기 수: ${deviceIds.size}")

            // 첫 번째 기기 이름 가져오기
            val firstDeviceId = deviceIds.first()
            WearableState.connectionStatus = "글래스 감지됨. 연결 수립 대기 중..."

            val deviceFlow = Wearables.devicesMetadata[firstDeviceId]
            if (deviceFlow != null) {
                // linkState가 CONNECTED가 될 때까지 대기
                val connectedDevice = deviceFlow.first { device ->
                    WearableState.connectedGlassesName = device.name
                    Log.d(TAG, "기기 상태 감지: ${device.name}, 링크 상태: ${device.linkState}, 호환성: ${device.compatibility}, 타입: ${device.deviceType}")
                    device.linkState == com.meta.wearable.dat.core.types.LinkState.CONNECTED
                }
                Log.d(TAG, "글래스 연결 확인됨: ${connectedDevice.name}")
            }

            // ── 소켓 충돌 방지: Meta View가 ACDC 소켓을 해제할 시간을 충분히 줌 ──
            // com.facebook.stella (Meta View/Meta AI 앱)이 Bluetooth LOW 소켓을 점유하다가
            // 백그라운드 전환 시 해제합니다. 너무 빨리 접속하면 socket abruptly closed 발생.
            WearableState.connectionStatus = "글래스 연결됨. 세션 초기화 준비 중... (3초 대기)"
            Log.d(TAG, "Meta View 소켓 해제 대기 중 (3초)...")
            kotlinx.coroutines.delay(3000)

            WearableState.connectionStatus = "글래스 연결됨. 세션 생성 중..."
            createRealDeviceSession(firstDeviceId)

        } catch (t: Throwable) {
            WearableState.connectionStatus = "글래스 탐색 중 오류: ${t.message}"
            Log.e(TAG, "waitForDeviceAndConnect 오류 발생", t)
        }
    }


    private suspend fun createRealDeviceSession(deviceId: com.meta.wearable.dat.core.types.DeviceIdentifier) {
        Log.d(TAG, "createRealDeviceSession 진입: deviceId = $deviceId")
        try {
            // ── 기존 세션이 살아있는지 먼저 확인 ──────────────────────────────────
            // 음성 재생 후 resumeScanning()에서 세션 상태 확인이 타이밍상 빗나가
            // 이 함수까지 진입하는 경우, 세션을 새로 만들면 SESSION_ALREADY_EXISTS 에러 발생.
            // 기존 세션이 STARTED 상태면 세션 생성을 건너뛰고 스트림만 재시작한다.
            val existingSession = activeSession
            if (existingSession != null) {
                val existingState = try { existingSession.state.first() } catch (e: Exception) { null }
                Log.d(TAG, "기존 activeSession 상태 확인: $existingState")
                if (existingState == DeviceSessionState.STARTED) {
                    Log.d(TAG, "기존 세션이 STARTED 상태 — 세션 재사용, 스트림만 재시작")
                    WearableState.isGlassesConnected = true
                    startStreamOnSession(existingSession, isResuming = true)
                    return
                } else {
                    // 세션이 있지만 종료된 상태 — 참조만 정리하고 새로 생성
                    Log.d(TAG, "기존 세션 종료 상태($existingState) — 참조 정리 후 새 세션 생성")
                    activeSession = null
                    activeStream = null
                }
            }

            var retries = 5
            var sessionCreated = false
            
            // AutoDeviceSelector 인스턴스를 한 번만 생성하여 백그라운드 탐색을 유지
            val deviceSelector = AutoDeviceSelector()

            while (retries > 0 && !sessionCreated) {
                val attempt = 6 - retries
                WearableState.connectionStatus = "실 글래스 세션 연결 중... (시도 $attempt/5)"
                Log.d(TAG, "세션 연결 시도 중... (시도 $attempt/5)")

                val sessionResult = Wearables.createSession(deviceSelector)
                val session = sessionResult.getOrNull()
                val sessionError = sessionResult.errorOrNull()
                Log.d(TAG, "세션 생성 결과: session=$session, error=$sessionError")

                // SESSION_ALREADY_EXISTS 에러 처리 — 기존 activeSession을 재사용 시도
                if (session == null && sessionError?.toString()?.contains("SESSION_ALREADY_EXISTS", ignoreCase = true) == true) {
                    Log.w(TAG, "SESSION_ALREADY_EXISTS — 기존 세션 재사용 시도")
                    val fallbackSession = activeSession
                    if (fallbackSession != null) {
                        val fallbackState = try { fallbackSession.state.first() } catch (e: Exception) { null }
                        if (fallbackState == DeviceSessionState.STARTED) {
                            Log.d(TAG, "기존 세션 재사용 성공 (STARTED) — 스트림 재시작")
                            WearableState.isGlassesConnected = true
                            startStreamOnSession(fallbackSession, isResuming = true)
                            return
                        }
                    }
                    // 재사용 불가 — 잠시 대기 후 재시도
                    Log.w(TAG, "SESSION_ALREADY_EXISTS이지만 재사용 불가 — 2초 후 재시도")
                    retries--
                    kotlinx.coroutines.delay(2000)
                    continue
                }

                if (session != null) {
                    activeSession = session

                    // ── session.start() 및 STARTED 대기 (타임아웃 10초) ────────────
                    session.start()
                    Log.d(TAG, "session.start() 호출됨. STARTED 상태 대기 중 (최대 10초)...")
                    WearableState.connectionStatus = "세션 수립 확인 중..."

                    val startResult = runCatching {
                        kotlinx.coroutines.withTimeout(10_000L) {
                            session.state.first { state ->
                                Log.d(TAG, "세션 상태 변화 감지: $state")
                                state == DeviceSessionState.STARTED ||
                                state == DeviceSessionState.STOPPED
                            }
                        }
                    }

                    val reachedState = startResult.getOrNull()
                    if (startResult.isFailure || reachedState == DeviceSessionState.STOPPED) {
                        val err = startResult.exceptionOrNull()?.message ?: "STOPPED"
                        Log.e(TAG, "세션 STARTED 대기 실패: $err")
                        retries--
                        if (retries > 0) {
                            WearableState.connectionStatus = "세션 시작 실패 ($err). ${retries}회 재시도..."
                            kotlinx.coroutines.delay(2000)
                        } else {
                            WearableState.connectionStatus = "글래스 세션 시작 실패! ($err)\nMeta View 앱 개발자 모드를 확인하세요."
                            WearableState.isGlassesConnected = false
                        }
                        continue
                    }

                    Log.d(TAG, "세션 상태 STARTED 확인 완료")
                    WearableState.connectionStatus = "세션 시작됨. SDK 카메라 권한 확인 중..."
                    WearableState.isGlassesConnected = true

                    // 카메라 권한 확인 + 스트림 신규 연결 + 프레임 수신 코루틴 시작
                    startStreamOnSession(session, isResuming = false)

                    // ── 세션 종료 감지 → 자동 재연결 ─────────────────────────────────
                    serviceScope.launch {
                        session.state.collect { state ->
                            Log.d(TAG, "세션 상태 변화 감지 (collect): $state")
                            if (state == DeviceSessionState.STOPPED) {
                                withContext(Dispatchers.Main) {
                                    WearableState.isGlassesConnected = false
                                    WearableState.connectedGlassesName = null
                                    WearableState.cameraFrame = null

                                    if (WearableState.isIntentionalStop) {
                                        Log.d(TAG, "의도적인 세션 종료이므로 자동 재연결 생략")
                                        WearableState.isIntentionalStop = false
                                        return@withContext
                                    }

                                    // 음성 재생 중 세션이 끊기는 경우(글래스 전원 off 등)
                                    // 비정상적인 세션 종료(글래스 전원 off 등)인 경우 isScanning이 true이므로 자동 재연결
                                    if (WearableState.isScanning) {
                                        WearableState.connectionStatus = "글래스 연결이 끊어졌습니다. 재연결 시도 중..."
                                        kotlinx.coroutines.delay(3000)
                                        waitForDeviceAndConnect()
                                    }
                                }
                            }
                        }
                    }

                    sessionCreated = true

                } else {
                    retries--
                    val error = sessionResult.errorOrNull()
                    Log.e(TAG, "세션 생성 실패: error = $error, 남은 시도: $retries")
                    if (retries > 0) {
                        kotlinx.coroutines.delay(1500)
                    } else {
                        WearableState.connectionStatus = "글래스 세션 생성 실패! ($error)\n글래스 전원 및 블루투스 연결을 확인해주세요."
                        WearableState.isGlassesConnected = false
                    }
                }
            }
        } catch (t: Throwable) {
            WearableState.connectionStatus = "세션 생성 중 치명적 오류: ${t.message}"
            Log.e(TAG, "createRealDeviceSession 치명적 오류 발생", t)
        }
    }

    private fun sendRegistrationRequiredBroadcast() {
        val intent = Intent(ACTION_REGISTRATION_REQUIRED)
        sendBroadcast(intent)
    }

    // SDK 앱 등록 트리거 (MainActivity에서 호출)
    fun triggerRegistration(activity: android.app.Activity) {
        try {
            Wearables.startRegistration(activity)
            WearableState.connectionStatus = "Meta AI 앱으로 이동하여 등록 승인 중..."
            WearableState.registrationState = "REGISTERING"

            serviceScope.launch {
                waitForRegistrationAndConnect()
            }
        } catch (e: Exception) {
            WearableState.connectionStatus = "등록 시작 오류: ${e.message}"
            e.printStackTrace()
        }
    }

    // ── 세션 위에 카메라 스트림을 연결하고 프레임 수신 코루틴을 시작하는 헬퍼 ──
    // 항상 신규 addStream() + start() + collect 코루틴을 생성한다.
    // 이 함수는 세션 최초 연결 시 또는 세션 재연결 후에만 호출된다.
    private suspend fun startStreamOnSession(
        session: com.meta.wearable.dat.core.session.Session,
        isResuming: Boolean = false
    ) {
        // 카메라 권한 확인 (신규 연결 시에만)
        if (!isResuming) {
            val permResult = Wearables.checkPermissionStatus(Permission.CAMERA)
            val permStatus = permResult.getOrNull()
            Log.d(TAG, "SDK 카메라 권한 상태: $permStatus")
            if (permStatus == null || permStatus is PermissionStatus.Denied) {
                Log.w(TAG, "SDK 카메라 권한 미승인 — MainActivity에 권한 요청 위임")
                WearableState.connectionStatus = "글래스 카메라 권한이 필요합니다.\n앱 화면에서 '카메라 권한 허용' 버튼을 눌러주세요."
                WearableState.needsWearableCameraPermission = true
                kotlinx.coroutines.withTimeout(60_000L) {
                    while (WearableState.needsWearableCameraPermission) {
                        kotlinx.coroutines.delay(500)
                    }
                }
                Log.d(TAG, "SDK 카메라 권한 승인됨")
            }
        }

        WearableState.connectionStatus = "글래스 카메라 스트림 연결 중..."
        Log.d(TAG, "addStream() 호출")

        val result = session.addStream(StreamConfiguration())
        val stream = result.getOrNull() as? Stream
        if (stream == null) {
            val error = result.errorOrNull()
            Log.e(TAG, "addStream() 실패: $error")
            WearableState.connectionStatus = "카메라 스트림 연결 실패 ($error) — 세션 재초기화 중..."
            try { session.stop() } catch (ex: Exception) { Log.e(TAG, "세션 stop 오류", ex) }
            return
        }

        activeStream = stream
        stream.start()
        WearableState.connectionStatus = "글래스 카메라 스트리밍 활성화됨. QR 스캔 대기 중..."
        WearableState.qrScanResult = null
        WearableState.isScanning = true
        Log.d(TAG, "스트림 연결 성공 — videoStream collect 코루틴 시작")

        serviceScope.launch {
            stream.videoStream.collect { videoFrame: VideoFrame ->
                withContext(Dispatchers.Default) {
                    val ySize = videoFrame.width * videoFrame.height
                    val yData = ByteArray(ySize)
                    videoFrame.buffer.rewind()
                    videoFrame.buffer.get(yData, 0, ySize)

                    val bitmap = yuvToBitmap(yData, videoFrame.width, videoFrame.height)
                    withContext(Dispatchers.Main) {
                        WearableState.cameraFrame = bitmap
                    }

                    if (WearableState.isScanning && WearableState.qrScanResult == null) {
                        decodeQRCode(videoFrame)
                    }
                }
            }
        }
    }

    private fun yuvToBitmap(yData: ByteArray, width: Int, height: Int): Bitmap? {
        return try {
            val nv21 = ByteArray(width * height * 3 / 2)
            System.arraycopy(yData, 0, nv21, 0, width * height)
            // UV 플레인은 중성값(128)으로 채워 그레이스케일 처리
            for (i in (width * height) until nv21.size) {
                nv21[i] = 128.toByte()
            }
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 85, out)
            val imageBytes = out.toByteArray()
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun decodeQRCode(videoFrame: VideoFrame) {
        try {
            val buffer = videoFrame.buffer
            val width = videoFrame.width
            val height = videoFrame.height
            val isCompressed = videoFrame.isCompressed

            buffer.rewind()
            val remaining = buffer.remaining()
            val ySize = width * height
            
            if (remaining < ySize) {
                return
            }

            val yData = ByteArray(ySize)
            buffer.get(yData, 0, ySize)

            // ZXing reader setup with QR code optimizations
            val hints = mapOf(
                com.google.zxing.DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE),
                com.google.zxing.DecodeHintType.TRY_HARDER to true,
                com.google.zxing.DecodeHintType.CHARACTER_SET to "UTF-8"
            )
            val reader = MultiFormatReader().apply { setHints(hints) }

            // Define crop regions to try (Center 50%, Center 75%, Full image)
            val cropRatios = listOf(0.5, 0.75, 1.0)
            var qrText: String? = null

            for (ratio in cropRatios) {
                val cropW = (width * ratio).toInt()
                val cropH = (height * ratio).toInt()
                val left = (width - cropW) / 2
                val top = (height - cropH) / 2

                val source = PlanarYUVLuminanceSource(
                    yData, width, height,
                    left, top, cropW, cropH,
                    false
                )

                // Strategy 1: HybridBinarizer
                try {
                    val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
                    val result = reader.decode(binaryBitmap)
                    if (!result.text.isNullOrBlank()) {
                        qrText = result.text
                        break
                    }
                } catch (e: com.google.zxing.ReaderException) {
                    // Fail over
                }

                // Strategy 2: GlobalHistogramBinarizer
                try {
                    val binaryBitmap = BinaryBitmap(com.google.zxing.common.GlobalHistogramBinarizer(source))
                    val result = reader.decode(binaryBitmap)
                    if (!result.text.isNullOrBlank()) {
                        qrText = result.text
                        break
                    }
                } catch (e: com.google.zxing.ReaderException) {
                    // Fail over
                }
            }

            if (!qrText.isNullOrBlank()) {
                val finalQrText = qrText
                serviceScope.launch(Dispatchers.Main) {
                    // 이미 처리 중이거나 스캔이 중지된 경우 무시
                    if (!WearableState.isScanning || WearableState.qrScanResult != null) return@launch
                    WearableState.qrScanResult = finalQrText
                    Log.i(TAG, "QR 코드 감지 성공! 내용: $finalQrText")
                    // pauseScanning에서 세션과 스트림을 모두 정지
                    pauseScanning()
                    processQrScanSuccess(finalQrText)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "decodeQRCode: 치명적 오류", e)
        }
    }

    private fun extractUuidFromQr(qrText: String): String {
        return try {
            when {
                qrText.contains("events/") -> {
                    val startIndex = qrText.indexOf("events/") + 7
                    val remaining = qrText.substring(startIndex)
                    remaining.split(".html", "/", "?").first().trim()
                }
                else -> qrText.trim()
            }
        } catch (e: Exception) {
            qrText.trim()
        }
    }

    private fun processQrScanSuccess(qrText: String) {
        val uuid = extractUuidFromQr(qrText)
        val lang = Locale.getDefault().toLanguageTag()

        Log.d(TAG, "추출된 UUID: $uuid, 언어: $lang")
        WearableState.connectionStatus = "QR 스캔 완료! 행사 정보 조회 중..."

        serviceScope.launch {
            // isSpeechPlaying은 실제 재생이 시작되는 시점에 설정.
            // 여기서 미리 true로 세팅하면 API 실패 시 스트림 복구 경로가 오작동.
            val result = fetchTranslationAndTTS(uuid, lang)
            if (result != null) {
                val (translatedText, audioUrl) = result
                WearableState.connectionStatus = "번역 로드 완료. 음성 출력 중..."

                // 실제 재생 직전에 플래그 설정
                isSpeechPlaying = true

                // 1. 음성 출력 (오디오 다운로드 시도 후 실패 시 Native TTS 백업)
                if (audioUrl.isNotBlank()) {
                    playAudioOnGlasses(audioUrl, translatedText, lang)
                } else if (translatedText.isNotBlank()) {
                    speakText(translatedText, lang)
                } else {
                    isSpeechPlaying = false
                    WearableState.connectionStatus = "재생할 내용이 없습니다."
                    kotlinx.coroutines.delay(3000)
                    onSpeechFinished()
                }

                // 2. 글래스 렌즈 디스플레이에 번역 텍스트 표시
                if (translatedText.isNotBlank()) {
                    showTextOnGlassesDisplay(translatedText)
                }

                WearableState.connectionStatus = "번역 완료: $translatedText"

                // 3. 스캔 내역 저장
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                WearableState.scanHistory.add(0, HistoryItem(
                    id = uuid,
                    qrText = qrText,
                    timestamp = timestamp,
                    translatedText = translatedText
                ))
            } else {
                // API 호출 실패 — 음성 없이 스캔 재개
                WearableState.connectionStatus = "서버 접속 실패! 백엔드 URL을 확인하세요."
                Log.w(TAG, "fetchTranslationAndTTS 실패 — 스캔 재개")
                isSpeechPlaying = false
                kotlinx.coroutines.delay(3000) // 사용자가 에러 메시지를 볼 수 있도록 3초 대기
                onSpeechFinished()
            }
        }
    }

    private suspend fun fetchTranslationAndTTS(uuid: String, lang: String): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            try {
                val url = URL("${WearableState.backendUrl}/api/event/$uuid?lang=$lang")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Bypass-Tunnel-Reminder", "true")
                conn.connectTimeout = 8000
                conn.readTimeout = 8000

                if (conn.responseCode == 200) {
                    val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(responseText)
                    val translatedText = json.optString("translated_text", "")
                    val audioUrl = json.optString("audio_url", "")
                    Pair(translatedText, audioUrl)
                } else {
                    Log.e(TAG, "API 응답 오류: ${conn.responseCode}")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "번역 API 요청 실패: ${e.message}")
                e.printStackTrace()
                null
            }
        }
    private suspend fun downloadFileToLocal(urlString: String): java.io.File? = withContext(Dispatchers.IO) {
        try {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Bypass-Tunnel-Reminder", "true")
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            
            if (conn.responseCode == 200) {
                val tempFile = java.io.File(cacheDir, "temp_tts_audio.mp3")
                if (tempFile.exists()) {
                    tempFile.delete()
                }
                conn.inputStream.use { input ->
                    java.io.FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d(TAG, "오디오 파일 다운로드 성공: ${tempFile.absolutePath} (${tempFile.length()} bytes)")
                tempFile
            } else {
                Log.e(TAG, "오디오 다운로드 HTTP 오류: ${conn.responseCode}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "오디오 파일 다운로드 실패: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    private fun playAudioOnGlasses(audioUrl: String, fallbackText: String, langCode: String) {
        serviceScope.launch {
            val localFile = downloadFileToLocal(audioUrl)
            if (localFile != null && localFile.exists()) {
                withContext(Dispatchers.Main) {
                    try {
                        stopAudio(keepSpeechPlayingState = true)
                        isSpeechPlaying = true

                        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        // 고음질 미디어 출력을 위해 MODE_NORMAL 유지 (A2DP 자동 라우팅)
                        audioManager.mode = AudioManager.MODE_NORMAL

                        mediaPlayer = MediaPlayer().apply {
                            setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    // 고음질 미디어 출력 규격 매칭
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .build()
                            )
                            setDataSource(localFile.absolutePath)
                            prepare() // 로컬 파일이므로 동기 준비로 즉시 재생 설정
                            start()
                            Log.d(TAG, "글래스 스피커 음성 재생 시작 (고음질 로컬 파일)")
                            
                            setOnCompletionListener { 
                                it.release()
                                mediaPlayer = null
                                Log.d(TAG, "MediaPlayer 재생 완료 — 블루투스 버퍼 대기 1.5초")
                                serviceScope.launch(Dispatchers.Main) {
                                    kotlinx.coroutines.delay(1500)
                                    isSpeechPlaying = false
                                    try { localFile.delete() } catch (e: Exception) {}
                                    Log.d(TAG, "대기 완료 — 스캔 재개 시작")
                                    onSpeechFinished()
                                }
                            }
                            setOnErrorListener { _, what, extra ->
                                Log.e(TAG, "MediaPlayer 오류: what=$what, extra=$extra")
                                serviceScope.launch(Dispatchers.Main) {
                                    kotlinx.coroutines.delay(500)
                                    isSpeechPlaying = false
                                    onSpeechFinished()
                                }
                                false
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "오디오 재생 오류: ${e.message}")
                        e.printStackTrace()
                        // 플레이어 오류 시 TTS 백업 재생
                        speakText(fallbackText, langCode)
                    }
                }
            } else {
                Log.w(TAG, "오디오 파일 다운로드 실패. Native TTS 백업 출력 실행.")
                withContext(Dispatchers.Main) {
                    speakText(fallbackText, langCode)
                }
            }
        }
    }

    fun stopAudio(keepSpeechPlayingState: Boolean = false) {
        try {
            if (tts?.isSpeaking == true) {
                tts?.stop()
            }
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            
            if (!keepSpeechPlayingState) {
                isSpeechPlaying = false
                
                // 오디오 모드 복구 (기본 모드)
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.mode = AudioManager.MODE_NORMAL
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showTextOnGlassesDisplay(translatedText: String) {
        Log.w(TAG, "디스플레이 기능 비활성화됨 — 텍스트 표시 안 함: $translatedText")
    }

    fun pauseScanning() {
        WearableState.isScanning = false
        WearableState.isIntentionalStop = true
        try {
            // 스트림과 세션을 모두 정지
            activeStream?.stop()
            activeSession?.stop()
            Log.d(TAG, "스캔 일시정지 — 스트림 및 세션 정지")
            WearableState.connectionStatus = "스캔 일시정지됨. 음성 출력 중..."
        } catch (e: Exception) {
            Log.e(TAG, "pauseScanning 오류", e)
        }
    }

    fun resumeScanning() {
        stopAudio()
        WearableState.qrScanResult = null
        WearableState.isScanning = true
        WearableState.connectionStatus = "스캔 재개 중..."
        serviceScope.launch(Dispatchers.Main) {
            Log.d(TAG, "resumeScanning — 전체 재연결 시작")
            waitForDeviceAndConnect()
        }
    }

    private fun onSpeechFinished() {
        Log.d(TAG, "onSpeechFinished() 호출 — 스캔 재개 시작")
        resumeScanning()
    }

    fun stopServiceAndCleanup() {
        try {
            activeStream?.stop()
            activeStream = null

            activeDisplay = null

            activeSession?.stop()
            activeSession = null

            stopAudio()

            WearableState.isGlassesConnected = false
            WearableState.connectedGlassesName = null
            WearableState.isScanning = false
            WearableState.cameraFrame = null
            WearableState.connectionStatus = "스캐너 서비스 비활성화됨 (App OFF)"
        } catch (e: Exception) {
            e.printStackTrace()
        }
        stopSelf()
    }

    companion object {
        private const val TAG = "GlassesForegroundService"
        private const val CHANNEL_ID = "GlassesForegroundChannel"
        private const val NOTIFICATION_ID = 101
        const val ACTION_REGISTRATION_REQUIRED = "com.example.glassesclient.ACTION_REGISTRATION_REQUIRED"
        const val ACTION_WEARABLE_PERMISSION_REQUIRED = "com.example.glassesclient.ACTION_WEARABLE_PERMISSION_REQUIRED"

        var instance: GlassesForegroundService? = null
            private set
    }
}
