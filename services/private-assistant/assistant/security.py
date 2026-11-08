import hashlib
import hmac
import sqlite3
import time
from pathlib import Path


class Journal:
    """One durable local journal for replay prevention and the total paid-call budget."""
    def __init__(self, path: Path):
        path.parent.mkdir(parents=True, exist_ok=True)
        self.path = path
        with self.connect() as db:
            db.executescript("""
            CREATE TABLE IF NOT EXISTS nonces (nonce TEXT PRIMARY KEY, expires INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS calls (id INTEGER PRIMARY KEY, purpose TEXT NOT NULL,
                created INTEGER NOT NULL, state TEXT NOT NULL, usage TEXT);
            """)

    def connect(self):
        return sqlite3.connect(self.path, timeout=15)

    def authenticate(self, body: bytes, timestamp: str, nonce: str, signature: str, secret: str):
        try:
            issued = int(timestamp)
        except (ValueError, TypeError):
            raise PermissionError("Invalid request signature") from None
        now = int(time.time())
        if abs(now - issued) > 30 or not 16 <= len(nonce) <= 80:
            raise PermissionError("Expired request")
        canonical = f"POST\n/v1/dispatch\n{timestamp}\n{nonce}\n{hashlib.sha256(body).hexdigest()}"
        expected = hmac.new(secret.encode(), canonical.encode(), hashlib.sha256).hexdigest()
        if not hmac.compare_digest(expected, signature):
            raise PermissionError("Invalid request signature")
        try:
            with self.connect() as db:
                db.execute("DELETE FROM nonces WHERE expires < ?", (now,))
                db.execute("INSERT INTO nonces VALUES (?, ?)", (nonce, now + 60))
        except sqlite3.IntegrityError:
            raise PermissionError("Replayed request") from None

    def reserve(self, purpose: str, limit: int) -> int:
        if type(limit) is not int or limit < 0:
            raise ValueError("Invalid local model limit")
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            if limit > 0 and db.execute("SELECT count(*) FROM calls").fetchone()[0] >= limit:
                raise RuntimeError("本轮生成额度已用完，未发起模型请求。仍可检索本地原文和查看已有图谱。")
            return db.execute("INSERT INTO calls(purpose,created,state) VALUES (?,?,?)",
                              (purpose[:80], int(time.time()), "reserved")).lastrowid

    def finish(self, call_id: int, state: str, usage: str = ""):
        with self.connect() as db:
            db.execute("UPDATE calls SET state=?,usage=? WHERE id=?", (state, usage, call_id))

    def status(self, limit: int, include_calls=True):
        if type(limit) is not int or limit < 0:
            raise ValueError("Invalid local model limit")
        with self.connect() as db:
            count = db.execute("SELECT count(*) FROM calls").fetchone()[0]
            rows = db.execute("SELECT id,purpose,created,state,usage FROM calls ORDER BY id").fetchall() if include_calls else []
        value = {"limit": limit or None, "used": count, "remaining": max(0, limit-count) if limit else None,
                 "unlimited": limit == 0}
        if include_calls:
            value["calls"] = [dict(zip(("id", "purpose", "created", "state", "usage"), row)) for row in rows]
        return value
