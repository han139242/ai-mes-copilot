"""Synthetic identities test storage/auth boundaries, not real-camera accuracy or liveness."""
import json
import os
import time
import uuid

import numpy as np
import pytest

from assistant.config import Settings
from assistant.database import Databases
from assistant.face import Faces
from assistant.face_identification import FaceIdentification

pytestmark = pytest.mark.skipif(os.environ.get("PRIVATE_INTEGRATION") != "1", reason="dedicated stage2 PostgreSQL")


@pytest.fixture
def env(tmp_path, monkeypatch):
    settings = Settings(root=tmp_path)
    dbs = Databases(settings)
    faces = Faces(settings, dbs)
    ids = [2_100_000_000 + int(uuid.uuid4().hex[:6], 16) for _ in range(2)]
    vectors = [np.eye(128)[0], np.eye(128)[1]]
    for uid, vector in zip(ids, vectors):
        dbs.ensure(uid)
        monkeypatch.setattr(faces, "verify_frames", lambda *a, v=vector: v)
        faces.enroll(uid, {"consent": True, "challengeId": "synthetic-storage"})
    monkeypatch.setattr(faces, "extract", lambda frame, pose: vectors[0])
    identify = FaceIdentification(settings, dbs, faces)
    return identify, faces, dbs, ids, vectors


def test_unique_matching_returns_no_identity_until_final_and_uses_aggregate(env, monkeypatch):
    identify, faces, dbs, ids, vectors = env
    challenge = identify.challenge({"eligibleIds": ids})
    assert set(challenge) == {"challengeId", "sequence", "expiresIn"}
    payload = {**challenge, "step": 0, "frame": "synthetic-front", "eligibleIds": ids}
    assert set(identify.frame(payload)) == {"ready", "message"}
    assert identify.frame(payload)["ready"]
    # Final aggregate pointing at a different identity cannot reuse the frontal candidate.
    monkeypatch.setattr(faces, "verify_frames", lambda *a: vectors[1])
    assert identify.verify(payload) == {"verified": False}
    with pytest.raises(ValueError, match="已经使用"):
        identify.verify(payload)


def test_unique_final_rechecks_all_templates_and_returns_internal_uid(env, monkeypatch):
    identify, faces, dbs, ids, vectors = env
    payload = {**identify.challenge({"eligibleIds": ids}), "eligibleIds": ids, "step": 0, "frame": "front"}
    assert identify.frame(payload)["ready"]
    monkeypatch.setattr(faces, "verify_frames", lambda *a: vectors[0])
    assert identify.verify(payload) == {"verified": True, "userId": ids[0]}
    with dbs.connect(ids[0]) as db:
        stored = db.execute("SELECT sequence,expires FROM face_challenge WHERE id=%s", (payload["challengeId"],)).fetchone()
    assert stored["sequence"] == payload["sequence"]
    assert time.time()+40 < stored["expires"] <= time.time()+45


def test_duplicate_faces_are_ambiguous_and_template_changes_invalidate_challenge(env, monkeypatch):
    identify, faces, dbs, ids, vectors = env
    challenge = identify.challenge({"eligibleIds": ids})
    monkeypatch.setattr(faces, "verify_frames", lambda *a: vectors[0])
    faces.enroll(ids[1], {"consent": True, "challengeId": "synthetic-duplicate"})
    with pytest.raises(ValueError, match="变化"):
        identify.frame({**challenge, "step": 0, "frame": "front"})
    duplicate = identify.challenge({"eligibleIds": ids})
    assert not identify.frame({**duplicate, "step": 0, "frame": "front"})["ready"]


def test_empty_or_untrusted_roster_limits_and_expiry_fail_closed(env):
    identify, faces, dbs, ids, vectors = env
    for bad in [list(range(1, 102)), [True], ["1"], [ids[0], ids[0]], [-1]]:
        with pytest.raises(ValueError, match="范围无效"):
            identify.challenge({"eligibleIds": bad})
    with pytest.raises(ValueError, match="唯一识别"):
        identify.challenge({"eligibleIds": []})
    challenge = identify.challenge({"eligibleIds": ids})
    with identify.connect() as db:
        db.execute("UPDATE challenges SET expires=0 WHERE id=?", (challenge["challengeId"],))
    with pytest.raises(ValueError, match="过期"):
        identify.frame({**challenge, "step": 0, "frame": "front"})


def test_unavailable_candidate_is_not_silently_dropped(env, monkeypatch):
    identify, faces, dbs, ids, vectors = env
    challenge = identify.challenge({"eligibleIds": ids})
    original = dbs.connect
    def unavailable(uid):
        if uid == ids[1]:
            raise RuntimeError("synthetic unavailable candidate")
        return original(uid)
    monkeypatch.setattr(dbs, "connect", unavailable)
    with pytest.raises(RuntimeError, match="unavailable"):
        identify.frame({**challenge, "step": 0, "frame": "front"})


def test_final_requires_same_server_roster_and_capacity_is_bounded(env):
    identify, faces, dbs, ids, vectors = env
    payload = {**identify.challenge({"eligibleIds": ids}), "step": 0, "frame": "front"}
    identify.frame(payload)
    with pytest.raises(ValueError, match="唯一识别"):
        identify.verify({**payload, "eligibleIds": [ids[0]]})
    assert identify.capacity.acquire(False) and identify.capacity.acquire(False)
    try:
        with pytest.raises(BlockingIOError):
            identify.dispatch("face.identify.challenge", {"eligibleIds": ids})
    finally:
        identify.capacity.release(); identify.capacity.release()


def test_sides_keep_frontal_binding_and_consistency_without_rescanning_candidates(env, monkeypatch):
    identify, faces, dbs, ids, vectors = env
    payload = {**identify.challenge({'eligibleIds': ids}), 'step': 0, 'frame': 'accepted-front'}
    assert identify.frame(payload)['ready']
    def forbidden(*args, **kw):
        raise AssertionError('Side guidance must not repeat a global identity search')
    monkeypatch.setattr(identify, 'match', forbidden)
    assert identify.frame({**payload,'step':1,'frame':'side','referenceFrame':'accepted-front'})['ready']
    with pytest.raises(ValueError, match='正面采集已变化'):
        identify.frame({**payload,'step':1,'frame':'side','referenceFrame':'unaccepted-front'})
    monkeypatch.setattr(faces, 'extract', lambda frame, pose: vectors[1] if frame=='wrong-side' else vectors[0])
    assert not identify.frame({**payload,'step':1,'frame':'wrong-side','referenceFrame':'accepted-front'})['ready']


def test_rank_uses_one_short_lived_connection_per_database_and_final_keeps_ambiguity(env, monkeypatch):
    identify, faces, dbs, ids, vectors = env
    payload = {**identify.challenge({'eligibleIds': ids}),'eligibleIds':ids,'step':0,'frame':'front'}
    original = dbs.connect; opened=[]
    def counted(uid):
        opened.append(uid); return original(uid)
    monkeypatch.setattr(dbs,'connect',counted)
    assert identify.frame(payload)['ready']
    assert sorted(opened) == sorted(ids)
    # Preview is never sufficient: final must reject newly ambiguous/private-template changes.
    monkeypatch.setattr(faces,'verify_frames',lambda *a:vectors[0])
    faces.enroll(ids[1],{'consent':True,'challengeId':'synthetic-duplicate'})
    with pytest.raises(ValueError,match='变化'):
        identify.verify(payload)


def test_real_challenge_and_pixel_replay_checks_remain_in_final(env, monkeypatch):
    import base64
    import cv2
    identify, faces, dbs, ids, vectors = env
    monkeypatch.setattr(faces, "verify_frames", Faces.verify_frames.__get__(faces))
    rng = np.random.default_rng(129)
    frames = [base64.b64encode(cv2.imencode('.png', rng.integers(0, 255, (160, 160, 3), dtype=np.uint8))[1]).decode()
              for _ in range(3)]
    def payload():
        p = {**identify.challenge({"eligibleIds": ids}), "eligibleIds": ids, "step": 0, "frame": frames[0], "frames": frames}
        assert identify.frame(p)["ready"]
        with identify.connect() as db:
            db.execute("UPDATE challenges SET expires=? WHERE id=?", (time.time()+40, p["challengeId"]))
        return p
    assert identify.verify(payload()) == {"verified": True, "userId": ids[0]}
    with pytest.raises(ValueError, match="重复使用"):
        identify.verify(payload())


def test_versions_are_checked_again_after_ranking(env, monkeypatch):
    identify, faces, dbs, ids, vectors = env
    faces.reset(ids[1], ids[1])
    payload = {**identify.challenge({"eligibleIds": ids}), "step": 0, "frame": "front"}
    original = identify.versions
    calls = []
    def changed_after_snapshot(roster, deadline, **kwargs):
        snapshot = original(roster, deadline, **kwargs)
        calls.append(1)
        if len(calls) == 1:
            # Deliberately bypass the cooperative write guard to exercise the final version check.
            with dbs.connect(ids[1]) as db:
                db.execute("INSERT INTO face_template(id,model,dimension,ciphertext,embedding) VALUES(1,%s,128,%s,%s::vector)",
                           (faces.MODEL, b'synthetic-only', json.dumps(vectors[0].tolist())))
        return snapshot
    monkeypatch.setattr(identify, "versions", changed_after_snapshot)
    with pytest.raises(ValueError, match="变化"):
        identify.frame(payload)


def test_template_mutation_waits_for_shared_identification_guard(env):
    from concurrent.futures import ThreadPoolExecutor, TimeoutError
    identify, faces, dbs, ids, vectors = env
    with ThreadPoolExecutor(max_workers=1) as executor:
        with faces.template_guard():
            future = executor.submit(faces.reset, ids[1], ids[1])
            with pytest.raises(TimeoutError):
                future.result(timeout=0.2)
        assert future.result(timeout=5) == {"enrolled": False}


def test_signed_zero_context_is_exact_and_cannot_access_user_data(tmp_path, monkeypatch):
    import hashlib
    import hmac
    from fastapi.testclient import TestClient
    import assistant.app as module
    settings = Settings(root=tmp_path, secret="synthetic-test-secret-"*3)
    monkeypatch.setattr(module, "settings", settings)
    seen = []
    monkeypatch.setattr(module.identification, "dispatch", lambda action, p: seen.append(action) or {"ready": False})
    def forbidden(*args):
        raise AssertionError("coordinator must never provision a user database")
    monkeypatch.setattr(module.databases, "ensure", forbidden)
    client = TestClient(module.app)
    def send(uid, action):
        body = json.dumps({"uid": uid, "action": action, "payload": {}}).encode()
        stamp, nonce = str(int(time.time())), uuid.uuid4().hex
        canonical = f"POST\n/v1/dispatch\n{stamp}\n{nonce}\n{hashlib.sha256(body).hexdigest()}"
        signature = hmac.new(settings.secret.encode(), canonical.encode(), hashlib.sha256).hexdigest()
        return client.post("/v1/dispatch", content=body, headers={"x-mes-time": stamp, "x-mes-nonce": nonce, "x-mes-signature": signature})
    for action in FaceIdentification.ACTIONS:
        assert send(0, action).status_code == 200
    for uid, action in [(0, "documents.list"), (0, "face.enroll"), (False, "face.identify.frame")]:
        assert send(uid, action).status_code == 400
    assert len(seen) == 3
    assert not (tmp_path / "users" / "0").exists()
