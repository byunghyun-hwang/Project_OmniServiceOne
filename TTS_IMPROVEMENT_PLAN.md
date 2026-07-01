# TTS 음성 출력 품질 개선 전략 (업데이트)

> 최종 업데이트: 2026-06-30  
> 이슈: "일상의 여유를 소중한 분과 특별한 와인 한 잔으로" → TTS 출력 "일상 여 소중 분 특별 와 잔"

---

## 1. 현재 실제 동작 경로 (OpenAI 키 없음)

```
Android QR 인식
  → /api/event/{uuid}?lang=ko 호출
  → 서버: translate_text_with_openai() → OPENAI_API_KEY 없음 → 원문 그대로 반환
  → 서버: generate_tts()
      → OpenAI TTS 시도 → API 키 없음 → 실패
      → gTTS (Google Translate TTS) fallback → MP3 생성
  → Android: MP3 다운로드 → MediaPlayer 재생
```

**실제 사용 중인 TTS: gTTS (Google Translate TTS 비공식 API)**

---

## 2. 근본 원인 분석

### 원인 1 — ZWNJ 오염 (가장 직접적 원인)

텍스트 바이트 분석 결과, 각 단어 사이에 `U+200C (ZWNJ)` 문자가 2~4개씩 삽입되어 있습니다.

```
"일상의\u200c\u200c\u200c 여유를\u200c\u200c\u200c 소중한..."
```

gTTS는 이 ZWNJ를 형태소 경계로 인식해 조사(의/를/한/과/인)를 앞 단어에서 분리하여 단독 발화합니다.  
→ 결과: "일상 의 여유 를 소중 한 분 과 특별 한 와인 한 잔으로" → 들리기엔 "일상 여 소중 분 특별 와 잔"

### 원인 2 — gTTS의 한국어 품질 한계

| 엔진 | 방식 | 한국어 품질 | 제어 파라미터 |
|------|------|------------|--------------|
| gTTS | Google Translate TTS (비공식) | 보통 — 억양 단조, 속도 제어 제한 | slow True/False만 |
| **edge-tts** | Microsoft Edge Neural TTS (무료) | **우수** — 자연스러운 신경망 음성 | rate/volume/pitch 세밀 조정 |

### 원인 3 — sanitize 적용 시점 문제

서버 `sanitize_text()`가 `generate_tts()` 호출 전에 적용되지만, ZWNJ 범위가 `\u200b-\u200d`로 제한되어 있어 일부 케이스 누락 가능. 더 넓은 범위의 불가시 유니코드 제거 필요.

---

## 3. 개선 전략: edge-tts 채택

### 선정 이유

| 항목 | gTTS | edge-tts |
|------|------|----------|
| API 키 | 불필요 | 불필요 |
| 비용 | 무료 | 무료 |
| 한국어 voice | 1종 | 3종 (SunHi/InJoon/HyunsuMultilingual) |
| 음질 | 보통 (단조) | 우수 (Neural TTS) |
| 속도 제어 | slow=True/False | rate=-10%~+10% 세밀 조정 |
| FastAPI 호환 | 동기 (스레드 필요) | **asyncio 네이티브 (직접 await)** |
| 응답속도 | ~0.5초 | ~0.3초 (더 빠름) |
| 설치 | 이미 설치됨 | **이미 설치됨 (v7.2.8)** |

**결론**: edge-tts가 이미 `.venv`에 설치되어 있으며, 추가 비용/설정 없이 즉시 교체 가능.

### 추천 Voice

| Voice | 성별 | 특징 | 추천 용도 |
|-------|------|------|----------|
| `ko-KR-SunHiNeural` | 여성 | 밝고 명확, 응답속도 최빠름(0.3s) | **행사 안내 — 1순위** |
| `ko-KR-InJoonNeural` | 남성 | 안정적, 신뢰감 | 공식 행사 |
| `ko-KR-HyunsuMultilingualNeural` | 남성 | 다국어, 응답속도 느림(1.9s) | 다국어 혼재 텍스트 |

**→ `ko-KR-SunHiNeural` 기본 사용, 언어별 자동 선택 로직 추가**

---

## 4. 즉시 반영 항목 (우선순위 순)

| # | 항목 | 파일 | 효과 |
|---|------|------|------|
| 1 | **gTTS → edge-tts 교체** | `main.py` | ★★★★★ 음질 대폭 향상 |
| 2 | **sanitize 범위 확장** (ZWNJ 포함 전체 불가시 문자) | `main.py` | ★★★★★ 끊김 제거 |
| 3 | **Android sanitizeText 범위 확장** | `GlassesForegroundService.kt` | ★★★★☆ fallback TTS 보호 |
| 4 | **admin.html 입력 전처리** (붙여넣기 시 ZWNJ 자동 제거) | `templates/admin.html` | ★★★☆☆ 오염 방지 |
| 5 | **기존 TTS 캐시 삭제** | `events/audio/` | ★★★★★ 필수 (구 gTTS 캐시 제거) |

---

## 5. 구현 코드

### main.py — edge-tts 적용 핵심 변경

```python
# generate_tts 함수를 async로 교체
async def generate_tts(text: str, filepath: str, lang: str):
    # 언어 코드 → edge-tts voice 매핑
    voice_map = {
        "ko": "ko-KR-SunHiNeural",
        "en": "en-US-JennyNeural",
        "ja": "ja-JP-NanamiNeural",
        "zh": "zh-CN-XiaoxiaoNeural",
        "es": "es-ES-ElviraNeural",
        "fr": "fr-FR-DeniseNeural",
        "de": "de-DE-KatjaNeural",
        "vi": "vi-VN-HoaiMyNeural",
        "th": "th-TH-PremwadeeNeural",
    }
    base_lang = lang.split("-")[0].lower()
    voice = voice_map.get(base_lang, "ko-KR-SunHiNeural")
    
    communicate = edge_tts.Communicate(text, voice, rate="-5%")
    await communicate.save(filepath)
```

`get_translated_event_with_tts` 엔드포인트도 async로 변경 필요.
