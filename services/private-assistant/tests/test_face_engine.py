"""Model reuse and serialization, using generated blank images only."""
import base64
import threading
import time
import gc
import weakref
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import cv2
import numpy as np
import pytest

from assistant.config import Settings
from assistant.face import Faces


def frame(width, height):
    ok, image = cv2.imencode('.png', np.zeros((height, width, 3), dtype=np.uint8))
    assert ok
    return base64.b64encode(image).decode()


def test_reused_mutable_engine_is_serialized_and_never_caches_an_identity(tmp_path, monkeypatch):
    faces = Faces(Settings(root=tmp_path, secret='synthetic-only-'*3), None)
    active = 0; maximum = 0
    vectors = []
    def extract(image, pose):
        nonlocal active, maximum
        active += 1; maximum = max(maximum, active)
        time.sleep(.02)
        active -= 1
        vector = np.eye(128)[0]
        vectors.append(weakref.ref(vector))
        return vector
    monkeypatch.setattr(faces, '_extract_image', extract)
    synthetic = frame(320, 240)
    with ThreadPoolExecutor(max_workers=4) as executor:
        assert len(list(executor.map(lambda _: faces.extract(synthetic, 'front'), range(6)))) == 6
    assert maximum == 1
    gc.collect()
    assert all(reference() is None for reference in vectors), 'request identity vectors must be released'


@pytest.mark.skipif(not (Settings().root.parent/'models'/'face'/'sface.onnx').exists(), reason='local face models required')
def test_real_engine_constructed_once_and_resized_without_accepting_blank_frames(monkeypatch):
    faces = Faces(Settings(), None)
    create_detector, create_recognizer = cv2.FaceDetectorYN.create, cv2.FaceRecognizerSF.create
    calls = []
    def detector(*a, **kw):
        calls.append('detector'); return create_detector(*a, **kw)
    def recognizer(*a, **kw):
        calls.append('recognizer'); return create_recognizer(*a, **kw)
    monkeypatch.setattr(cv2.FaceDetectorYN, 'create', detector)
    monkeypatch.setattr(cv2.FaceRecognizerSF, 'create', recognizer)
    for size in [(320,240), (640,480), (320,240)]:
        with pytest.raises(ValueError, match='一张脸'):
            faces.extract(frame(*size), 'front')
    assert calls == ['detector', 'recognizer']
