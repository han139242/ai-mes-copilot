import base64
import hashlib
import io
import json
import logging
import os
import re
import subprocess
import sys
import uuid
from pathlib import Path
from threading import BoundedSemaphore
from contextlib import contextmanager

from psycopg.types.json import Jsonb

from .answers import business_intent, excerpt_answer, generate, graph_citations, merge_citations, route
from .parsing import parse_file
from .vectors import VectorIndex
from .ontology import graph_profile, has_query_evidence, identifier_tokens, is_comparison, missing_identifiers
from .job_policy import load_policy, save_policy, assert_sources
from .deepseek import ToolFailure, recognize, require_confirmation, speak
from .scans import image_for_ocr, inspect_scan, pdf_renderer, render_pdf_pages, reviewed_pieces, scan_payload, selected_pages

WORKERS = BoundedSemaphore(1)
logger = logging.getLogger(__name__)


class Knowledge:
    def __init__(self, settings, databases):
        self.s, self.dbs = settings, databases
        self.vectors = VectorIndex(settings, databases)

    @staticmethod
    def graph_ready(state):
        return bool(state["generation"] and state["graph_revision"] == state["revision"])

    def listing(self, uid):
        with self.dbs.connect(uid) as db:
            docs = db.execute("""SELECT id,name,version,hash,created,vector_version,vector_state,
                vector_error,graph_state,active_hash,active_name FROM documents WHERE NOT deleted ORDER BY created DESC""").fetchall()
            jobs = db.execute("SELECT * FROM jobs ORDER BY created DESC LIMIT 20").fetchall()
            state = db.execute("SELECT * FROM private_state WHERE id=1").fetchone()
        return {"documents": docs, "jobs": jobs, **state,
                "ragReady": any(d["vector_version"]>0 and d["active_hash"] for d in docs),
                "graphReady": self.graph_ready(state),
                "ocrAvailable": self.s.tools_ready,
                "modelConfigured": all((self.s.llm_url, self.s.llm_key, self.s.llm_model))}

    def queue(self, db):
        state = db.execute("SELECT * FROM private_state WHERE id=1").fetchone()
        graph_requested = bool(state["graph_enabled"] and state["model_consent"])
        existing = db.execute("SELECT id FROM jobs WHERE revision=%s AND state IN ('queued','running') LIMIT 1",
                              (state["revision"],)).fetchone()
        if existing:
            if graph_requested:
                db.execute("UPDATE jobs SET graph_requested=true WHERE id=%s", (existing["id"],))
            return existing["id"]
        db.execute("UPDATE jobs SET state='superseded',finished=now() WHERE state='queued'")
        job = uuid.uuid4().hex
        db.execute("""INSERT INTO jobs(id,state,revision,graph_requested,graph_state)
            VALUES (%s,'queued',%s,%s,%s)""",
            (job,state["revision"],graph_requested,"queued" if graph_requested else "not_requested"))
        return job

    def retry(self, uid, job):
        with self.dbs.locked(uid) as db:
            row = db.execute("SELECT * FROM jobs WHERE id=%s AND state IN ('failed','partial','cancelled') AND attempts<3",
                             (job,)).fetchone()
            if not row:
                raise ValueError("仅能重试失败、部分完成或取消的任务，最多尝试三次")
            current = db.execute("SELECT revision FROM private_state WHERE id=1").fetchone()["revision"]
            if current != row["revision"]:
                raise ValueError("资料已经变化，请查看最新任务")
            db.execute("UPDATE jobs SET state='queued',error=null,finished=null WHERE id=%s", (job,))
        return {"jobId": job}

    def cancel(self, uid, job):
        with self.dbs.locked(uid) as db:
            row = db.execute("""UPDATE jobs SET state='cancelled',finished=now()
                WHERE id=%s AND state IN ('queued','running') RETURNING id""", (job,)).fetchone()
        if not row:
            raise ValueError("任务已结束，不能再次取消")
        return {"jobId": job, "cancelled": True}

    def upload(self, uid, name, content, document_id=None, *, validation_policy=None):
        if not isinstance(name,str) or not name.strip():
            raise ValueError("请选择有效文件")
        try:
            pieces = parse_file(name, content, self.s.max_file_bytes, self.s.max_text_chars)
        except UnicodeError:
            raise ValueError("文本文件请使用 UTF-8 编码后重新上传") from None
        except ValueError:
            raise
        except Exception:
            raise ValueError("文件无法解析，请检查格式、完整性以及是否加密") from None
        name = re.split(r"[/\\]", name)[-1][:160]
        digest = hashlib.sha256(content).hexdigest()
        if validation_policy is not None and validation_policy["sources"].get(name) != digest:
            raise ValueError("文件不在本轮批准的合成资料清单中")
        with self.dbs.locked(uid) as db:
            return self._publish_upload(db, uid, name, content, pieces, digest, document_id, validation_policy)

    @staticmethod
    def _previous_document(db, document_id):
        if document_id:
            previous = db.execute("SELECT * FROM documents WHERE id=%s AND NOT deleted", (document_id,)).fetchone()
            if not previous:
                raise ValueError("资料不存在或已删除")
            return previous
        if db.execute("SELECT count(*) AS n FROM documents WHERE NOT deleted").fetchone()["n"] >= 50:
            raise ValueError("每个私有库最多保存 50 份资料，请先整理已有文件")
        return None

    def _publish_upload(self, db, uid, name, content, pieces, digest, document_id, validation_policy=None):
        previous = self._previous_document(db, document_id)
        ocr_pieces = any(piece.get("method") == "deepseek-ocr" for piece in pieces)
        # A reviewed transcription can change without changing the original bytes.
        if previous and previous["hash"] == digest and (not ocr_pieces or previous["pieces"] == pieces):
            original = Path(previous["path"]).resolve()
            if not original.is_relative_to(self.s.user_dir(uid).resolve()):
                raise ValueError("非法文件路径")
            if not original.exists():
                # A same-content replacement repairs a lost original without touching an existing file.
                original.parent.mkdir(parents=True, exist_ok=True)
                with original.open("xb") as stream:
                    stream.write(content)
                return {"id": document_id, "version": previous["version"], "unchanged": True, "restored": True}
            if hashlib.sha256(original.read_bytes()).hexdigest() == digest:
                return {"id": document_id, "version": previous["version"], "unchanged": True}
            # Preserve the damaged copy, publishing the verified upload as a new version below.
        usage = db.execute("SELECT coalesce(sum(size_bytes),0) AS n FROM document_versions").fetchone()["n"]
        if usage+len(content) > 128*1024*1024:
            raise ValueError("私有原件及版本已达到 128 MB 配额，请整理后再上传")
        document_id = document_id or uuid.uuid4().hex
        version = previous["version"]+1 if previous else 1
        path = self.s.user_dir(uid)/"files"/document_id/f"v{version}-{uuid.uuid4().hex[:12]}"
        path.parent.mkdir(parents=True,exist_ok=True)
        with path.open("xb") as stream:
            stream.write(content)
        db.execute("""INSERT INTO documents(id,name,version,hash,path,pieces)
            VALUES(%s,%s,%s,%s,%s,%s) ON CONFLICT(id) DO UPDATE SET
            name=excluded.name,version=excluded.version,hash=excluded.hash,path=excluded.path,
            pieces=excluded.pieces,vector_state='queued',vector_error=null,graph_state='outdated'""",
            (document_id,name,version,digest,str(path),Jsonb(pieces)))
        db.execute("""INSERT INTO document_versions(document_id,version,name,hash,path,size_bytes,extracted_pieces)
            VALUES(%s,%s,%s,%s,%s,%s,%s)""", (document_id,version,name,digest,str(path),len(content),
            Jsonb(pieces) if ocr_pieces else None))
        # The last verified version remains available until this candidate is indexed atomically.
        db.execute("UPDATE private_state SET revision=revision+1,blocked=true WHERE id=1")
        db.execute("UPDATE history SET valid=false WHERE valid")
        job = self.queue(db)
        if validation_policy is not None:
            # A partial import may survive a crash. Bind every committed
            # upload job, before exposing it to the resident worker.
            save_policy(self.s, uid, job, validation_policy["sources"], validation_policy["ceiling"])
        return {"id": document_id, "version": version, "jobId": job}

    def prepare_scan(self, uid, name, content, document_id=None, base_version=None):
        if not self.s.tools_ready:
            raise ValueError("当前未配置云 OCR")
        if not isinstance(name, str) or not name.strip():
            raise ValueError("请选择有效扫描件")
        name = re.split(r"[/\\]", name)[-1][-160:]
        reader, native, _ = inspect_scan(name, content, self.s.max_file_bytes)
        if reader is not None:
            pdf_renderer()
        digest = hashlib.sha256(content).hexdigest()
        review_id = uuid.uuid4().hex
        with self.dbs.locked(uid) as db:
            previous = self._previous_document(db, document_id)
            if previous and (type(base_version) is not int or previous["version"] != base_version):
                raise ValueError("资料版本已变化，请刷新列表后再更新")
            db.execute("DELETE FROM ocr_reviews WHERE expires <= now()")
            if db.execute("SELECT count(*) AS n FROM ocr_reviews WHERE state<>'published'").fetchone()["n"] >= 4:
                raise ValueError("请先完成或关闭已有的扫描件识别")
            db.execute("""INSERT INTO ocr_reviews(id,name,hash,document_id,base_version,pages)
                VALUES(%s,%s,%s,%s,%s,%s)""",
                (review_id, name, digest, document_id, base_version if previous else None, Jsonb([])))
        return {"reviewId": review_id, "name": name, "pageCount": len(native),
                "suggested": [page for page, text in native.items() if not text]}

    @staticmethod
    def _scan_ticket(db, review_id, content):
        if not isinstance(review_id, str) or not re.fullmatch(r"[0-9a-f]{32}", review_id):
            raise ValueError("识别预览编号无效")
        row = db.execute("SELECT * FROM ocr_reviews WHERE id=%s AND expires > now()", (review_id,)).fetchone()
        if not row or not content or len(content) > 8 * 1024 * 1024 or hashlib.sha256(content).hexdigest() != row["hash"]:
            raise ValueError("识别预览已过期或原件不匹配，请重新选择文件")
        return row

    def start_scan(self, uid, review_id, content, selected, confirmed):
        require_confirmation(self.s, confirmed)
        with self.dbs.locked(uid) as db:
            row = self._scan_ticket(db, review_id, content)
            previous = self._previous_document(db, row["document_id"])
            if previous and previous["version"] != row["base_version"]:
                raise ValueError("资料已更新，请重新选择当前版本")
            if row["state"] != "prepared":
                raise ValueError("该扫描预览正在处理或已经识别")
            db.execute("UPDATE ocr_reviews SET state='recognizing' WHERE id=%s", (review_id,))
        try:
            reader, native, _ = inspect_scan(row["name"], content, self.s.max_file_bytes)
            pages = selected_pages(native, selected)
            payload, name, media_type = scan_payload(content, pages, reader is not None, self.s.max_file_bytes)
            with self.dbs.locked(uid) as db:
                current = self._scan_ticket(db, review_id, content)
                if current["state"] != "recognizing":
                    raise ValueError("识别预览已关闭")
                previous = self._previous_document(db, row["document_id"])
                if previous and previous["version"] != row["base_version"]:
                    raise ValueError("资料已更新，请重新选择当前版本")
            output = recognize(self.s, payload, name, media_type, len(pages))
            if not any(output):
                raise ValueError("未识别到可核对的文字，请检查清晰度后再试")
            if sum(map(len, output)) > self.s.max_text_chars:
                raise ValueError("识别文字超过 24 万字符，请拆分后上传")
            review = [{"page": page, "text": text} for page, text in zip(pages, output)]
            with self.dbs.locked(uid) as db:
                saved = db.execute("""UPDATE ocr_reviews SET state='reviewed',pages=%s
                    WHERE id=%s AND state='recognizing' AND expires > now() RETURNING id""",
                    (Jsonb(pages), review_id)).fetchone()
                if not saved:
                    raise ValueError("识别预览已过期，请重新识别")
        except Exception:
            with self.dbs.connect(uid) as db:
                db.execute("UPDATE ocr_reviews SET state='prepared' WHERE id=%s AND state='recognizing'", (review_id,))
            raise
        return {"reviewId": review_id, "name": row["name"], "pages": review,
                "nativePages": sum(bool(text) for page, text in native.items() if page not in pages)}

    def discard_scan(self, uid, review_id):
        if not isinstance(review_id, str) or not re.fullmatch(r"[0-9a-f]{32}", review_id):
            raise ValueError("识别预览编号无效")
        with self.dbs.locked(uid) as db:
            db.execute("DELETE FROM ocr_reviews WHERE id=%s AND state<>'published'", (review_id,))
        return {"discarded": True}

    def publish_scan(self, uid, review_id, content, edited, confirmed):
        if confirmed is not True:
            raise ValueError("请核对扫描件识别结果后再入库")
        with self.dbs.connect(uid) as db:
            row = self._scan_ticket(db, review_id, content)
        if row["state"] == "published":
            return row["receipt"]
        if row["state"] != "reviewed":
            raise ValueError("请先完成扫描识别")
        _, native, _ = inspect_scan(row["name"], content, self.s.max_file_bytes)
        pages = selected_pages(native, row["pages"])
        with self.dbs.locked(uid) as db:
            current = self._scan_ticket(db, review_id, content)
            if current["state"] == "published":
                return current["receipt"]
            if current["state"] != "reviewed":
                raise ValueError("识别预览已提交或过期")
            previous = self._previous_document(db, row["document_id"])
            if previous and previous["version"] != row["base_version"]:
                raise ValueError("资料已更新，请基于当前版本重新识别")
            previous_pieces = previous["pieces"] if previous and previous["hash"] == row["hash"] else ()
            pieces = reviewed_pieces(native, pages, edited, self.s.max_text_chars, previous_pieces)
            result = self._publish_upload(db, uid, row["name"], content, pieces, row["hash"], row["document_id"])
            db.execute("UPDATE ocr_reviews SET state='published',receipt=%s WHERE id=%s", (Jsonb(result), review_id))
            return result

    def delete(self, uid, document_id):
        with self.dbs.locked(uid) as db:
            found = db.execute("SELECT id FROM documents WHERE id=%s AND NOT deleted",(document_id,)).fetchone()
            if not found:
                raise ValueError("资料不存在或已删除")
            db.execute("""UPDATE documents SET deleted=true,pieces='[]',active_pieces='[]',active_hash=null,
                active_name=null,vector_version=0,vector_state='deleted',graph_state='deleted' WHERE id=%s""",
                       (document_id,))
            db.execute("DELETE FROM knowledge_chunks WHERE document_id=%s", (document_id,))
            db.execute("UPDATE private_state SET blocked=true,revision=revision+1 WHERE id=1")
            # Erasure is local and must not depend on another cloud extraction succeeding.
            previous=db.execute("SELECT generation FROM private_state WHERE id=1").fetchone()["generation"]
            remaining=db.execute("SELECT id,version,hash,name FROM documents WHERE NOT deleted").fetchall()
            manifest=[{"key":f"{d['id']}-v{d['version']}","source":f"{d['id']}:v{d['version']}:{d['hash']}:{d['name']}"} for d in remaining]
            retained=self.retain_extractions(db,previous,manifest)
            self.purge_old(uid, db, None, retained)
            db.execute("UPDATE private_state SET generation=null,graph_revision=-1 WHERE id=1")
            db.execute("UPDATE history SET valid=false WHERE valid")
            versions = db.execute("DELETE FROM document_versions WHERE document_id=%s RETURNING path",(document_id,)).fetchall()
            for version in versions:
                db.execute("INSERT INTO pending_files(path) VALUES(%s) ON CONFLICT DO NOTHING",(version["path"],))
            job = self.queue(db)
        self.cleanup_files(uid)
        return {"jobId":job,"revoked":True}

    def version(self, uid, document_id, version=None):
        with self.dbs.connect(uid) as db:
            row = db.execute("""SELECT v.*,d.version AS current_version,d.vector_version,d.pieces FROM document_versions v
                JOIN documents d ON d.id=v.document_id WHERE d.id=%s AND NOT d.deleted
                AND v.version=coalesce(%s,d.version)""",(document_id,version)).fetchone()
        if not row:
            raise ValueError("文件不存在或已删除")
        path = Path(row["path"]).resolve()
        if not path.is_relative_to(self.s.user_dir(uid).resolve()):
            raise ValueError("非法文件路径")
        if not path.is_file():
            raise ValueError("本机原件已丢失。请用相同资料更新以恢复原件；旧版本请从备份恢复。")
        if hashlib.sha256(path.read_bytes()).hexdigest() != row["hash"]:
            raise ValueError("本机原件校验失败。请重新上传正确文件，系统会保留损坏副本并生成新版本。")
        row["resolved_path"] = path
        return row

    def download(self, uid, document_id, version=None):
        row = self.version(uid,document_id,version)
        return {"name":row["name"],"version":row["version"],
                "content":base64.b64encode(row["resolved_path"].read_bytes()).decode()}

    def preview(self, uid, document_id, version=None, locator=None):
        row = self.version(uid,document_id,version)
        if row["version"] == row["current_version"]:
            pieces = row["pieces"]
        elif row.get("extracted_pieces") is not None:
            pieces = row["extracted_pieces"]
        else:
            pieces = parse_file(row["name"],row["resolved_path"].read_bytes(),self.s.max_file_bytes,self.s.max_text_chars)
        if locator:
            pieces = [piece for piece in pieces if piece["locator"]==locator]
        publication = "active" if row["version"]==row["vector_version"] else (
            "pending" if row["version"]==row["current_version"] else "historical")
        return {"id":document_id,"name":row["name"],"version":row["version"],"hash":row["hash"],
                "active":publication=="active","publication":publication,"pieces":pieces[:200],"total":len(pieces)}

    def scan_page(self, uid, document_id, version, locator):
        row = self.version(uid, document_id, version)
        piece = next((p for p in row.get("extracted_pieces") or []
                      if p.get("method") == "deepseek-ocr" and p.get("locator") == locator), None)
        if not piece:
            raise ValueError("这条引用没有可查看的扫描原页")
        content = row["resolved_path"].read_bytes()
        if hashlib.sha256(content).hexdigest() != row["hash"]:
            raise ValueError("本机原件校验失败")
        reader, _, _ = inspect_scan(row["name"], content, self.s.max_file_bytes, read_text=False)
        if reader is not None:
            page, = render_pdf_pages(content, [piece["page"]])
        else:
            with image_for_ocr(content) as image:
                stream = io.BytesIO()
                image.save(stream, "PNG")
                page = stream.getvalue()
        with self.dbs.connect(uid) as db:
            if not db.execute("SELECT 1 FROM documents WHERE id=%s AND NOT deleted", (document_id,)).fetchone():
                raise ValueError("资料已删除，请重新打开引用")
        return {"type": "image/png", "content": base64.b64encode(page).decode("ascii")}

    def read_answer(self, uid, history_id, visible_text, confirmed):
        require_confirmation(self.s, confirmed)
        if type(history_id) is not int:
            raise ValueError("请选择一条有效的知识答复")
        @contextmanager
        def authorize():
            with self.dbs.locked(uid) as db:
                row = db.execute("""SELECT h.response FROM history h JOIN private_state s ON s.id=1
                    WHERE h.id=%s AND h.valid AND h.revision=s.answer_revision AND s.model_consent""",
                    (history_id,)).fetchone()
                if not row or row["response"].get("filtered"):
                    raise ToolFailure("答复已变化或未允许云端处理资料，请重新提问或检查偏好设置")
                if str(row["response"].get("answer", "")).strip()[:600] != visible_text:
                    raise ToolFailure("朗读内容已变化，请重新打开答复")
                yield
        with authorize():
            pass
        return speak(self.s, visible_text, confirmed, before_send=authorize)

    def preferences(self, uid, payload):
        with self.dbs.locked(uid) as db:
            state = db.execute("SELECT * FROM private_state WHERE id=1").fetchone()
            preferences = {**state["preferences"], **{key:str(payload[key])[:80] for key in
                           ("role","level","length","product") if key in payload}}
            if preferences.get("level") not in {"入门","熟悉","专业"} or preferences.get("length") not in {"简洁","详细"}:
                raise ValueError("请选择有效的回答偏好")
            for key in ("modelConsent","graphEnabled"):
                if key in payload and not isinstance(payload[key],bool):
                    raise ValueError("请选择有效的资料处理选项")
            consent = payload.get("modelConsent",state["model_consent"])
            enabled = payload.get("graphEnabled",state["graph_enabled"]) and consent
            db.execute("UPDATE private_state SET preferences=%s,model_consent=%s,graph_enabled=%s WHERE id=1",
                       (Jsonb(preferences),consent,enabled))
            job = None
            if enabled and (not self.graph_ready(state) or not state["graph_enabled"]):
                job = self.queue(db)
        return {"preferences":preferences,"modelConsent":consent,"graphEnabled":enabled,"jobId":job}

    def build_graph(self, uid):
        with self.dbs.locked(uid) as db:
            state = db.execute("SELECT * FROM private_state WHERE id=1").fetchone()
            if not state["model_consent"]:
                raise ValueError("请先允许模型处理资料片段，再构建知识图谱")
            db.execute("UPDATE private_state SET graph_enabled=true WHERE id=1")
            job = self.queue(db)
        return {"jobId":job}

    def worker(self, uid, generation, action, **payload):
        task_id = uuid.uuid4().hex
        output = self.s.user_dir(uid)/f"result-{task_id}.json"
        log = self.s.user_dir(uid)/"worker.log"
        env = {**os.environ,**self.dbs.worker_env(uid),"WORKSPACE":generation,
               "POSTGRES_WORKSPACE":generation,"PYTHONIOENCODING":"utf-8",
               "TIKTOKEN_CACHE_DIR":str(self.s.root.parent/"models"/"tokenizer"),
               "HF_HOME":str(self.s.root.parent/"models"/"huggingface"),
               "HF_HUB_DISABLE_TELEMETRY":"1","XDG_CACHE_HOME":str(self.s.root.parent/"cache")}
        if not WORKERS.acquire(timeout=3):
            raise RuntimeError("图谱正在处理其他任务，文档问答仍可使用，请稍后重试图谱")
        try:
            with log.open("a",encoding="utf-8") as stream:
                process = subprocess.run([sys.executable,"-m","assistant.worker"],
                    input=json.dumps({"uid":uid,"generation":generation,"action":action,"output":str(output),**payload},
                                     ensure_ascii=False),
                    text=True,encoding="utf-8",stdout=stream,stderr=stream,env=env,
                    cwd=str(Path(__file__).resolve().parents[1]),timeout=600)
            result = json.loads(output.read_text(encoding="utf-8")) if output.exists() else {}
            if process.returncode or not result.get("ok"):
                raise RuntimeError("图谱处理未完成。文档检索仍可使用；请检查模型服务或剩余额度后重试。")
            return result["data"]
        except subprocess.TimeoutExpired:
            raise RuntimeError("图谱处理超时，请缩小资料范围后重试；文档检索仍可使用") from None
        finally:
            WORKERS.release()
            output.unlink(missing_ok=True)

    def rebuild(self, uid, job):
        generation = "g"+uuid.uuid4().hex[:20]
        try:
            with self.dbs.index_lease(uid) as acquired:
                if acquired:
                    self._rebuild(uid,job,generation)
        except Exception:
            # Even contention before/after embedding must leave a user-retryable task.
            # A service/database outage is retried by the queue's stale-job recovery.
            try:
                with self.dbs.connect(uid) as db:
                    db.execute("""UPDATE jobs SET state='failed',finished=now(),
                        error='索引暂未完成，可重试；已发布资料仍可问答'
                        WHERE id=%s AND ((state='queued' AND attempts=0)
                            OR (state='running' AND generation=%s))""",(job,generation))
            except Exception:
                logger.warning("Index task is waiting for database recovery")

    def _rebuild(self, uid, job, generation):
        self.cleanup_files(uid)
        with self.dbs.locked(uid) as db:
            row = db.execute("SELECT * FROM jobs WHERE id=%s",(job,)).fetchone()
            state = db.execute("SELECT * FROM private_state WHERE id=1").fetchone()
            if not row or row["state"]!="queued":
                return
            if row["revision"]!=state["revision"]:
                db.execute("UPDATE jobs SET state='superseded',finished=now() WHERE id=%s",(job,))
                return
            revision = state["revision"]
            docs = db.execute("SELECT * FROM documents WHERE NOT deleted ORDER BY id").fetchall()
            assert_sources(load_policy(self.s,uid,job),docs)
            db.execute("UPDATE jobs SET state='running',vector_state='running',attempts=attempts+1,generation=%s,started=now() WHERE id=%s",
                       (generation,job))
        vector_errors = []
        for doc in docs:
            if doc["vector_state"]=="ready" and doc["vector_version"]==doc["version"]:
                continue
            try:
                if not self.vectors.index(uid,doc,job,generation):
                    break
            except Exception:
                vector_errors.append(doc["id"])
                with self.dbs.connect(uid) as db:
                    db.execute("""UPDATE documents SET vector_state='failed',
                        vector_error='本地向量索引未完成，请检查模型文件后重试'
                        WHERE id=%s AND version=%s AND NOT deleted
                        AND EXISTS(SELECT 1 FROM jobs WHERE id=%s AND state='running' AND generation=%s)""",
                        (doc["id"],doc["version"],job,generation))
        with self.dbs.locked(uid) as db:
            current = db.execute("SELECT * FROM private_state WHERE id=1").fetchone()
            running = db.execute("SELECT state,generation FROM jobs WHERE id=%s",(job,)).fetchone()
            if (current["revision"]!=revision or not running or running["state"]!="running"
                    or running['generation']!=generation):
                if running and running["state"]=="running" and running['generation']==generation:
                    db.execute("UPDATE jobs SET state='superseded',finished=now() WHERE id=%s",(job,))
                return
            vector_state = "failed" if vector_errors else "ready"
            db.execute("UPDATE jobs SET vector_state=%s WHERE id=%s",(vector_state,job))
            db.execute("UPDATE private_state SET blocked=%s WHERE id=1",(bool(vector_errors),))
            graph_requested = current["graph_enabled"] and current["model_consent"]
            if not graph_requested or vector_errors:
                db.execute("""UPDATE jobs SET state=%s,graph_state='not_requested',finished=now(),error=%s WHERE id=%s""",
                           ("partial" if vector_errors else "ready",
                            "部分资料的本地索引失败，可重试；其他已就绪资料仍可使用" if vector_errors else None,job))
                return
            db.execute("UPDATE jobs SET graph_state='running' WHERE id=%s",(job,))
            db.execute("UPDATE documents SET graph_state='running' WHERE NOT deleted")
        manifest = [{"key":f"{d['id']}-v{d['version']}",
                     "source":f"{d['id']}:v{d['version']}:{d['hash']}:{d['name']}",
                     "text":json.dumps(d["pieces"],ensure_ascii=False)} for d in docs]
        try:
            with self.dbs.locked(uid) as db:
                reused=self.reuse_extractions(db,state["generation"],generation,manifest)
            if docs:
                self.worker(uid,generation,"index",documents=manifest,revision=revision,jobId=job)
            with self.dbs.locked(uid) as db:
                latest = db.execute("SELECT * FROM private_state WHERE id=1").fetchone()
                running = db.execute("SELECT state,generation FROM jobs WHERE id=%s",(job,)).fetchone()
                if (latest["revision"]!=revision or not running or running["state"]!="running"
                        or running['generation']!=generation or not latest["model_consent"]):
                    if running and running["state"]=="running" and running['generation']==generation:
                        db.execute("UPDATE jobs SET state='superseded',finished=now() WHERE id=%s",(job,))
                    return
                db.execute("UPDATE private_state SET generation=%s,graph_revision=%s,blocked=false WHERE id=1",
                           (generation if docs else None,revision))
                db.execute("UPDATE documents SET graph_state='ready' WHERE NOT deleted")
                db.execute("UPDATE jobs SET state='ready',graph_state='ready',finished=now(),error=null WHERE id=%s",(job,))
                db.execute("INSERT INTO graph_profiles(generation,signature,reused_extractions) VALUES(%s,%s,%s)",
                           (generation,graph_profile(self.s),reused))
                self.purge_old(uid,db,generation if docs else None)
        except Exception:
            with self.dbs.connect(uid) as db:
                db.execute("""UPDATE jobs SET state='partial',graph_state='failed',finished=now(),
                    error='图谱未完成，文档问答仍可使用。检查模型服务或额度后可重试。'
                    WHERE id=%s AND state='running' AND generation=%s""",(job,generation))
                db.execute("""UPDATE documents SET graph_state='failed' WHERE NOT deleted
                    AND EXISTS(SELECT 1 FROM jobs WHERE id=%s AND state='partial')
                    AND (SELECT revision FROM private_state WHERE id=1)=%s""",(job,revision))

    def reuse_extractions(self, db, source, target, manifest):
        if not manifest: return 0
        source=self.extraction_source(db,source)
        if not source: return 0
        profile=db.execute("SELECT signature FROM graph_profiles WHERE generation=%s",(source,)).fetchone()
        if not profile or profile["signature"]!=graph_profile(self.s): return 0
        if not db.execute("SELECT to_regclass('public.lightrag_llm_cache') AS cache").fetchone()["cache"]: return 0
        if not db.execute("SELECT to_regclass('public.lightrag_doc_chunks') AS chunks").fetchone()["chunks"]: return 0
        # Reuse only extraction entries tied to identical active document IDs, versions and hashes.
        # Query answers/keyword caches and changed/deleted sources are deliberately excluded.
        copied=db.execute("""INSERT INTO lightrag_llm_cache
            (workspace,id,original_prompt,return_value,chunk_id,cache_type,queryparam,create_time,update_time)
            SELECT DISTINCT ON(c.id) %s,c.id,c.original_prompt,c.return_value,k.id,c.cache_type,c.queryparam,c.create_time,c.update_time
            FROM lightrag_llm_cache c JOIN lightrag_doc_chunks k
                ON k.workspace=c.workspace AND (k.id=c.chunk_id OR coalesce(k.llm_cache_list,'[]'::jsonb) ? c.id)
            WHERE c.workspace=%s AND c.cache_type='extract' AND k.file_path=ANY(%s) AND k.full_doc_id=ANY(%s)
            ORDER BY c.id,CASE WHEN k.id=c.chunk_id THEN 0 ELSE 1 END,k.id
            ON CONFLICT(workspace,id) DO NOTHING""",
            (target,source,[d["source"] for d in manifest],[d["key"] for d in manifest]))
        return copied.rowcount

    @staticmethod
    def extraction_source(db, source):
        if source: return source
        row=db.execute("SELECT generation FROM graph_profiles WHERE generation ~ '^c[a-f0-9]{20}$' LIMIT 1").fetchone()
        return row["generation"] if row else None

    def retain_extractions(self, db, source, manifest):
        source=self.extraction_source(db,source)
        if not source or not manifest: return None
        target="c"+uuid.uuid4().hex[:20]
        copied=self.reuse_extractions(db,source,target,manifest)
        if not copied: return None
        # Retain the same whitelisted source ledger alongside the extraction cache, so the next
        # rebuild can independently recheck document identity/version/hash. No graph is retained.
        from psycopg import sql
        columns=[row["column_name"] for row in db.execute("""SELECT column_name FROM information_schema.columns
            WHERE table_schema='public' AND table_name='lightrag_doc_chunks' AND column_name<>'workspace'
            ORDER BY ordinal_position""").fetchall()]
        fields=sql.SQL(',').join(map(sql.Identifier,columns))
        selected=sql.SQL(',').join(sql.SQL('k.{}').format(sql.Identifier(name)) for name in columns)
        db.execute(sql.SQL("""INSERT INTO lightrag_doc_chunks(workspace,{})
            SELECT %s,{} FROM lightrag_doc_chunks k WHERE k.workspace=%s
                AND k.file_path=ANY(%s) AND k.full_doc_id=ANY(%s)
                AND EXISTS(SELECT 1 FROM lightrag_llm_cache n WHERE n.workspace=%s
                    AND (n.chunk_id=k.id OR coalesce(k.llm_cache_list,'[]'::jsonb) ? n.id))
            ON CONFLICT(workspace,id) DO NOTHING""").format(fields,selected),
            (target,source,[d["source"] for d in manifest],[d["key"] for d in manifest],target))
        db.execute("INSERT INTO graph_profiles(generation,signature,reused_extractions) VALUES(%s,%s,%s)",
                   (target,graph_profile(self.s),copied))
        return target

    def cleanup_files(self, uid):
        with self.dbs.connect(uid) as db:
            paths = db.execute("SELECT path FROM pending_files").fetchall()
        for item in paths:
            path = Path(item["path"])
            try:
                if path.resolve().is_relative_to(self.s.user_dir(uid).resolve()):
                    path.unlink(missing_ok=True)
                    with self.dbs.connect(uid) as db:
                        db.execute("DELETE FROM pending_files WHERE path=%s",(str(path),))
            except OSError:
                pass

    def purge_old(self, uid, db, active, retained=None):
        from psycopg import sql
        schemas = db.execute("SELECT name FROM ag_catalog.ag_graph").fetchall()
        for schema in schemas:
            name = schema["name"]
            if re.fullmatch(r"g[a-f0-9]{20}_chunk_entity_relation",name) and not (active and name.startswith(active+"_")):
                db.execute("SELECT ag_catalog.drop_graph(%s,true)",(name,))
        tables = db.execute("""SELECT table_name FROM information_schema.columns
            WHERE table_schema='public' AND column_name='workspace'""").fetchall()
        for table in tables:
            if table["table_name"].lower().startswith("lightrag_"):
                keep_cache=retained if table["table_name"].lower() in {"lightrag_llm_cache","lightrag_doc_chunks"} else None
                db.execute(sql.SQL("DELETE FROM {} WHERE workspace<>%s AND workspace<>%s").format(sql.Identifier(table["table_name"])),
                           (active or "",keep_cache or ""))
        db.execute("DELETE FROM graph_profiles WHERE generation<>%s AND generation<>%s",(active or "",retained or ""))

    def ask(self, uid, question, mode, session="default", context=None):
        if mode not in {"auto","rag","graph","fusion"} or not isinstance(question,str) or not 2<=len(question.strip())<=1000:
            raise ValueError("请输入 2～1000 字的问题，并选择有效检索模式")
        if not isinstance(session,str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,80}",session):
            raise ValueError("会话编号无效")
        if context is not None and not isinstance(context,dict):
            raise ValueError("页面上下文无效")
        question = question.strip()
        with self.dbs.connect(uid) as db:
            state = db.execute("SELECT * FROM private_state WHERE id=1").fetchone()
            history = db.execute("""SELECT question,response FROM history WHERE valid AND revision=%s AND session_id=%s
                ORDER BY id DESC LIMIT 3""",(state["answer_revision"],session)).fetchall()
            docs = db.execute("""SELECT id,active_name AS name,vector_version AS version,active_hash AS hash,
                active_pieces AS pieces FROM documents WHERE NOT deleted AND vector_version>0""").fetchall()
            pending = db.execute("""SELECT name,version,vector_version FROM documents
                WHERE NOT deleted AND vector_version>0 AND vector_version<>version""").fetchall()
        business = business_intent(question)
        if business:
            result = {"answer": "这需要读取 MES 的实时业务数据。点击下方“转到业务助手”，我会带上你的原问题。"
                      +("涉及修改时仍会先展示操作预览，确认后才执行。" if business=="write" else "资料问答不会代替当前业务记录。"),
                      "generated":False,"notice":"未调用模型，未修改业务数据","citations":[],"mode":"business",
                      "requestedMode":mode,"routeReason":"已识别为实时业务请求，使用原 MES 权限与确认流程",
                      "businessHandoff":True,"generation":state["generation"],"revision":state["answer_revision"],
                      "sourceRevision":state["revision"],"graphEvidence":{}}
            return self.remember(uid,question,session,state,result)
        effective = question
        if history and not identifier_tokens(question) and re.match(
                r"^(它|这个|那个|刚才|上面|那|再|详细|简短|继续|换个)",question):
            effective = history[0]["question"]+"；后续问题："+question
        actual, reason = route(effective,mode,self.graph_ready(state))
        if pending:
            reason += "；新版本尚未就绪，沿用已发布的旧版本，具体版本见引用"
        local = self.vectors.retrieve(uid,effective)
        graph_data, remote = {}, []
        if actual in {"graph","fusion"}:
            try:
                queried = self.worker(uid,state["generation"],"retrieve",question=effective,mode=actual,
                                      revision=state["revision"])
                graph_data = queried.get("data",{})
                remote = graph_citations(graph_data,docs)
                if not remote or not has_query_evidence(effective, remote):
                    actual, reason = "rag","图检索未形成可追溯依据，本次使用文档原文"
                    graph_data,remote = {},[]
            except RuntimeError:
                actual, reason = "rag","图检索暂不可用，本次使用文档原文"
        # Preserve the most relevant excerpts, with a slot for graph evidence.
        if actual == "fusion":
            local_documents = {row["documentId"] for row in local[:3]}
            graph_source = next((row for row in remote if row["documentId"] not in local_documents), None)
            if graph_source is None and remote:
                graph_source = remote[0]
            citations = merge_citations(local[:3], [graph_source] if graph_source else [], local[3:], remote)
        else:
            citations = merge_citations(remote, local)
        if actual != "rag" and missing_identifiers(effective, citations):
            sources = remote + local
            reserved = []
            for token in missing_identifiers(effective, [])[:4]:
                if not missing_identifiers(token, reserved):
                    continue
                source = next((row for row in sources if not missing_identifiers(token, [row])), None)
                if source:
                    reserved.append(source)
            if reserved:
                citations = merge_citations(reserved, citations, remote, local)
        if not has_query_evidence(effective, citations):
            citations,graph_data = [],{}
            actual,reason = "rag","当前资料没有目标编号的有效依据，未改用相近编号"
        @contextmanager
        def authorize():
            # This acceptance point is shared with preference/version mutations. A request already
            # accepted by the provider cannot be recalled; later requests must use fresh consent.
            with self.dbs.locked(uid) as db:
                latest = db.execute("SELECT * FROM private_state WHERE id=1").fetchone()
                if not latest["model_consent"] or latest["revision"]!=state["revision"] or latest["answer_revision"]!=state["answer_revision"]:
                    raise ValueError("资料或云处理授权已变化，未发起新的模型请求")
                yield
        missing = missing_identifiers(effective, citations)
        coverage_notice = "当前引用未覆盖编号：" + "、".join(missing) if missing else ""
        if citations and missing and is_comparison(effective):
            generated = {"answer": excerpt_answer(citations), "generated": False,
                         "notice": coverage_notice + "。先展示已找到的原文，暂不生成对比结论。"}
        else:
            generated = generate(self.s,uid,question,citations,state,history,graph_data,
                                 {key:str(value)[:500] for key,value in (context or {}).items()
                                  if key in {"route","pageTitle","product","recordNumber"}},authorize=authorize)
            if citations and coverage_notice:
                generated["notice"] = "。".join(filter(None, [coverage_notice, generated["notice"]]))
        filtered = generated.get("filtered", False)
        result = {**generated,"citations":[] if filtered else citations,"mode":actual,"requestedMode":mode,"routeReason":reason,
                  "missingIdentifiers":missing,
                  "generation":state["generation"],"revision":state["answer_revision"],"sourceRevision":state["revision"],
                  "graphEvidence":{} if filtered else {key:graph_data.get(key,[]) for key in ("entities","relationships")}}
        return self.remember(uid,question,session,state,result)

    def remember(self, uid, question, session, state, result):
        with self.dbs.locked(uid) as db:
            latest = db.execute("SELECT * FROM private_state WHERE id=1").fetchone()
            if latest["revision"]!=state["revision"] or latest["answer_revision"]!=state["answer_revision"] or latest["model_consent"]!=state["model_consent"]:
                raise ValueError("提问期间资料或处理授权已变化，请重新提问")
            saved = db.execute("""INSERT INTO history(question,response,generation,revision,session_id)
                VALUES(%s,%s,%s,%s,%s) RETURNING id""",(question,Jsonb(result),state["generation"],state["answer_revision"],session)).fetchone()
        return {**result, "historyId": saved["id"]}

    def graph(self, uid, search=""):
        with self.dbs.connect(uid) as db:
            state = db.execute("SELECT * FROM private_state WHERE id=1").fetchone()
        if not self.graph_ready(state):
            return {"ready":False,"nodes":[],"edges":[],"reason":"图谱尚未就绪，文档问答仍可使用"}
        result = self.worker(uid,state["generation"],"graph",search=str(search)[:80])
        with self.dbs.connect(uid) as db:
            current = db.execute("SELECT * FROM private_state WHERE id=1").fetchone()
            if current["revision"]!=state["revision"] or not self.graph_ready(current):
                raise ValueError("资料正在变化，请重新加载图谱")
        return {**result,"ready":True,"revision":state["revision"]}
