import uuid
import io
import datetime
from fastapi import FastAPI, Depends, HTTPException, Request
from fastapi.responses import StreamingResponse, HTMLResponse
from fastapi.templating import Jinja2Templates
from sqlalchemy.orm import Session
import qrcode

import models
from database import engine, get_db

# Create DB tables
models.Base.metadata.create_all(bind=engine)

app = FastAPI(title="행사 관리 API")

# Setup templates
templates = Jinja2Templates(directory="templates")

from pydantic import BaseModel

class EventCreate(BaseModel):
    content: str

@app.post("/api/events")
def create_event(event_in: EventCreate, db: Session = Depends(get_db)):
    if not event_in.content.strip():
        raise HTTPException(status_code=400, detail="행사 내용을 입력해주세요.")
    
    event_uuid = str(uuid.uuid4())
    db_event = models.Event(uuid=event_uuid, content=event_in.content)
    db.add(db_event)
    db.commit()
    db.refresh(db_event)
    return {
        "uuid": db_event.uuid,
        "content": db_event.content,
        "created_at": db_event.created_at.strftime("%Y-%m-%d %H:%M:%S")
    }

@app.get("/api/events")
def list_events(request: Request, db: Session = Depends(get_db)):
    events = db.query(models.Event).order_by(models.Event.id.desc()).all()
    return [{
        "uuid": e.uuid,
        "content": e.content,
        "created_at": e.created_at.strftime("%Y-%m-%d %H:%M:%S"),
        "url": str(request.url_for("read_event", uuid=e.uuid))
    } for e in events]

@app.get("/", response_class=HTMLResponse)
def admin_page(request: Request, db: Session = Depends(get_db)):
    events = db.query(models.Event).order_by(models.Event.id.desc()).all()
    formatted_events = []
    for e in events:
        formatted_events.append({
            "uuid": e.uuid,
            "content": e.content,
            "created_at": e.created_at.strftime("%Y-%m-%d %H:%M:%S"),
            "url": str(request.url_for("read_event", uuid=e.uuid))
        })
    return templates.TemplateResponse(request=request, name="admin.html", context={"events": formatted_events})

@app.get("/event/{uuid}", response_class=HTMLResponse)
def read_event(uuid: str, request: Request, db: Session = Depends(get_db)):
    db_event = db.query(models.Event).filter(models.Event.uuid == uuid).first()
    if not db_event:
        raise HTTPException(status_code=404, detail="존재하지 않는 행사입니다.")
    return templates.TemplateResponse(request=request, name="event.html", context={"event": db_event})

@app.get("/event/{uuid}/qr")
def get_event_qr(uuid: str, request: Request, db: Session = Depends(get_db)):
    db_event = db.query(models.Event).filter(models.Event.uuid == uuid).first()
    if not db_event:
        raise HTTPException(status_code=404, detail="존재하지 않는 행사입니다.")
    
    # URL generation using request context
    event_url = str(request.url_for("read_event", uuid=uuid))
    
    # Generate QR Code
    qr = qrcode.QRCode(version=1, box_size=10, border=4)
    qr.add_data(event_url)
    qr.make(fit=True)

    img = qr.make_image(fill_color="black", back_color="white")
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    buf.seek(0)
    return StreamingResponse(buf, media_type="image/png")
