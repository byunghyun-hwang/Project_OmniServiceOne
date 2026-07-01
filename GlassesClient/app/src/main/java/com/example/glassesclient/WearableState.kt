package com.example.glassesclient

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class HistoryItem(
    val id: String,
    val qrText: String,
    val timestamp: String,
    val translatedText: String
)

object WearableState {
    // 전체 서비스 연결 및 상태 메시지
    var connectionStatus by mutableStateOf("권한 확인 중...")

    // 실 글래스 연결 상태
    var isGlassesConnected by mutableStateOf(false)
    var connectedGlassesName by mutableStateOf<String?>(null)

    // 등록 상태 문자열 (UNAVAILABLE / AVAILABLE / REGISTERING / REGISTERED)
    var registrationState by mutableStateOf("UNAVAILABLE")

    // QR 스캔 활성 여부 및 결과
    var isScanning by mutableStateOf(true)
    var qrScanResult by mutableStateOf<String?>(null)
    
    // 의도적인 세션 중지 여부 (비동기 STOPPED 콜백의 중복 재연결 방지용)
    var isIntentionalStop by mutableStateOf(false)

    // 실시간 카메라 프리뷰 프레임 (글래스 카메라에서 수신)
    var cameraFrame by mutableStateOf<Bitmap?>(null)

    // 포그라운드 서비스 활성 여부 (App ON/OFF)
    var isServiceActive by mutableStateOf(false)

    // 스캔 내역 리스트
    val scanHistory = mutableStateListOf<HistoryItem>()

    // SDK 레벨 글래스 카메라/마이크 권한 요청 필요 여부
    var needsWearableCameraPermission by mutableStateOf(false)

    // 백엔드 서버 URL (수동 설정 가능)
    var backendUrl by mutableStateOf("https://cedar-first-transit-bids.trycloudflare.com")
}
