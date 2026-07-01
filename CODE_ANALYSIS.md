# 72_AIGlassesTest 프로젝트 코드 분석 보고서

> 분석 일자: 2026-06-30  
> 대상: 전체 프로젝트 (GlassesClient Android 앱 + Python 백엔드)

---

## 1. 프로젝트 개요

### 시스템 아키텍처

```
┌─────────────────────────────────────────────────────────────┐
│  Python FastAPI 백엔드 (main.py)                             │
│  - 행사 등록 / QR 생성 / 번역 / TTS 생성                    │
│  - SQLite DB (events.db)                                     │
│  - Cloudflare Tunnel / Localtunnel 으로 외부 노출            │
│  - GitHub Pages에 행사 HTML 자동 배포                        │
└───────────────────────┬─────────────────────────────────────┘
                        │ REST API (HTTPS)
┌───────────────────────▼─────────────────────────────────────┐
│  GlassesClient Android 앱 (Kotlin + Jetpack Compose)        │
│  - Meta Wearables DAT SDK 연동                               │
│  - Ray-Ban Meta 글래스 카메라 스트리밍                       │
│  - ZXing QR 코드 디코딩                                      │
│  - OpenAI TTS 오디오 다운로드 → 글래스 스피커 재생           │
└─────────────────────────────────────────────────────────────┘
```

### 기술 스택

| 레이어 | 기술 |
|--------|------|
| Android | Kotlin 2.3.20, Jetpack Compose (BOM 2026.03.01), Material3 |
| SDK | Meta Wearables DAT SDK 0.6.0 |
| QR 디코딩 | ZXing 3.5.3 |
| 네비게이션 | Android Navigation3 1.0.1 |
| 백엔드 | Python FastAPI + Uvicorn |
| DB | SQLAlchemy + SQLite |
| AI | OpenAI gpt-4o-mini (번역), tts-1 (음성합성) |
| 배포 | GitHub Pages, Cloudflare Tunnel |

---

## 2. GlassesClient Android 앱 상세 분석

### 2.1 파일 구성 현황

```
app/src/main/java/com/example/glassesclient/
├── MainActivity.kt                 ★ 핵심 UI (약 600줄, 비대)
├── GlassesForegroundService.kt     ★ 핵심 서비스 (약 700줄, 비대)
├── WearableState.kt                글로벌 상태 관리 (object)
├── Navigation.kt                   Navigation3 라우팅
├── NavigationKeys.kt               NavKey 정의
├── data/DataRepository.kt          ⚠️ 미사용 레거시
├── theme/                          Color.kt, Theme.kt, Type.kt
└── ui/main/
    ├── MainScreen.kt               ⚠️ 미사용 레거시
    └── MainScreenViewModel.kt      ⚠️ 미사용 레거시
```

---

## 3. 품질 이슈 분석

### 🔴 심각 (Critical)

#### 3.1 보안 자격 증명 하드코딩
**파일**: `AndroidManifest.xml`

```xml
<!-- 실제 Meta 앱 자격 증명이 소스 코드에 노출됨 -->
<meta-data android:name="com.meta.wearable.mwdat.APPLICATION_ID"
           android:value="1533313598538080" />
<meta-data android:name="com.meta.wearable.mwdat.CLIENT_TOKEN"
           android:value="AR|1533313598538080|229e72d0ee6b70d4678c2e8cb1f431e3" />
```

**문제**: APPLICATION_ID와 CLIENT_TOKEN이 평문으로 소스 코드에 노출되어 있어, Git 히스토리에 영구적으로 기록됩니다.  
**권장 조치**: `local.properties` + `buildConfigField`로 분리하거나, CI/CD 환경변수로 주입.

---

#### 3.2 Facebook 2FA 복구 코드 파일 존재
**파일**: `FACEBOOK-2FA-RecoveryCodes.txt`

프로젝트 루트에 실제 계정의 2FA 복구 코드 파일이 존재합니다. `.gitignore`에 포함되어 있지 않아 Git에 커밋될 위험이 있습니다.  
**권장 조치**: 파일 즉시 삭제, `.gitignore`에 추가, Facebook 계정 2FA 복구 코드 재발급.

---

#### 3.3 백엔드 URL 하드코딩
**파일**: `WearableState.kt`

```kotlin
var backendUrl by mutableStateOf("https://occurrence-needle-superior-ham.trycloudflare.com")
```

**문제**: 임시 터널 주소가 앱 빌드에 하드코딩되어 있습니다. 터널 재시작 시 주소가 변경되어 앱이 작동하지 않습니다.  
**권장 조치**: `BuildConfig`에서 주입하거나, 첫 실행 시 사용자 입력 → `SharedPreferences`에 저장(현재 설정 탭의 저장 로직은 있으나 앱 재시작 시 초기화됨).

---

#### 3.4 전역 Singleton 상태 오염 위험
**파일**: `WearableState.kt`, `GlassesForegroundService.kt`

```kotlin
// WearableState - Kotlin object (진정한 싱글톤, 앱 전체 공유)
object WearableState {
    var connectionStatus by mutableStateOf("권한 확인 중...")
    var cameraFrame by mutableStateOf<Bitmap?>(null)
    ...
}

// GlassesForegroundService - static instance 노출
companion object {
    var instance: GlassesForegroundService? = null
        private set
}
```

**문제**:
- `WearableState`는 `object` 싱글톤으로 테스트 격리 불가
- `GlassesForegroundService.instance`는 static 참조로 메모리 누수 가능성
- 여러 서비스 인스턴스 생성 시 상태 일관성 깨짐 (코드에서 `instance == this` 체크가 있으나 근본적 해결책 아님)

---

### 🟠 높음 (High)

#### 3.5 MainActivity가 지나치게 비대 (God Class)
**파일**: `MainActivity.kt` (약 600줄)

MainActivity 단일 파일에 다음이 모두 포함됨:
- 권한 요청 로직
- 서비스 시작/종료 로직
- BroadcastReceiver 등록/해제
- 전체 UI (GlassesControlScreen, HomeTab, HistoryTab, SettingsTab, RadarAnimation 등)
- 모든 Composable 함수들

**권장 조치**: 각 탭별로 파일 분리 (`HomeTab.kt`, `HistoryTab.kt`, `SettingsTab.kt`, `RadarAnimation.kt`), ViewModel 도입.

---

#### 3.6 GlassesForegroundService가 지나치게 비대
**파일**: `GlassesForegroundService.kt` (약 700줄)

단일 Service 클래스에 다음이 모두 혼재:
- SDK 초기화 및 등록 플로우
- 기기 연결 및 세션 관리
- 카메라 스트림 처리
- QR 코드 디코딩 (ZXing)
- 번역 API 호출
- 오디오 파일 다운로드
- MediaPlayer 재생
- Native TTS 재생
- Bitmap 변환 (YUV → Bitmap)
- 자동 재연결 로직

**권장 조치**: 책임에 따라 클래스 분리 (`QrDecoder`, `AudioPlayer`, `WearableSessionManager`, `TranslationRepository` 등).

---

#### 3.7 설정 탭의 URL이 앱 재시작 시 초기화
**파일**: `SettingsTab()` in `MainActivity.kt`

```kotlin
@Composable
fun SettingsTab() {
    var urlText by remember { mutableStateOf(WearableState.backendUrl) }
    // ...
    Button(onClick = {
        WearableState.backendUrl = urlText.trim()  // 메모리에만 저장
        Toast.makeText(context, "로컬터널 URL이 업데이트되었습니다!", Toast.LENGTH_SHORT).show()
    }) { ... }
}
```

`WearableState.backendUrl`을 변경해도 앱이 종료되면 초기 하드코딩 값으로 되돌아갑니다. `SharedPreferences` 또는 `DataStore`에 영속화 필요.

---

#### 3.8 HTTP 연결에서 예외 처리 미흡
**파일**: `GlassesForegroundService.kt`

```kotlin
private suspend fun fetchTranslationAndTTS(uuid: String, lang: String): Pair<String, String>? =
    withContext(Dispatchers.IO) {
        try {
            val url = URL("${WearableState.backendUrl}/api/event/$uuid?lang=$lang")
            val conn = url.openConnection() as HttpURLConnection
            // ...
        } catch (e: Exception) {
            Log.e(TAG, "번역 API 요청 실패: ${e.message}")
            null  // null 반환 → 상위에서 단순 오류 메시지만 표시
        }
    }
```

- `HttpURLConnection` 대신 OkHttp나 Ktor 같은 Android 친화적 HTTP 클라이언트 사용 권장
- 네트워크 오류 유형별 구분 없음 (타임아웃, DNS 오류, HTTP 4xx/5xx 등)
- `conn.disconnect()` 미호출 → 연결 누수 가능

---

#### 3.9 UI에서 WearableState 직접 참조 (상태 불일치 위험)
**파일**: `MainActivity.kt`

```kotlin
// GlassesControlScreen은 파라미터로 상태를 받지만,
// HomeTab 내부에서는 WearableState를 직접 참조
val frame = WearableState.cameraFrame
val needsWearablePerm = WearableState.needsWearableCameraPermission

// Switch의 onCheckedChange에서 직접 서비스 시작
context.startForegroundService(intent)
```

Composable이 직접 `WearableState` 싱글톤과 Android API를 참조하면 테스트가 불가능합니다. ViewModel을 통해 추상화 필요.

---

### 🟡 중간 (Medium)

#### 3.10 미사용 레거시 코드
**파일**: `DataRepository.kt`, `MainScreen.kt`, `MainScreenViewModel.kt`, `Navigation.kt`, `NavigationKeys.kt`

```kotlin
// DataRepository.kt - 실제 앱에서 전혀 사용하지 않음
class DefaultDataRepository : DataRepository {
    override val data: Flow<List<String>> = flow { emit(listOf("Android")) }
}

// Navigation.kt - MainNavigation()은 MainActivity에서 호출되지 않음
@Composable
fun MainNavigation() { ... }
```

실제 앱은 Navigation3를 사용하지 않고 단순 `Tab` enum + `when` 분기로 네비게이션을 처리합니다. 레거시 파일들이 남아있어 코드베이스 이해를 방해합니다.

---

#### 3.11 에러 상태 UI 부재 (Loading State 처리 미흡)
**파일**: `MainActivity.kt` - `HomeTab()`

```kotlin
// QR 결과 없는 경우 조건 분기는 있으나,
// 네트워크 오류, 번역 실패, 오디오 재생 실패 시 사용자에게 명확한 피드백 없음
WearableState.connectionStatus = "행사 번역 정보 조회 실패! 백엔드 서버를 확인하세요."
// -> Toast나 Snackbar 없이 상태 텍스트만 변경
```

실패 시나리오에 대한 사용자 친화적 UI가 부족합니다.

---

#### 3.12 해제되지 않는 리소스
**파일**: `GlassesForegroundService.kt`

```kotlin
private fun yuvToBitmap(yData: ByteArray, width: Int, height: Int): Bitmap? {
    return try {
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 85, out)
        // out.close() 미호출
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    } catch (e: Exception) { null }
}
```

- `ByteArrayOutputStream`의 `close()` 미호출
- 이전 `Bitmap`의 `recycle()` 미호출 (프레임 교체 시)
- 카메라 스트림이 초당 여러 프레임을 처리하는 경우 GC 압박 증가 가능

---

#### 3.13 TTS 완료 시간 추정 방식 부정확
**파일**: `GlassesForegroundService.kt`

```kotlin
// 글자당 약 280ms 추정 - 매우 부정확한 휴리스틱
ttsEstimatedDurationMs = (cleanText.length * 280L).coerceAtLeast(2500L)
```

글자 수 기반의 시간 추정은 언어(한국어 vs 영어), 문장 구조, TTS 엔진 속도에 따라 크게 달라집니다. `UtteranceProgressListener.onDone()`이 이미 실제 완료를 알려주는데, 추정 시간으로 추가 대기를 하는 것은 불필요한 지연을 유발합니다.

---

#### 3.14 권한 요청 흐름 일부 누락
**파일**: `MainActivity.kt`

```kotlin
// NEARBY_WIFI_DEVICES는 API 33+에서만 추가되나,
// ACCESS_FINE_LOCATION은 API 33+에서 블루투스 스캔에 더 이상 필요하지 않음
// 이 부분의 정확한 API 레벨별 권한 분기가 필요
val list = mutableListOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.CAMERA,
    Manifest.permission.RECORD_AUDIO,
    Manifest.permission.ACCESS_FINE_LOCATION  // API 31+에서는 BLUETOOTH_SCAN이 대체
)
```

---

### 🟢 낮음 (Low)

#### 3.15 테스트 커버리지 거의 없음

```
androidTest/MainScreenTest.kt  - 레거시 화면 UI 테스트 1개 (실제 앱 기능 미포함)
test/ (unit test)               - 테스트 파일 전무
```

핵심 로직(QR 디코딩, UUID 추출, TTS 시간 추정, 상태 전환)에 대한 단위 테스트가 전혀 없습니다.

---

#### 3.16 하드코딩된 색상값
**파일**: `MainActivity.kt`

```kotlin
// theme/Color.kt의 Purple/Pink 색상 정의가 있지만 실제로는 미사용
// MainActivity.kt 전체에 걸쳐 Color 값이 직접 하드코딩됨
color = Color(0xFF0B0F19)
color = Color(0xFF1F2937)
color = Color(0xFF10B981)
color = Color(0xFF111827)
// ... 등 20+ 곳
```

`Color.kt`에 앱 전용 색상 팔레트를 정의하고 참조하도록 수정 필요.

---

#### 3.17 접근성 미흡

- 이모지를 텍스트 내용으로 사용 (`"🕶️ Ray-Ban Meta 글래스"`, `"📷 실시간 글래스 카메라 뷰"`)
- `contentDescription`이 없는 인터랙티브 요소 존재
- 애니메이션에 `reduceMotion` 대응 없음

---

#### 3.18 릴리즈 빌드에서 난독화 비활성화
**파일**: `app/build.gradle.kts`

```kotlin
buildTypes {
    release {
        isMinifyEnabled = false  // 난독화 및 코드 축소 비활성화
        proguardFiles(...)
    }
}
```

릴리즈 빌드에서도 코드 축소/난독화가 되지 않아 APK 크기 증가 및 리버스 엔지니어링 취약.

---

#### 3.19 시스템 정보의 SDK 버전 불일치
**파일**: `MainActivity.kt` - `SettingsTab()`

```kotlin
// 실제 build.gradle.kts에는 mwdat-core:0.6.0 이 의존성으로 명시되어 있으나
Text("Meta Wearables DAT 버전: mwdat-core:0.7.0", color = Color.Gray, fontSize = 12.sp)
```

UI에 표시되는 버전과 실제 사용 중인 SDK 버전이 불일치합니다.

---

## 4. Python 백엔드 분석

### 🔴 심각 (Critical)

#### 4.1 CORS 전체 허용
**파일**: `main.py`

```python
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # 모든 출처 허용
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)
```

`allow_origins=["*"]`와 `allow_credentials=True`를 동시에 사용하는 것은 CORS 보안 정책상 유효하지 않으며, 브라우저에서 오류를 발생시킬 수 있습니다. 허용할 출처를 명시적으로 지정해야 합니다.

---

#### 4.2 subprocess를 통한 Git 명령 실행 (보안 위험)
**파일**: `main.py`

```python
def push_to_github(filepath: str):
    try:
        subprocess.run(["git", "add", filepath], check=True)
        commit_message = f"deploy event page: {os.path.basename(filepath)}"
        subprocess.run(["git", "commit", "-m", commit_message], check=True)
        subprocess.run(["git", "push", "origin", "main"], check=True)
```

- `filepath`가 검증 없이 `subprocess.run`에 전달됨 → 경로 주입 공격 가능성
- `main` 브랜치에 직접 푸시 → 실수 시 복구 어려움
- Git 자격증명이 서버 환경에 설정되어 있어야 함 (관리 부담)

---

#### 4.3 API 인증 없음
**파일**: `main.py`

```python
@app.post("/api/events")  # 인증 없이 누구나 행사 생성 가능
def create_event(event_in: EventCreate, ...):
    ...

@app.get("/")  # 어드민 페이지 인증 없음
def admin_page(request: Request, db: Session = Depends(get_db)):
    ...
```

어드민 페이지와 행사 생성 API에 인증이 없어 외부에서 자유롭게 접근 가능합니다.

---

### 🟠 높음 (High)

#### 4.4 `datetime.utcnow()` 사용 (Deprecated)
**파일**: `models.py`

```python
created_at = Column(DateTime, default=datetime.datetime.utcnow)
```

Python 3.12부터 `datetime.utcnow()`는 deprecated입니다.  
**권장**: `datetime.datetime.now(datetime.timezone.utc)`

---

#### 4.5 오디오 파일 무제한 누적
**파일**: `main.py`

```python
audio_filepath = f"events/audio/{audio_filename}"
if not os.path.exists(audio_filepath):
    generate_tts(clean_text, audio_filepath, lang)
```

생성된 TTS MP3 파일이 `events/audio/`에 무제한 쌓입니다. 행사 × 언어 수만큼 파일이 누적되며 디스크 공간 관리 정책이 없습니다.

---

#### 4.6 SQLite를 프로덕션 DB로 사용
**파일**: `database.py`

```python
SQLALCHEMY_DATABASE_URL = "sqlite:///./events.db"
```

SQLite는 동시 쓰기 처리에 한계가 있어, 다수의 행사 등록이 동시에 발생하면 잠금 충돌이 발생할 수 있습니다.

---

### 🟡 중간 (Medium)

#### 4.7 HTML 템플릿에서 XSS 취약점 가능성
**파일**: `admin.html`

```javascript
// admin.html - innerHTML로 콘텐츠 직접 삽입
tr.innerHTML = `
    <td ... title="${content}">${content}</td>
    ...
    <button class="action-btn" onclick="showQR('${uuid}', '${url}')">
`;
```

`content`나 `url`에 `'` 또는 `<script>` 등이 포함될 경우 XSS 공격에 취약합니다. `textContent`를 사용하거나 값을 이스케이프 처리해야 합니다.

---

## 5. 공통 이슈

#### 5.1 README 파일 없음
프로젝트 루트에 README가 없어 새 개발자가 프로젝트 실행 방법, 아키텍처, 필요 환경 설정 등을 파악할 방법이 없습니다.

#### 5.2 환경 변수 관리 체계 없음
- Python 백엔드: `OPENAI_API_KEY`를 환경변수로 처리하지만, `.env` 파일이나 `.env.example`이 없음
- Android: `local.properties`에 `github_token`을 저장하도록 되어 있으나 `.env.example`에 해당하는 가이드 없음

---

## 6. 우선순위별 개선 로드맵

### Phase 1 — 즉시 조치 (보안)
| # | 항목 | 파일 |
|---|------|------|
| 1 | Facebook 2FA 복구 코드 파일 삭제 및 재발급 | `FACEBOOK-2FA-RecoveryCodes.txt` |
| 2 | Meta 앱 자격 증명을 `local.properties` + `buildConfigField`로 분리 | `AndroidManifest.xml` |
| 3 | 백엔드 URL 영속화 (`SharedPreferences` or `DataStore`) | `WearableState.kt`, `SettingsTab` |
| 4 | CORS 출처 명시적 허용으로 변경 | `main.py` |
| 5 | 어드민 API 인증 추가 (Basic Auth or API Key) | `main.py` |

### Phase 2 — 단기 (코드 품질)
| # | 항목 | 파일 |
|---|------|------|
| 6 | 미사용 레거시 파일 제거 | `DataRepository.kt`, `MainScreen.kt`, `Navigation.kt` 등 |
| 7 | MainActivity UI를 탭별 파일로 분리 | `MainActivity.kt` |
| 8 | GlassesForegroundService 책임 분리 | `GlassesForegroundService.kt` |
| 9 | 색상값 Color.kt로 중앙화 | `MainActivity.kt` |
| 10 | SDK 버전 표시 수정 | `SettingsTab` |

### Phase 3 — 중기 (아키텍처 개선)
| # | 항목 |
|---|------|
| 11 | ViewModel 도입으로 UI 상태 분리 |
| 12 | WearableState를 StateFlow 기반 Repository 패턴으로 전환 |
| 13 | HTTP 클라이언트 OkHttp로 교체 + 오류 유형별 처리 |
| 14 | 핵심 로직 단위 테스트 추가 (QR 디코딩, UUID 추출) |
| 15 | `datetime.utcnow()` → `datetime.now(timezone.utc)` 교체 |

### Phase 4 — 장기 (확장성)
| # | 항목 |
|---|------|
| 16 | 릴리즈 빌드 난독화 활성화 (`isMinifyEnabled = true`) |
| 17 | 오디오 파일 만료/삭제 정책 추가 |
| 18 | SQLite → PostgreSQL 또는 클라우드 DB 전환 고려 |
| 19 | 접근성 개선 (contentDescription, 이모지 레이블 분리) |
| 20 | README 및 개발 환경 설정 가이드 작성 |

---

## 7. 코드 품질 종합 점수

| 항목 | 점수 | 비고 |
|------|------|------|
| 보안 | 3/10 | 자격증명 노출, CORS 미설정, 인증 없음 |
| 구조/아키텍처 | 4/10 | God Class, 레거시 혼재, 싱글톤 과의존 |
| 유지보수성 | 4/10 | 파일 분리 없음, 하드코딩 다수 |
| 테스트 커버리지 | 1/10 | 실질적 테스트 없음 |
| 에러 처리 | 4/10 | 기본 try-catch는 있으나 세분화 미흡 |
| 기능 완성도 | 7/10 | 핵심 기능은 동작, 일부 edge case 미처리 |
| **종합** | **3.8/10** | |

---

*이 문서는 자동 분석 도구와 수동 코드 리뷰를 병행하여 작성되었습니다.*
