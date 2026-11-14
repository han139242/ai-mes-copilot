import asyncio
import base64
import io
import json
import os
import shutil
import uuid
import wave
from dataclasses import replace
from pathlib import Path

import httpx
import pytest
from PIL import Image
from pypdf import PdfReader, PdfWriter

from assistant import deepseek
from assistant.config import Settings
from assistant.database import Databases
from assistant.knowledge import Knowledge
from assistant.llm import ContentFiltered, complete
from assistant.scans import inspect_scan, reviewed_pieces, scan_payload, selected_pages
from assistant.security import Journal


@pytest.fixture
def settings(tmp_path):
    return Settings(root=tmp_path, llm_url="", llm_key="", llm_model="",
                    deepseek_tools_enabled=True, deepseek_tools_key="synthetic-tools-key", llm_limit=0,
                    public_fetch_hosts="example.org")


def mock_provider(monkeypatch, respond):
    client = httpx.Client
    requests = []

    def handle(request):
        assert request.url.host == "api.deepseek.com"
        assert request.headers["authorization"] == "Bearer synthetic-tools-key"
        requests.append(request)
        return respond(request)

    monkeypatch.setattr(deepseek.httpx, "Client", lambda **kw: client(transport=httpx.MockTransport(handle), **kw))
    return requests


def scan_pdf():
    writer = PdfWriter()
    writer.add_blank_page(width=180, height=200)
    writer.add_blank_page(width=260, height=280)
    output = io.BytesIO()
    writer.write(output)
    return output.getvalue()


def wav():
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as audio:
        audio.setparams((1, 2, 16000, 0, "NONE", "not compressed"))
        audio.writeframes(b"\x00\x00" * 8000)
    return buffer.getvalue()


def test_tools_disabled_missing_confirmation_and_unsafe_links_make_zero_calls(settings, monkeypatch):
    requests = mock_provider(monkeypatch, lambda _: pytest.fail("unexpected provider call"))
    with pytest.raises(ValueError, match="离线"):
        deepseek.search(replace(settings, deepseek_tools_enabled=False), "公开规范", True)
    with pytest.raises(ValueError, match="确认"):
        deepseek.search(settings, "公开规范", "true")
    for link in ("http://localhost", "https://127.0.0.1/", "http://127.0.0.0x1/",
                 "https://internal.lan/", "https://example.org:8080/", "https://user@example.org/",
                 "https://example.org/a?access_token=synthetic", "javascript:alert(1)",
                 "https://example.org\\@other.test", "https://example.com/manual",
                 "http://example.org/doc", "https://example.org/doc?lookup=1"):
        with pytest.raises(ValueError, match="公开"):
            deepseek.fetch(settings, link, True)
    assert not requests and not (settings.root / "requests.sqlite3").exists()


def test_public_tools_send_only_query_or_link_and_keep_excerpts_as_text(settings, monkeypatch):
    def respond(request):
        if request.url.path.endswith("/search"):
            assert json.loads(request.content) == {"query": "公开标准", "count": 6, "need_content": False}
            return httpx.Response(200, json={"results": [
                {"title": "<script>inert</script>", "url": "https://example.org/doc#part", "snippet": "公开摘要"},
                {"title": "重复", "url": "https://example.org/doc"},
                {"title": "丢弃", "url": "javascript:invalid"},
            ]})
        assert json.loads(request.content) == {"url": "https://example.org/doc"}
        return httpx.Response(200, json={"title": "公开样例", "content": "文字" * 6500})

    requests = mock_provider(monkeypatch, respond)
    result = deepseek.search(settings, " 公开标准 ", True)
    assert result["results"] == [{"title": "<script>inert</script>", "url": "https://example.org/doc",
                                  "site": "example.org", "excerpt": "公开摘要", "date": "", "readable": True}]
    page = deepseek.fetch(settings, result["results"][0]["url"], True)
    assert page["truncated"] and len(page["text"]) == 12000
    assert [request.url.path for request in requests] == ["/v1/tools/search", "/v1/tools/fetch"]
    assert Journal(settings.root / "requests.sqlite3").status(0)["used"] == 2


def test_public_empty_search_and_http_failure_are_different(settings, monkeypatch):
    responses = iter([httpx.Response(200, json={"results": []}),
                      httpx.Response(402, text="synthetic upstream body")])
    requests = mock_provider(monkeypatch, lambda _: next(responses))
    assert deepseek.search(settings, "公开标准", True) == {"results": []}
    with pytest.raises(ValueError, match="额度不足") as error:
        deepseek.search(settings, "公开标准", True)
    assert "synthetic upstream body" not in str(error.value) and len(requests) == 2
    assert [call["state"] for call in Journal(settings.root / "requests.sqlite3").status(0)["calls"]] == ["success", "failed"]


def test_ocr_schema_strips_layout_marks_and_rejects_truncation_and_in_body_error(settings, monkeypatch):
    complete_response = {"object": "ocr.result", "page_count": 2, "pages": [
        {"index": 1, "text": "20 lx", "finish_reason": "stop"},
        {"index": 0, "text": "<｜ref｜>L100<｜/ref｜><｜box｜>[[1,2,3,4]]<｜/box｜>", "finish_reason": "stop"},
    ]}
    responses = iter([httpx.Response(200, content=("\n\n" + json.dumps(complete_response)).encode()),
                      httpx.Response(200, json={**complete_response,
                          "pages": [complete_response["pages"][0], {"index": 0, "text": "前半页", "finish_reason": "length"}]}),
                      httpx.Response(200, json={"error": {"message": "synthetic upstream body"}}),
                      httpx.Response(200, json={**complete_response, "pages": [complete_response["pages"][0]] * 2})])

    def respond(request):
        assert 'multipart/form-data;' in request.headers["content-type"]
        assert b'name="purpose"' in request.content and b'text_recognition' in request.content
        return next(responses)

    requests = mock_provider(monkeypatch, respond)
    args = (settings, scan_pdf(), "scan.pdf", "application/pdf", 2)
    assert deepseek.recognize(*args) == ["L100", "20 lx"]
    with pytest.raises(ValueError, match="未完整"):
        deepseek.recognize(*args)
    with pytest.raises(ValueError, match="未完成处理") as error:
        deepseek.recognize(*args)
    assert "synthetic upstream body" not in str(error.value)
    with pytest.raises(ValueError, match="页码"):
        deepseek.recognize(*args)
    assert len(requests) == 4


@pytest.mark.skipif(shutil.which("pdftoppm") is None, reason="requires local Poppler renderer")
def test_pdf_rendering_sends_only_selected_pixels_and_keeps_original_page_numbers():
    from pypdf.generic import NameObject, DictionaryObject, DecodedStreamObject

    writer = PdfWriter()
    first = writer.add_blank_page(width=180, height=200)
    second = writer.add_blank_page(width=260, height=280)
    hidden = DecodedStreamObject()
    hidden.set_data(b"q % unselected synthetic resource\nQ")
    shared = writer._add_object(DictionaryObject({NameObject("/XObject"):
        DictionaryObject({NameObject("/Hidden"): writer._add_object(hidden)})}))
    first[NameObject("/Resources")] = shared
    second[NameObject("/Resources")] = shared
    original = io.BytesIO()
    writer.write(original)
    _, native, mime = inspect_scan("sample.pdf", original.getvalue(), 8_000_000)
    assert mime == "application/pdf" and list(native) == [1, 2]
    native[1] = "text from a locally parsed page"
    pages = selected_pages(native, [2])
    payload, _, content_type = scan_payload(original.getvalue(), pages, True, 8_000_000)
    subset = PdfReader(io.BytesIO(payload))
    assert len(subset.pages) == 1 and content_type == "application/pdf"
    assert "/Hidden" not in subset.pages[0]["/Resources"]["/XObject"]
    assert b"unselected synthetic resource" not in payload
    pieces = reviewed_pieces(native, pages, [{"page": 2, "text": "人工核对后的 20 lx"}], 1000)
    corrected = reviewed_pieces(native, [1], [{"page": 1, "text": "补充第一页"}], 1000, pieces)
    assert corrected[0]["text"] == "补充第一页"
    assert corrected[1] == pieces[1]
    assert pieces[1] == {"page": 2, "locator": "第 2 页", "text": "人工核对后的 20 lx", "method": "deepseek-ocr"}
    with pytest.raises(ValueError, match="页码"):
        reviewed_pieces(native, pages, [{"page": 1, "text": "wrong page"}], 1000)


def test_voice_contract_checks_wav_and_requires_terminal_tts_success(settings, monkeypatch):
    import websockets.sync.client

    def respond(request):
        if request.url.path.endswith("/asr"):
            assert b'filename="recording.wav"' in request.content and b'RIFF' in request.content
            return httpx.Response(200, json={"text": "查批次 RM-2048", "duration_ms": 500})
        pytest.fail("only ASR uses HTTP")

    class Socket:
        def __init__(self, result):
            self.frames = iter([b"\x00\x00" * 400, result])
            self.sent = []

        def __enter__(self): return self
        def __exit__(self, *args): pass
        def send(self, message): self.sent.append(json.loads(message))
        def recv(self, timeout):
            frame = next(self.frames)
            if isinstance(frame, Exception):
                raise frame
            return frame

    sockets = [Socket('{"event":"finish","code":"success"}'), Socket(EOFError())]
    remaining = iter(sockets)

    def connect(url, **kwargs):
        assert url == "wss://api.deepseek.com/v1/tools/tts?format=pcm"
        assert kwargs["additional_headers"]["Authorization"] == "Bearer synthetic-tools-key"
        return next(remaining)

    monkeypatch.setattr(websockets.sync.client, "connect", connect)

    requests = mock_provider(monkeypatch, respond)
    with pytest.raises(ValueError, match="WAV"):
        deepseek.transcribe(settings, wav()[:-20], True)
    assert deepseek.transcribe(settings, wav(), True) == {"text": "查批次 RM-2048", "durationMs": 500}
    result = deepseek.speak(settings, "仅朗读合成内容", True)
    with wave.open(io.BytesIO(base64.b64decode(result["audio"]))) as audio:
        assert result["type"] == "audio/wav" and audio.getframerate() == 24000 and audio.getnframes() == 400
    assert sockets[0].sent == [{"event": "text", "text": "仅朗读合成内容"}, {"event": "finish_text"}]
    with pytest.raises(ValueError, match="响应无法读取"):
        deepseek.speak(settings, "上游中断", True)
    assert len(requests) == 1


@pytest.mark.parametrize("status", [200, 400])
def test_official_model_safety_returns_refusal_without_provider_text(settings, monkeypatch, status):
    sent = []

    class Client:
        async def __aenter__(self):
            return self

        async def __aexit__(self, *args):
            pass

        async def post(self, endpoint, **request):
            sent.append(request["json"])
            if status == 400:
                return httpx.Response(status, json={"error": {"message": "Content Exists Risk: synthetic provider data"}})
            return httpx.Response(status, json={"choices": [{"finish_reason": "content_filter",
                                                            "message": {"content": "synthetic provider data"}}]})

    monkeypatch.setattr(httpx, "AsyncClient", lambda **kwargs: Client())
    for url, official in [("https://api.deepseek.com", True), ("https://example.invalid", False)]:
        configured = replace(settings, llm_url=url, llm_key="synthetic-chat-key", llm_model="deepseek-flash",
                             deepseek_safety=True)
        expected = ContentFiltered if official or status == 200 else RuntimeError
        with pytest.raises(expected) as error:
            asyncio.run(complete(configured, "synthetic-test", "synthetic prompt"))
        assert ("safety_check" in sent[-1]) is official
        assert "synthetic provider data" not in str(error.value)
    assert len(sent) == 2


@pytest.mark.skipif(os.environ.get("PRIVATE_INTEGRATION") != "1", reason="requires stage2 PostgreSQL and local models")
def test_review_uses_real_pg_and_index_isolation_and_can_reopen_historical_scan(settings, monkeypatch):
    # Reuse cached local embeddings while creating fresh, synthetic user databases.
    settings = replace(settings, root=Path(os.environ["PRIVATE_ASSISTANT_ROOT"]))
    dbs = Databases(settings)
    uid = 223_000_000_000 + int(uuid.uuid4().hex[:8], 16)
    other = uid + 5_000_000_000
    dbs.ensure(uid)
    dbs.ensure(other)
    kb = Knowledge(settings, dbs)
    image = io.BytesIO()
    Image.new("RGB", (48, 32), "white").save(image, "PNG")
    original = image.getvalue()
    seen = []

    def recognize(_settings, content, filename, media_type, pages):
        seen.append((content, filename, media_type, pages))
        return ["合成扫描规范：L100 的调光检验观察低、中、高三个档位。"]

    monkeypatch.setattr("assistant.knowledge.recognize", recognize)
    prepared = kb.prepare_scan(uid, "合成检验.png", original)
    assert prepared["suggested"] == [1] and not seen
    with pytest.raises(ValueError, match="确认"):
        kb.start_scan(uid, prepared["reviewId"], original, [1], False)
    first = kb.start_scan(uid, prepared["reviewId"], original, [1], True)
    assert seen[0][0].startswith(b"\xff\xd8") and seen[0][1:] == ("scan.jpg", "image/jpeg", 1)
    assert not kb.listing(uid)["documents"]
    with pytest.raises(ValueError, match="不匹配"):
        kb.publish_scan(other, first["reviewId"], original, first["pages"], True)
    with pytest.raises(ValueError, match="不匹配"):
        kb.publish_scan(uid, first["reviewId"], original + b"wrong file", first["pages"], True)
    original_version = kb.publish_scan(uid, first["reviewId"], original, first["pages"], True)
    kb.rebuild(uid, original_version["jobId"])
    cited = kb.ask(uid, "L100 调光检验观察哪些档位？", "rag")
    assert any(c["locator"] == "第 1 页" and "低、中、高" in c["text"] for c in cited["citations"])
    assert not kb.ask(other, "L100 调光检验观察哪些档位？", "rag")["citations"]
    assert kb.discard_scan(uid, first["reviewId"]) == {"discarded": True}
    assert kb.publish_scan(uid, first["reviewId"], original, first["pages"], True) == original_version
    cancelled = kb.prepare_scan(uid, "未发送的合成件.png", original)
    kb.discard_scan(uid, cancelled["reviewId"])
    with pytest.raises(ValueError, match="过期"):
        kb.start_scan(uid, cancelled["reviewId"], original, [1], True)
    assert len(seen) == 1
    doc_id = original_version["id"]
    with pytest.raises(ValueError, match="版本已变化"):
        kb.prepare_scan(uid, "合成检验.png", original, doc_id, 0)
    prepared_again = kb.prepare_scan(uid, "合成检验.png", original, doc_id, 1)
    second = kb.start_scan(uid, prepared_again["reviewId"], original, [1], True)
    second["pages"][0]["text"] = "L100 的教学调光检验观察中、高两个档位。"
    new = kb.publish_scan(uid, second["reviewId"], original, second["pages"], True)
    assert new["id"] == doc_id and new["version"] == 2
    assert "低、中、高" in kb.preview(uid, doc_id, 1, "第 1 页")["pieces"][0]["text"]
    assert Image.open(io.BytesIO(base64.b64decode(kb.scan_page(uid, doc_id, 1, "第 1 页")["content"]))).size == (48, 32)
    with pytest.raises(ValueError, match="不存在"):
        kb.scan_page(other, doc_id, 1, "第 1 页")
    kb.delete(uid, doc_id)
    with pytest.raises(ValueError, match="不存在"):
        kb.scan_page(uid, doc_id, 1, "第 1 页")
