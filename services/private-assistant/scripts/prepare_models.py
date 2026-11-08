"""Download pinned OpenCV models and a local CPU embedding model into the private runtime."""
import hashlib
import json
import os
from pathlib import Path
import httpx

root = Path(os.environ.get("PRIVATE_ASSISTANT_ROOT", "D:/AI-MES-Stage2/data")).parent / "models"
face = root/"face"; face.mkdir(parents=True, exist_ok=True)
commit = "48b10fa505aeb51fbd127fc60a5c660040e823b7"
models = [
    ("yunet.onnx", "face_detection_yunet/face_detection_yunet_2023mar.onnx", "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4"),
    ("sface.onnx", "face_recognition_sface/face_recognition_sface_2021dec.onnx", "0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79"),
]
for name, source, expected in models:
    target = face/name
    url=f"https://media.githubusercontent.com/media/opencv/opencv_zoo/{commit}/models/{source}"
    if not target.exists() or hashlib.sha256(target.read_bytes()).hexdigest() != expected:
        temporary=target.with_suffix(".download")
        with httpx.stream("GET", url, follow_redirects=True, timeout=120) as response:
            response.raise_for_status()
            with temporary.open("wb") as stream:
                for part in response.iter_bytes(): stream.write(part)
        if hashlib.sha256(temporary.read_bytes()).hexdigest() != expected:
            raise RuntimeError(f"Model checksum mismatch: {name}")
        temporary.replace(target)
    print(f"Verified {name}: {expected}", flush=True)

from fastembed import TextEmbedding
model=TextEmbedding(model_name="BAAI/bge-small-zh-v1.5", cache_dir=str(root/"embedding"), threads=2)
vector=list(model.embed(["智能调光台灯的装配与检验"]))[0]
assert vector.shape == (512,)
manifest={"opencv_zoo_commit":commit,"embedding_model":"BAAI/bge-small-zh-v1.5", "dimension":512,
          "files":{str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest()
                   for p in root.rglob("*") if p.is_file() and p.name != "manifest.json"}}
(root/"manifest.json").write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding="utf-8")
print("Local embedding verified: 512 dimensions",flush=True)
