import uuid
import io
import datetime
import os
import json
import urllib.request
import subprocess
from fastapi import FastAPI, Depends, HTTPException, Request, BackgroundTasks
from fastapi.responses import StreamingResponse, HTMLResponse
from fastapi.templating import Jinja2Templates
from fastapi.middleware.cors import CORSMiddleware
from sqlalchemy.orm import Session
import qrcode

import models
from database import engine, get_db

# Create DB tables
models.Base.metadata.create_all(bind=engine)

app = FastAPI(title="행사 관리 API (GitHub Pages + 로컬 Ollama 번역)")

# Enable CORS for external access (e.g. GitHub Pages client fetching backend)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Setup templates
templates = Jinja2Templates(directory="templates")

# GitHub Pages configuration
GITHUB_USER = "byunghyun-hwang"
GITHUB_REPO = "Project_OmniServiceOne"
GITHUB_PAGES_BASE_URL = f"https://{GITHUB_USER}.github.io/{GITHUB_REPO}"

from pydantic import BaseModel

class EventCreate(BaseModel):
    content: str
    public_api_url: str = ""  # The ngrok or localtunnel URL passed from Admin dashboard

class TranslateRequest(BaseModel):
    text: str
    target_lang: str

# Helper to detect best installed Ollama model
def get_best_ollama_model() -> str:
    try:
        # Request tags from local Ollama service
        with urllib.request.urlopen("http://localhost:11434/api/tags", timeout=3) as response:
            res_data = json.loads(response.read().decode("utf-8"))
            models = [m["name"] for m in res_data.get("models", [])]
            if not models:
                return "llama3.2:latest"  # Fallback if empty list
            # Priority preference matching
            priority_models = ["llama3.1:8b", "llama3.2:latest", "gemma4:e4b"]
            for pm in priority_models:
                if pm in models:
                    return pm
            # If no priority matching, return first available model
            return models[0]
    except Exception as e:
        print(f"Failed to query Ollama tags: {e}. Fallback to llama3.2:latest")
        return "llama3.2:latest"

# Helper to call local Ollama translation service
def translate_text_with_ollama(text: str, target_lang: str) -> str:
    model_name = get_best_ollama_model()
    print(f"Running translation using Ollama model: {model_name} for target lang: {target_lang}")
    url = "http://localhost:11434/api/generate"
    
    prompt = (
        f"You are a professional translator. Translate the following text into the language corresponding to language code '{target_lang}'. "
        "Keep the original tone, line breaks, and meaning. Do NOT add any notes, side explanations, quotes, or markdown backticks. "
        "Just output the clean translated text itself:\n\n"
        f"{text}"
    )
    
    payload = {
        "model": model_name,
        "prompt": prompt,
        "stream": False
    }
    
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=data,
        headers={"Content-Type": "application/json"},
        method="POST"
    )
    
    try:
        # Local model inference may take longer, timeout is set to 30 seconds
        with urllib.request.urlopen(req, timeout=30) as response:
            res_data = json.loads(response.read().decode("utf-8"))
            translated = res_data["response"].strip()
            return translated
    except Exception as e:
        print(f"Ollama API Translation Error: {e}")
        return text  # Graceful fallback to original text if error occurs

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
    os.makedirs("events", exist_ok=True)
    filepath = f"events/{event_uuid}.html"
    
    template = templates.get_template("event.html")
    event_data = {
        "content": db_event.content,
        "created_at": db_event.created_at.strftime("%Y-%m-%d %H:%M:%S")
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

# Realtime Translation Route (Using local Ollama)
@app.post("/api/translate")
def translate_content(req: TranslateRequest):
    if not req.text.strip():
        return {"translated_text": ""}
    
    translated = translate_text_with_ollama(req.text, req.target_lang)
    return {"translated_text": translated}

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

# Fallback local viewing router (with local translation capability if desired)
@app.get("/event/{uuid}", response_class=HTMLResponse)
def read_event(uuid: str, request: Request, db: Session = Depends(get_db)):
    db_event = db.query(models.Event).filter(models.Event.uuid == uuid).first()
    if not db_event:
        raise HTTPException(status_code=404, detail="존재하지 않는 행사입니다.")
    # Local fallback passes empty public url (will default to relative /api/translate)
    return templates.TemplateResponse(request=request, name="event.html", context={"event": db_event, "public_api_url": ""})

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
