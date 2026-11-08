"""Account discovery stays in the signed backend; private templates never leave their DB."""
import json
import hashlib
import random
import re
import sqlite3
import threading
import time
import uuid
from contextlib import contextmanager, nullcontext, ExitStack
from collections import OrderedDict

import numpy as np
from psycopg.types.json import Jsonb


class FaceIdentification:
    ACTIONS = frozenset({"face.identify.challenge", "face.identify.frame", "face.identify.verify"})
    FAILURE = "未能唯一识别已录入账号，请重试或使用密码登录。"

    def __init__(self, settings, databases, faces):
        self.s, self.dbs, self.faces = settings, databases, faces
        self.capacity = threading.BoundedSemaphore(2)

    def dispatch(self, action, payload):
        if not self.capacity.acquire(blocking=False):
            raise BlockingIOError()
        try:
            return {"face.identify.challenge": self.challenge, "face.identify.frame": self.frame,
                    "face.identify.verify": self.verify}[action](payload)
        finally:
            self.capacity.release()

    @staticmethod
    def check_deadline(deadline):
        if time.monotonic() > deadline:
            raise ValueError("识别超时，请重新开始或使用密码登录。")

    @contextmanager
    def connect(self):
        self.s.root.mkdir(parents=True, exist_ok=True)
        db = sqlite3.connect(self.s.root / "face-identification.sqlite3", timeout=5)
        db.row_factory = sqlite3.Row
        db.execute("""CREATE TABLE IF NOT EXISTS challenges (
            id TEXT PRIMARY KEY, sequence TEXT NOT NULL, expires REAL NOT NULL,
            roster TEXT NOT NULL, versions TEXT NOT NULL, candidate INTEGER,
            used INTEGER NOT NULL DEFAULT 0)""")
        if 'reference_digest' not in {row[1] for row in db.execute('PRAGMA table_info(challenges)')}:
            try:
                db.execute('ALTER TABLE challenges ADD COLUMN reference_digest TEXT')
            except sqlite3.OperationalError:
                if 'reference_digest' not in {row[1] for row in db.execute('PRAGMA table_info(challenges)')}:
                    raise
        try:
            with db:
                yield db
        finally:
            db.close()

    def roster(self, payload):
        ids = payload.get("eligibleIds")
        if (not isinstance(ids, list) or len(ids) > 100 or
                any(type(uid) is not int or uid <= 0 for uid in ids) or len(set(ids)) != len(ids)):
            raise ValueError("账号范围无效，请使用密码登录。")
        return sorted(ids)

    def versions(self, ids, deadline, *, connections=None, control=None):
        # Catalog inspection enumerates database names only; no provisioning or admin template reads.
        with (nullcontext(control) if control is not None else self.dbs.admin()) as db:
            existing = {row[0] for row in db.execute("SELECT datname FROM pg_database").fetchall()}
        result = {}
        for uid in ids:
            self.check_deadline(deadline)
            name = self.dbs.identity(uid)[0]
            if name not in existing:
                continue
            with (nullcontext(connections(uid)) if connections else self.dbs.connect(uid)) as db:
                db.execute("SET LOCAL statement_timeout='1000ms'")
                row = db.execute("SELECT updated FROM face_template WHERE id=1 AND embedding IS NOT NULL").fetchone()
            result[str(uid)] = str(row["updated"]) if row else None
        return result

    def challenge(self, payload):
        ids = self.roster(payload)
        versions = self.versions(ids, time.monotonic()+15)
        if not any(versions.values()):
            raise ValueError(self.FAILURE)
        token = uuid.uuid4().hex
        sequence = ["front", *random.sample(["left", "right"], 2)]
        with self.connect() as db:
            db.execute("DELETE FROM challenges WHERE expires<?", (time.time(),))
            db.execute("INSERT INTO challenges(id,sequence,expires,roster,versions) VALUES(?,?,?,?,?)",
                       (token, json.dumps(sequence), time.time()+45, json.dumps(ids), json.dumps(versions)))
        return {"challengeId": token, "sequence": sequence, "expiresIn": 45}

    def read(self, payload, consume=False):
        token = payload.get("challengeId")
        if not isinstance(token, str) or not re.fullmatch(r"[a-f0-9]{32}", token):
            raise ValueError("认证步骤无效，请重新开始。")
        with self.connect() as db:
            if consume:
                row = db.execute("UPDATE challenges SET used=1 WHERE id=? AND used=0 AND expires>? RETURNING *",
                                 (token, time.time())).fetchone()
            else:
                row = db.execute("SELECT * FROM challenges WHERE id=? AND used=0 AND expires>?",
                                 (token, time.time())).fetchone()
        if not row:
            raise ValueError("认证步骤已过期或已经使用，请重新开始。")
        return dict(row)

    def match(self, vector, row):
        with self.faces.template_guard() as control:
            return self._match(vector, row, control=control)

    def _match(self, vector, row, *, control=None):
        # One short-lived connection per private DB for this scan, no global/user-shared pool.
        with ExitStack() as stack:
            if control is None:
                control = stack.enter_context(self.dbs.admin())
            opened = OrderedDict()
            def connection(uid):
                if uid in opened:
                    opened.move_to_end(uid)
                    return opened[uid][0]
                # Two concurrent searches must not exhaust PostgreSQL's 40 connections.
                if len(opened) >= 4:
                    _, (_, previous) = opened.popitem(last=False)
                    previous.close()
                lease = ExitStack()
                stack.callback(lease.close)
                db = lease.enter_context(self.dbs.connect(uid))
                db.execute('SET TRANSACTION ISOLATION LEVEL READ COMMITTED')
                opened[uid] = db, lease
                return db
            return self._rank(vector, row, control, connection)

    def _rank(self, vector, row, control, connection):
        deadline = time.monotonic()+15
        versions = json.loads(row["versions"])
        # Include previously empty DBs and detect reset/re-enrollment/new DBs before comparing.
        if self.versions(json.loads(row["roster"]), deadline, connections=connection, control=control) != versions:
            raise ValueError("人脸资料已变化，请重新开始或使用密码登录。")
        scores = []
        for uid, version in versions.items():
            self.check_deadline(deadline)
            if version is None:
                continue
            with nullcontext(connection(int(uid))) as db:
                db.execute("SET LOCAL statement_timeout='1000ms'")
                found = db.execute("""SELECT model,dimension,updated,1-(embedding <=> %s::vector) AS score
                    FROM face_template WHERE id=1 AND embedding IS NOT NULL""",
                    (json.dumps(vector.tolist()),)).fetchone()
            if not found or str(found["updated"]) != version:
                raise ValueError("人脸资料已变化，请重新开始。")
            if found["model"] != self.faces.MODEL or found["dimension"] != 128:
                continue
            score = float(found["score"])
            if not np.isfinite(score):
                raise ValueError(self.FAILURE)
            scores.append((score, int(uid)))
        scores.sort(reverse=True)
        if self.versions(json.loads(row["roster"]), deadline, connections=connection, control=control) != versions:
            raise ValueError("人脸资料已变化，请重新开始。")
        if not scores or scores[0][0] < 0.5 or (len(scores) > 1 and scores[0][0]-scores[1][0] < 0.08):
            return None
        return scores[0][1]

    def frame(self, payload):
        step = payload.get("step")
        if type(step) is not int or not 0 <= step < 3:
            raise ValueError("采集步骤无效，请重新开始。")
        row = self.read(payload)
        sequence = json.loads(row["sequence"])
        answer, vector = self.faces.guidance_vectors(payload, sequence)
        if not answer["ready"]:
            return answer
        if step > 0:
            # A side capture is tied to the exact frontal frame accepted for this nonce.
            # Recheck pose/quality/consistency above, all candidates again on final submission.
            reference = payload.get("referenceFrame")
            if (row['candidate'] is None or not isinstance(reference, str) or
                    row['reference_digest'] != hashlib.sha256(reference.encode()).hexdigest()):
                raise ValueError("正面采集已变化，请重新开始。")
            return answer
        uid = self.match(vector, row)
        if uid is None:
            return {"ready": False, "message": self.FAILURE}
        if step == 0:
            with self.connect() as db:
                result = db.execute("UPDATE challenges SET candidate=?,reference_digest=? WHERE id=? AND used=0 AND expires>?",
                                    (uid, hashlib.sha256(payload['frame'].encode()).hexdigest(), row["id"], time.time()))
                if result.rowcount != 1:
                    raise ValueError("认证步骤已过期，请重新开始。")
            if row["candidate"] not in (None, uid):
                return {"ready": False, "message": "请同一人正对摄像头并保持片刻。"}
        return answer

    def verify(self, payload):
        with self.faces.template_guard() as control:
            return self._verify(payload, control=control)

    def _verify(self, payload, *, control=None):
        row = self.read(payload, consume=True)
        if self.roster(payload) != json.loads(row["roster"]) or row["candidate"] is None:
            raise ValueError(self.FAILURE)
        uid = row["candidate"]
        # Reuse the exact sequence, expiry, single-use, pixel replay and cross-pose checks.
        with self.dbs.connect(uid) as db:
            db.execute("INSERT INTO face_challenge(id,purpose,sequence,expires) VALUES(%s,'login',%s,%s)",
                       (row["id"], Jsonb(json.loads(row["sequence"])), row["expires"]))
        vector = self.faces.verify_frames(uid, "login", row["id"], payload.get("frames", []))
        # The same final aggregate must uniquely identify the account, not just the frontal preview.
        matched = self._match(vector, row, control=control)
        if matched != uid or not self.faces.match(uid, vector)["verified"]:
            return {"verified": False}
        return {"verified": True, "userId": uid}
