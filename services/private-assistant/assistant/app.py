import asyncio
import base64
import json
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request, HTTPException
from fastapi.encoders import jsonable_encoder

from .config import Settings
from .database import Databases
from .face import Faces
from .face_identification import FaceIdentification
from .knowledge import Knowledge
from .index_queue import IndexQueue
from .model_proxy import proxy
from .security import Journal

settings = Settings()
databases = Databases(settings)
knowledge = Knowledge(settings, databases)
faces = Faces(settings, databases)
identification = FaceIdentification(settings, databases, faces)
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app):
    settings.validate()
    app.state.index_queue = IndexQueue(databases, knowledge)
    tasks = [asyncio.create_task(app.state.index_queue.process()),
             asyncio.create_task(app.state.index_queue.discover())]
    try:
        yield
    finally:
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)


app = FastAPI(title="MES Private Assistant", lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)


@app.post("/internal/model/v1/chat/completions")
async def business_model(request: Request):
    return await proxy(request,settings)


@app.get("/health")
def health():
    try:
        with databases.admin() as db:
            db.execute("SELECT 1")
        return {"ready": True, "modelsPresent": (settings.root.parent/"models"/"manifest.json").exists()}
    except Exception:
        raise HTTPException(503, "私有资料库暂不可用") from None


def dispatch(uid, action, p):
    if type(uid) is int and uid == 0 and action in FaceIdentification.ACTIONS:
        return identification.dispatch(action, p)
    databases.ensure(uid)
    if action == "status":
        return {"ready": True, "database": databases.identity(uid)[0],
                "budget": Journal(settings.root/"requests.sqlite3").status(settings.llm_limit, include_calls=False)}
    if action == "documents.list":
        return knowledge.listing(uid)
    if action == "documents.upload":
        return knowledge.upload(uid, p["name"], base64.b64decode(p["content"], validate=True), p.get("documentId"))
    if action == "documents.delete":
        return knowledge.delete(uid, p["documentId"])
    if action == "documents.download":
        return knowledge.download(uid, p["documentId"], p.get("version"))
    if action == "documents.preview":
        return knowledge.preview(uid, p["documentId"], p.get("version"), p.get("locator"))
    if action == "jobs.retry":
        return knowledge.retry(uid, p.get("jobId"))
    if action == "jobs.cancel":
        return knowledge.cancel(uid, p.get("jobId"))
    if action == "question":
        return knowledge.ask(uid, p.get("question", ""), p.get("mode", "auto"),
                             p.get("sessionId", "default"), p.get("context"))
    if action == "graph":
        return knowledge.graph(uid, str(p.get("search", "")))
    if action == "graph.build":
        return knowledge.build_graph(uid)
    if action == "preferences.save":
        return knowledge.preferences(uid, p)
    if action == "history":
        with databases.connect(uid) as db:
            return db.execute("SELECT id,question,response,created,valid,session_id FROM history ORDER BY id DESC LIMIT 40").fetchall()
    if action == "history.clear":
        with databases.connect(uid) as db:
            db.execute("DELETE FROM history")
        return {"cleared": True}
    if action == "face.status":
        return faces.status(uid)
    if action == "face.challenge":
        return faces.challenge(uid, p["purpose"])
    if action == "face.enroll":
        return faces.enroll(uid, p)
    if action == "face.verify":
        return faces.login(uid, p)
    if action == "face.frame":
        return faces.check_frame(uid, p["purpose"], p)
    if action == "face.reset":
        return faces.reset(uid, p.get("actor", uid))
    raise ValueError("不支持的操作")


@app.post("/v1/dispatch")
async def signed_dispatch(request: Request):
    # Bound the body while streaming, not only after allocating it.
    chunks, size = [], 0
    async for part in request.stream():
        size += len(part)
        if size > 13*1024*1024:
            raise HTTPException(413, "请求过大")
        chunks.append(part)
    body = b"".join(chunks)
    journal = Journal(settings.root/"requests.sqlite3")
    try:
        journal.authenticate(body, request.headers.get("x-mes-time", ""),
            request.headers.get("x-mes-nonce", ""), request.headers.get("x-mes-signature", ""), settings.secret)
        message = json.loads(body)
        uid = message["uid"]
        if not (type(uid) is int and uid == 0 and message["action"] in FaceIdentification.ACTIONS):
            settings.user_dir(uid)
        data = await asyncio.to_thread(dispatch, uid, message["action"], message.get("payload", {}))
        if message['action'] in {'documents.upload', 'documents.delete', 'jobs.retry',
                                  'graph.build', 'preferences.save'}:
            app.state.index_queue.notify(uid)
        return {"ok": True, "data": jsonable_encoder(data)}
    except BlockingIOError:
        raise HTTPException(429, "检测服务忙，请保持姿态稍后继续。") from None
    except PermissionError:
        raise HTTPException(401, "无效或过期的网关签名") from None
    except (ValueError, KeyError, TypeError) as exc:
        # Messages here are own validation text; do not return malformed external content.
        detail = str(exc) if isinstance(exc, ValueError) and len(str(exc)) < 160 else "请求参数无效"
        raise HTTPException(400, detail) from None
    except Exception:
        logger.exception("Private operation failed: action=%s", message.get("action", "unknown") if 'message' in locals() else "unknown")
        raise HTTPException(503, "私有知识服务暂不可用，请检查数据库、模型文件和服务日志后重试") from None
