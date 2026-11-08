"""Storage/challenge checks only; synthetic vectors/images do not prove camera recognition."""
import base64
import os
import time
import uuid

import cv2
import numpy as np
import pytest

from assistant.config import Settings
from assistant.database import Databases
from assistant.face import Faces

pytestmark=pytest.mark.skipif(os.environ.get("PRIVATE_INTEGRATION")!="1",reason="requires dedicated stage2 PostgreSQL")


def setup():
    dbs=Databases(Settings())
    uid=2_000_000_000+int(uuid.uuid4().hex[:6],16)
    dbs.ensure(uid)
    return uid,dbs,Faces(dbs.s,dbs)


def test_actual_vector_column_comparison_status_and_reset(monkeypatch):
    uid,dbs,faces=setup()
    vector=np.zeros(128); vector[0]=1
    monkeypatch.setattr(faces,"verify_frames",lambda *args:vector)
    with pytest.raises(ValueError): faces.enroll(uid,{"consent":False})
    assert not faces.status(uid)["enrolled"]
    faces.enroll(uid,{"consent":True,"challengeId":"synthetic-storage-test"})
    with dbs.connect(uid) as db:
        assert db.execute("SELECT vector_dims(embedding) AS n FROM face_template").fetchone()["n"]==128
    assert faces.login(uid,{"challengeId":"synthetic-comparison"})=={"verified":True}
    assert set(faces.status(uid))=={"enrolled","needsReenroll","model","dimension","updated"}
    vector[0]=0; vector[1]=1
    assert faces.login(uid,{"challengeId":"synthetic-mismatch"})=={"verified":False}
    faces.reset(uid,uid)
    assert not faces.status(uid)["enrolled"]
    assert not faces.login(uid,{"challengeId":"synthetic-after-reset"})["verified"]


def test_challenge_is_consumed_on_invalid_attempt():
    uid,dbs,faces=setup()
    challenge=faces.challenge(uid,"enroll")
    with dbs.connect(uid) as db:
        db.execute("UPDATE face_challenge SET expires=%s WHERE id=%s",(time.time()+40,challenge["challengeId"]))
    with pytest.raises(ValueError,match="三个不同"):
        faces.verify_frames(uid,"enroll",challenge["challengeId"],["same"]*3)
    with pytest.raises(ValueError,match="已经使用"):
        faces.verify_frames(uid,"enroll",challenge["challengeId"],[])


def test_guidance_is_challenge_bound_and_does_not_enroll_or_consume(monkeypatch):
    uid, dbs, faces = setup()
    challenge = faces.challenge(uid, "enroll")
    observed = []
    monkeypatch.setattr(faces, "extract", lambda frame, pose: observed.append(pose))
    payload = {"challengeId": challenge["challengeId"], "step": 0, "frame": "synthetic-guidance-only"}
    assert faces.check_frame(uid, "enroll", payload)["ready"]
    assert observed == [challenge["sequence"][0]]
    assert not faces.status(uid)["enrolled"]
    with dbs.connect(uid) as db:
        assert not db.execute("SELECT used FROM face_challenge WHERE id=%s", (challenge["challengeId"],)).fetchone()["used"]
    with pytest.raises(ValueError, match="过期"):
        faces.check_frame(uid, "login", payload)
    other_uid, _, _ = setup()
    with pytest.raises(ValueError, match="过期"):
        faces.check_frame(other_uid, "enroll", payload)
    for step in [True, -1, 3, "0"]:
        with pytest.raises(ValueError, match="步骤无效"):
            faces.check_frame(uid, "enroll", {**payload, "step": step})
    with dbs.connect(uid) as db:
        db.execute("UPDATE face_challenge SET expires=%s WHERE id=%s", (time.time()-1, challenge["challengeId"]))
    with pytest.raises(ValueError, match="过期"):
        faces.check_frame(uid, "enroll", payload)


def test_guidance_rejects_inconsistent_side_before_accepting_the_capture(monkeypatch):
    uid, dbs, faces = setup()
    reference = np.zeros(128); reference[0] = 1
    side = np.zeros(128); side[1] = 1
    monkeypatch.setattr(faces, "extract", lambda frame, pose: reference if pose == "front" else side)
    challenge = faces.challenge(uid, "enroll")
    payload = {"challengeId": challenge["challengeId"], "step": 1, "frame": "side", "referenceFrame": "front"}
    assert not faces.check_frame(uid, "enroll", payload)["ready"]
    side[0] = 0.8; side[1] = 0.6
    assert faces.check_frame(uid, "enroll", payload)["ready"]


def test_real_detector_rejects_blank_guidance_without_storing_a_template():
    uid, dbs, faces = setup()
    challenge = faces.challenge(uid, "enroll")
    ok, encoded = cv2.imencode(".jpg", np.zeros((240, 320, 3), dtype=np.uint8))
    assert ok
    response = faces.check_frame(uid, "enroll", {"challengeId": challenge["challengeId"],
        "step": 0, "frame": base64.b64encode(encoded).decode()})
    assert response["ready"] is False
    assert "一张脸" in response["message"]
    assert not faces.status(uid)["enrolled"]


def test_new_captures_of_same_identity_are_allowed_but_exact_replay_is_rejected(monkeypatch):
    uid,dbs,faces=setup()
    vector=np.zeros(128); vector[0]=1
    monkeypatch.setattr(faces,"extract",lambda *args:vector)
    monkeypatch.setattr(faces,"status",lambda *args:{"enrolled":True})
    rng=np.random.default_rng(731)
    frames=[]
    for _ in range(6):
        ok,data=cv2.imencode('.png',rng.integers(0,255,(160,160,3),dtype=np.uint8))
        assert ok
        frames.append(base64.b64encode(data.tobytes()).decode())
    def challenge():
        value=faces.challenge(uid,"login")
        with dbs.connect(uid) as db:
            db.execute("UPDATE face_challenge SET expires=%s WHERE id=%s",(time.time()+40,value["challengeId"]))
        return value["challengeId"]
    assert np.allclose(faces.verify_frames(uid,"login",challenge(),frames[:3]),vector)
    assert np.allclose(faces.verify_frames(uid,"login",challenge(),frames[3:]),vector)
    with pytest.raises(ValueError,match="重复使用"):
        faces.verify_frames(uid,"login",challenge(),frames[:3])
