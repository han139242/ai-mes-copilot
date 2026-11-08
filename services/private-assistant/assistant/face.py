import base64
import hashlib
import hmac
import json
import os
import random
import time
import threading
import uuid
from contextlib import contextmanager

os.environ.setdefault("OPENCV_IO_MAX_IMAGE_PIXELS", "2560000")
import cv2
import numpy as np
from cryptography.fernet import Fernet
from psycopg.types.json import Jsonb


class Faces:
    """Server-side 1:1 verification. Motion challenge is not certified anti-spoofing."""
    MODEL = "opencv-sface-2021dec-v1"

    def __init__(self, settings, databases):
        self.s, self.dbs = settings, databases
        key = hmac.new(settings.secret.encode(), b"face-template-encryption-v1", hashlib.sha256).digest()
        self.cipher = Fernet(base64.urlsafe_b64encode(key))
        self._inference_lock = threading.Lock()
        self._engine = None

    @contextmanager
    def template_guard(self):
        # Shared across workers and DBs; admin connection holds only a coordination lock.
        with self.dbs.admin() as control:
            control.execute("SET statement_timeout='5000ms'")
            control.execute("SELECT pg_advisory_lock(728405)")
            try:
                yield control
            finally:
                control.execute("SELECT pg_advisory_unlock(728405)")

    def challenge(self, uid, purpose):
        if purpose not in {"enroll", "login"}:
            raise ValueError("Invalid purpose")
        if purpose == "login" and not self.status(uid)["enrolled"]:
            raise ValueError("无法完成人脸认证，请使用密码登录")
        token = uuid.uuid4().hex
        sequence = ["front", *random.sample(["left", "right"], 2)]
        with self.dbs.connect(uid) as db:
            db.execute("DELETE FROM face_challenge WHERE expires < %s", (time.time(),))
            db.execute("INSERT INTO face_challenge(id,purpose,sequence,expires) VALUES(%s,%s,%s,%s)",
                       (token, purpose, Jsonb(sequence), time.time()+45))
        return {"challengeId": token, "sequence": sequence, "expiresIn": 45,
                "notice": "请本人完成动作。动作检查用于防止简单照片重放，不代表专业活体认证。"}

    def extract(self, frame, pose):
        if not isinstance(frame, str) or len(frame) > 1_400_000:
            raise ValueError("人脸图片过大")
        raw = base64.b64decode(frame.split(",")[-1], validate=True)
        if len(raw) > 1_000_000:
            raise ValueError("人脸图片过大")
        image = cv2.imdecode(np.frombuffer(raw, dtype=np.uint8), cv2.IMREAD_COLOR)
        if image is None or image.shape[0] < 160 or image.shape[1] < 160 or max(image.shape[:2]) > 1600:
            raise ValueError("请使用清晰的摄像头画面")
        # OpenCV inference objects are mutable; reuse weights, serialize all their operations.
        # Images and identity vectors remain request-local and are never cached here.
        with self._inference_lock:
            return self._extract_image(image, pose)

    def _extract_image(self, image, pose):
        if self._engine is None:
            models = self.s.root.parent / "models" / "face"
            detector = cv2.FaceDetectorYN.create(str(models / "yunet.onnx"), "", (image.shape[1], image.shape[0]), 0.85)
            recognizer = cv2.FaceRecognizerSF.create(str(models / "sface.onnx"), "")
            self._engine = detector, recognizer
        detector, recognizer = self._engine
        detector.setInputSize((image.shape[1], image.shape[0]))
        _, found = detector.detect(image)
        if found is None or len(found) != 1:
            raise ValueError("画面中需要且只能有一张脸")
        face = found[0]
        x, y, w, h = face[:4].astype(int)
        if min(w, h) < 80:
            raise ValueError("请靠近摄像头")
        crop = image[max(y, 0):max(y, 0)+h, max(x, 0):max(x, 0)+w]
        gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
        if not 35 < gray.mean() < 225 or cv2.Laplacian(gray, cv2.CV_64F).var() < 35:
            raise ValueError("光线不足或画面模糊，请调整后重试")
        eye_center = (face[4:6] + face[6:8]) / 2
        distance = np.linalg.norm(face[4:6] - face[6:8])
        offset = float((face[8] - eye_center[0]) / max(distance, 1))
        if pose in {"left", "right"} and abs(offset) >= 0.32:
            raise ValueError("转头幅度偏大，请向正面回一点。")
        valid = {"front": abs(offset) < 0.18, "left": offset > 0.12, "right": offset < -0.12}
        if not valid.get(pose, False):
            raise ValueError("动作与提示不符，请重新开始；左右以画面提示为准")
        aligned = recognizer.alignCrop(image, face)
        vector = recognizer.feature(aligned).flatten().astype(float)
        if len(vector) != 128 or not np.isfinite(vector).all() or np.linalg.norm(vector) == 0:
            raise ValueError("无法提取有效人脸特征")
        return vector / np.linalg.norm(vector)

    def verify_frames(self, uid, purpose, challenge_id, frames):
        with self.dbs.connect(uid) as db:
            row = db.execute("""UPDATE face_challenge SET used=true WHERE id=%s AND purpose=%s
                AND NOT used AND expires>%s RETURNING sequence,expires""",
                (challenge_id, purpose, time.time())).fetchone()
        if not row:
            raise ValueError("认证步骤已过期或已经使用，请重新开始")
        if time.time() < row["expires"]-43:
            raise ValueError("请按步骤完成动作后再提交")
        if not isinstance(frames,list) or len(frames) != 3 or not all(isinstance(f,str) for f in frames) or len(set(frames)) != 3:
            raise ValueError("请完成三个不同的动作画面")
        # A fresh nonce must not make a previously submitted capture reusable.
        digests = []
        for frame in frames:
            if not isinstance(frame, str) or len(frame) > 1_400_000:
                raise ValueError("人脸图片过大")
            raw = base64.b64decode(frame.split(',')[-1], validate=True)
            image = cv2.imdecode(np.frombuffer(raw, dtype=np.uint8), cv2.IMREAD_COLOR)
            if image is None or min(image.shape[:2]) < 160 or max(image.shape[:2]) > 1600:
                raise ValueError("请使用有效的摄像头画面")
            # Hash decoded pixels, so JPEG metadata/trailing-byte changes cannot reuse a capture.
            digests.append(hashlib.sha256(str(image.shape).encode()+image.tobytes()).hexdigest())
        vectors = [self.extract(frame, pose) for frame, pose in zip(frames, row["sequence"])]
        import psycopg
        try:
            with self.dbs.connect(uid) as db:
                db.execute("SELECT pg_advisory_xact_lock(728403)")
                # Alignment deliberately makes genuine repeat visits visually similar.
                # Reject exact decoded captures, not the person's perceptual identity.
                db.execute("DELETE FROM face_capture WHERE created < now()-interval '10 minutes'")
                for digest in digests:
                    db.execute("INSERT INTO face_capture(digest) VALUES(%s)", (digest,))
        except psycopg.errors.UniqueViolation:
            raise ValueError("检测到重复使用的画面，请重新使用摄像头采集") from None
        if min(float(np.dot(vectors[0], vector)) for vector in vectors[1:]) < 0.5:
            raise ValueError("动作画面不够一致，请减小转头幅度后重新采集，无需重新录入。")
        vector = np.mean(vectors, axis=0)
        return vector / np.linalg.norm(vector)

    def check_frame(self, uid, purpose, payload):
        """Transient guidance only: no template writes, challenge consumption or login result."""
        step = payload.get("step")
        token = payload.get("challengeId")
        if purpose not in {"enroll", "login"} or type(step) is not int or not 0 <= step < 3:
            raise ValueError("采集步骤无效，请重新开始")
        if not isinstance(token, str) or len(token) != 32:
            raise ValueError("认证步骤无效，请重新开始")
        with self.dbs.connect(uid) as db:
            row = db.execute("""SELECT sequence FROM face_challenge WHERE id=%s AND purpose=%s
                AND NOT used AND expires>%s""", (token, purpose, time.time())).fetchone()
        if not row:
            raise ValueError("认证步骤已过期或已经使用，请重新开始")
        return self.guidance(payload, row["sequence"])

    def guidance(self, payload, sequence):
        return self.guidance_vectors(payload, sequence)[0]

    def guidance_vectors(self, payload, sequence):
        """Reuse within this request only; vectors never enter the response or a cache."""
        step = payload["step"]
        try:
            vector = self.extract(payload.get("frame"), sequence[step])
            if step > 0:
                reference = self.extract(payload.get("referenceFrame"), "front")
                if float(np.dot(reference, vector)) < 0.5:
                    return {"ready": False, "message": "请向正面回一点，保持同一人和相近光线。"}, None
        except ValueError as failure:
            message = str(failure)
            if message.startswith("动作与提示不符"):
                message = "请按提示轻转头，保持片刻。"
            return {"ready": False, "message": message if len(message) < 100 else "请调整姿态和光线。"}, None
        return {"ready": True, "message": "姿态和清晰度合适，请保持。"}, vector

    def enroll(self, uid, payload):
        if payload.get("consent") is not True:
            raise ValueError("需要本人同意在私有资料库保存人脸特征；默认不保存原图")
        vector = self.verify_frames(uid, "enroll", payload["challengeId"], payload.get("frames", []))
        encrypted = self.cipher.encrypt(json.dumps(vector.tolist()).encode())
        with self.template_guard(), self.dbs.connect(uid) as db:
            db.execute("""INSERT INTO face_template(id,model,dimension,ciphertext,embedding) VALUES(1,%s,128,%s,%s::vector)
                ON CONFLICT(id) DO UPDATE SET model=excluded.model,dimension=128,
                ciphertext=excluded.ciphertext,embedding=excluded.embedding,updated=now()""",
                (self.MODEL, encrypted, json.dumps(vector.tolist())))
            db.execute("INSERT INTO audit(action,actor) VALUES('face.enroll',%s)", (uid,))
        return {"enrolled": True, "model": self.MODEL, "dimension": 128}

    def login(self, uid, payload):
        vector = self.verify_frames(uid, "login", payload["challengeId"], payload.get("frames", []))
        return self.match(uid, vector)

    def match(self, uid, vector):
        with self.dbs.connect(uid) as db:
            row = db.execute("""SELECT model,dimension,1-(embedding <=> %s::vector) AS score
                FROM face_template WHERE id=1 AND embedding IS NOT NULL""",
                (json.dumps(vector.tolist()),)).fetchone()
        if not row or row["model"] != self.MODEL or row["dimension"] != 128:
            return {"verified": False}
        score = float(row["score"])
        # Conservative initial policy, pending real camera calibration. Do not claim an accuracy rate.
        return {"verified": bool(np.isfinite(score) and score >= 0.5)}

    def status(self, uid):
        with self.dbs.connect(uid) as db:
            row = db.execute("SELECT model,dimension,updated,embedding IS NOT NULL AS ready FROM face_template WHERE id=1").fetchone()
        if not row:
            return {"enrolled": False, "needsReenroll": False}
        ready = row.pop("ready") and row["model"] == self.MODEL and row["dimension"] == 128
        return {"enrolled": bool(ready), "needsReenroll": not ready, **row}

    def reset(self, uid, actor):
        with self.template_guard(), self.dbs.connect(uid) as db:
            db.execute("DELETE FROM face_template")
            db.execute("DELETE FROM face_challenge")
            db.execute("DELETE FROM face_capture")
            db.execute("INSERT INTO audit(action,actor) VALUES('face.reset',%s)", (actor,))
        return {"enrolled": False}
