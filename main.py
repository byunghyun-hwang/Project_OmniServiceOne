import uuid
import re
import io
import datetime
import os
import json
import urllib.request
import subprocess
import asyncio
import edge_tts
from fastapi import FastAPI, Depends, HTTPException, Request, BackgroundTasks
from fastapi.responses import StreamingResponse, HTMLResponse
from fastapi.templating import Jinja2Templates
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from sqlalchemy.orm import Session
import qrcode

import models
from database import engine, get_db

# Create DB tables
models.Base.metadata.create_all(bind=engine)

app = FastAPI(title="행사 관리 API (GitHub Pages + OpenAI Translate & TTS)")

# Enable CORS for external access (e.g. GitHub Pages client fetching backend)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Ensure events and audio directories exist
os.makedirs("events/audio", exist_ok=True)

# Mount events directory for static audio serving
app.mount("/events", StaticFiles(directory="events"), name="events")

# Setup templates
templates = Jinja2Templates(directory="templates")

# GitHub Pages configuration
GITHUB_USER = "byunghyun-hwang"
GITHUB_REPO = "Project_OmniServiceOne"
GITHUB_PAGES_BASE_URL = f"https://{GITHUB_USER}.github.io/{GITHUB_REPO}"

from pydantic import BaseModel

class EventCreate(BaseModel):
    content: str
    public_api_url: str = ""  # The localtunnel or ngrok URL passed from Admin dashboard

class TranslateRequest(BaseModel):
    text: str
    target_lang: str

# Helper: OpenAI Chat Translation API (using urllib)
def translate_text_with_openai(text: str, target_lang: str) -> str:
    api_key = os.environ.get("OPENAI_API_KEY")
    if not api_key:
        print("Warning: OPENAI_API_KEY environment variable is not set. Returning original text.")
        return text
    
    url = "https://api.openai.com/v1/chat/completions"
    prompt = (
        f"Translate the following text into the language corresponding to language code '{target_lang}'. "
        "Keep the original tone, line breaks, and context. Do NOT add any notes, side explanations, quotes, or markdown format. "
        "Just output the clean translated text itself:\n\n"
        f"{text}"
    )
    
    payload = {
        "model": "gpt-4o-mini",
        "messages": [{"role": "user", "content": prompt}]
    }
    
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=data,
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {api_key}"
        },
        method="POST"
    )
    
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            res_data = json.loads(response.read().decode("utf-8"))
            translated = res_data["choices"][0]["message"]["content"].strip()
            return translated
    except Exception as e:
        print(f"OpenAI API Translation Error: {e}")
        return text

# Helper: Remove HTML and zero-width spaces for clean TTS generation
def sanitize_text(text: str) -> str:
    # 1. HTML 태그 제거
    text = re.sub(r'<[^>]*>', '', text)
    # 2. 불가시 유니코드 전체 제거
    #    \u00ad 소프트 하이픈
    #    \u200b ZWSP, \u200c ZWNJ, \u200d ZWJ, \u200e LRM, \u200f RLM
    #    \u2028 LS, \u2029 PS
    #    \u202a-\u202f 방향 제어 문자
    #    \ufeff BOM
    text = re.sub(r'[\u00ad\u200b-\u200f\u2028\u2029\u202a-\u202f\ufeff]', '', text)
    # 3. 다중 공백 단일 공백으로 치환
    text = re.sub(r'\s+', ' ', text)
    return text.strip()

# 언어 코드 → edge-tts Neural voice 매핑
EDGE_TTS_VOICE_MAP = {
    "ko": "ko-KR-SunHiNeural",       # 한국어: SunHi (여성, 명확하고 자연스러움)
    "en": "en-US-JennyNeural",        # 영어
    "ja": "ja-JP-NanamiNeural",       # 일본어
    "zh": "zh-CN-XiaoxiaoNeural",     # 중국어
    "es": "es-ES-ElviraNeural",       # 스페인어
    "fr": "fr-FR-DeniseNeural",       # 프랑스어
    "de": "de-DE-KatjaNeural",        # 독일어
    "vi": "vi-VN-HoaiMyNeural",       # 베트남어
    "th": "th-TH-PremwadeeNeural",    # 태국어
}

# Helper: Generate TTS using edge-tts (Microsoft Neural TTS, 무료, API 키 불필요)
async def generate_tts(text: str, filepath: str, lang: str):
    base_lang = lang.split("-")[0].lower()
    voice = EDGE_TTS_VOICE_MAP.get(base_lang, "ko-KR-SunHiNeural")
    try:
        communicate = edge_tts.Communicate(text, voice, rate="-5%")
        await communicate.save(filepath)
        print(f"edge-tts 생성 완료: {filepath} (voice={voice})")
    except Exception as e:
        print(f"edge-tts 오류: {e}. gTTS fallback 시도.")
        # fallback: gTTS
        try:
            from gtts import gTTS
            clean_lang = base_lang
            tts_fb = gTTS(text=text, lang=clean_lang, slow=False)
            tts_fb.save(filepath)
            print(f"gTTS fallback 완료: {filepath} (lang={clean_lang})")
        except Exception as e2:
            print(f"gTTS fallback 오류: {e2}")

# GitHub push background task
def push_to_github(filepath: str):
    try:
        subprocess.run(["git", "add", filepath], check=True)
        commit_message = f"deploy event page: {os.path.basename(filepath)}"
        subprocess.run(["git", "commit", "-m", commit_message], check=True)
        subprocess.run(["git", "push", "origin", "main"], check=True)
        print(f"Successfully pushed {filepath} to GitHub.")
    except Exception as e:
        print(f"Failed to push to GitHub: {e}")

@app.post("/api/events")
def create_event(event_in: EventCreate, background_tasks: BackgroundTasks, db: Session = Depends(get_db)):
    if not event_in.content.strip():
        raise HTTPException(status_code=400, detail="행사 내용을 입력해주세요.")
    
    event_uuid = str(uuid.uuid4())
    db_event = models.Event(uuid=event_uuid, content=event_in.content)
    db.add(db_event)
    db.commit()
    db.refresh(db_event)
    
    # 1. Create static HTML page with embedded public api url for translation requests
    filepath = f"events/{event_uuid}.html"
    
    template = templates.get_template("event.html")
    event_data = {
        "content": db_event.content,
        "created_at": db_event.created_at.strftime("%Y-%m-%d %H:%M:%S"),
        "uuid": event_uuid
    }
    
    # Inject both local DB event and public API tunnel URL into the static event page
    html_content = template.render({
        "event": event_data,
        "public_api_url": event_in.public_api_url
    })
    
    with open(filepath, "w", encoding="utf-8") as f:
        f.write(html_content)
        
    # 2. Trigger background task to push to GitHub
    background_tasks.add_task(push_to_github, filepath)
    
    # Return URL of GitHub Pages
    github_url = f"{GITHUB_PAGES_BASE_URL}/events/{event_uuid}.html"
    return {
        "uuid": db_event.uuid,
        "content": db_event.content,
        "created_at": db_event.created_at.strftime("%Y-%m-%d %H:%M:%S"),
        "url": github_url
    }

@app.get("/api/events")
def list_events(db: Session = Depends(get_db)):
    events = db.query(models.Event).order_by(models.Event.id.desc()).all()
    return [{
        "uuid": e.uuid,
        "content": e.content,
        "created_at": e.created_at.strftime("%Y-%m-%d %H:%M:%S"),
        "url": f"{GITHUB_PAGES_BASE_URL}/events/{e.uuid}.html"
    } for e in events]

# Realtime Translation Route (Legacy compatibility)
@app.post("/api/translate")
def translate_content(req: TranslateRequest):
    if not req.text.strip():
        return {"translated_text": ""}
    translated = translate_text_with_openai(req.text, req.target_lang)
    return {"translated_text": translated}

# GET /api/event/{uuid}?lang={lang} - Exposes translated text + high-quality TTS audio file URL
@app.get("/api/event/{uuid}")
async def get_translated_event_with_tts(uuid: str, lang: str, request: Request, db: Session = Depends(get_db)):
    db_event = db.query(models.Event).filter(models.Event.uuid == uuid).first()
    if not db_event:
        raise HTTPException(status_code=404, detail="존재하지 않는 행사입니다.")
    
    # 1. Translate content using OpenAI Chat (키 없으면 원문 반환)
    translated_text = translate_text_with_openai(db_event.content, lang)
    
    # 2. Generate and save TTS audio file (cache if exists to reduce API calls)
    safe_lang = "".join([c for c in lang if c.isalnum() or c in ("-", "_")]).lower()
    audio_filename = f"{uuid}_{safe_lang}.mp3"
    audio_filepath = f"events/audio/{audio_filename}"
    
    if not os.path.exists(audio_filepath):
        # sanitize 후 edge-tts로 고품질 MP3 생성
        clean_text = sanitize_text(translated_text)
        await generate_tts(clean_text, audio_filepath, lang)
        
    # 3. Formulate public audio URL dynamically based on proxy headers (respecting tunnel)
    headers = request.headers
    proto = headers.get("x-forwarded-proto", "http")
    host = headers.get("x-forwarded-host", headers.get("host", "127.0.0.1:8000"))
    base_url = f"{proto}://{host}"
    if not base_url.endswith("/"):
        base_url += "/"
    audio_url = f"{base_url}events/audio/{audio_filename}"
    
    return {
        "translated_text": translated_text,
        "audio_url": audio_url
    }

@app.get("/", response_class=HTMLResponse)
def admin_page(request: Request, db: Session = Depends(get_db)):
    events = db.query(models.Event).order_by(models.Event.id.desc()).all()
    formatted_events = []
    for e in events:
        formatted_events.append({
            "uuid": e.uuid,
            "content": e.content,
            "created_at": e.created_at.strftime("%Y-%m-%d %H:%M:%S"),
            "url": f"{GITHUB_PAGES_BASE_URL}/events/{e.uuid}.html"
        })
    return templates.TemplateResponse(request=request, name="admin.html", context={"events": formatted_events})

# Fallback local viewing router
@app.get("/event/{uuid}", response_class=HTMLResponse)
def read_event(uuid: str, request: Request, db: Session = Depends(get_db)):
    db_event = db.query(models.Event).filter(models.Event.uuid == uuid).first()
    if not db_event:
        raise HTTPException(status_code=404, detail="존재하지 않는 행사입니다.")
    
    event_data = {
        "content": db_event.content,
        "created_at": db_event.created_at.strftime("%Y-%m-%d %H:%M:%S"),
        "uuid": uuid
    }
    return templates.TemplateResponse(request=request, name="event.html", context={"event": event_data, "public_api_url": ""})

# QR generator pointing to GitHub Pages URL
@app.get("/event/{uuid}/qr")
def get_event_qr(uuid: str, db: Session = Depends(get_db)):
    db_event = db.query(models.Event).filter(models.Event.uuid == uuid).first()
    if not db_event:
        raise HTTPException(status_code=404, detail="존재하지 않는 행사입니다.")
    
    event_url = f"{GITHUB_PAGES_BASE_URL}/events/{uuid}.html"
    
    qr = qrcode.QRCode(version=1, box_size=10, border=4)
    qr.add_data(event_url)
    qr.make(fit=True)

    img = qr.make_image(fill_color="black", back_color="white")
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    buf.seek(0)
    return StreamingResponse(buf, media_type="image/png")
