"""Explicit DeepSeek tool calls. Provider responses are data, never tool instructions."""
import base64
import io
import json
import re
import time
import wave
from contextlib import contextmanager, nullcontext
from threading import BoundedSemaphore
from urllib.parse import parse_qsl, urlsplit, urlunsplit

import httpx

from .security import Journal


ENDPOINT = "https://api.deepseek.com/v1/tools/"
MAX_AUDIO_BYTES = 4 * 1024 * 1024
MAX_SPEECH_SECONDS = 30
_SLOTS = BoundedSemaphore(3)
_HTTP_ERRORS = {
    400: "DeepSeek 未能处理本次输入，请核对格式或内容",
    401: "DeepSeek 工具密钥未通过认证，请检查服务端配置",
    402: "DeepSeek 账号额度不足",
    403: "DeepSeek 拒绝了本次请求",
    404: "暂未取得这条网页的可用内容",
    408: "DeepSeek 接收请求超时",
    413: "内容超过 DeepSeek 的接收上限",
    429: "DeepSeek 工具繁忙，请稍后手动重试",
    504: "DeepSeek 处理超时，建议缩小内容后再试",
}


class ToolFailure(ValueError):
    """A user-facing message containing no provider response or credentials."""


def require_confirmation(settings, confirmed):
    if not settings.tools_ready:
        raise ToolFailure("DeepSeek 工具未配置，或当前正在使用离线模式")
    if confirmed is not True:
        raise ToolFailure("请先确认本次发送到 DeepSeek 的内容")


@contextmanager
def _attempt(settings, tool):
    if not settings.tools_ready:
        raise ToolFailure("DeepSeek 工具未配置，或当前正在使用离线模式")
    if not _SLOTS.acquire(blocking=False):
        raise ToolFailure("本机 DeepSeek 工具正在处理其他请求，请稍后再试")
    try:
        journal = Journal(settings.root / "requests.sqlite3")
        try:
            call = journal.reserve(f"deepseek:{tool}", settings.llm_limit)
        except RuntimeError:
            raise ToolFailure("外部调用已达到本地上限，请检查额度设置") from None
        metadata = {"httpStatus": None}
        try:
            yield metadata
            journal.finish(call, "success", json.dumps(metadata))
        except Exception as error:
            journal.finish(call, "failed", json.dumps({**metadata, "errorType": type(error).__name__}))
            if isinstance(error, ToolFailure):
                raise
            if isinstance(error, (httpx.TimeoutException, TimeoutError)):
                raise ToolFailure("DeepSeek 响应超时。本次请求可能已送达，请核对后再试") from None
            raise ToolFailure("DeepSeek 响应无法读取，请稍后手动重试") from None
    finally:
        _SLOTS.release()


@contextmanager
def _request(settings, tool, *, timeout=25, max_bytes=1024 * 1024, **request):
    with _attempt(settings, tool) as metadata:
        started = time.monotonic()
        # A short read timeout on interactive tools also bounds a provider that trickles data.
        with httpx.Client(timeout=httpx.Timeout(timeout, connect=5, read=min(timeout, 45 if tool == "ocr" else 20)),
                          follow_redirects=False) as client:
            with client.stream("POST", ENDPOINT + tool,
                               headers={"Authorization": f"Bearer {settings.deepseek_tools_key.strip()}"},
                               **request) as response:
                status = response.status_code
                metadata["httpStatus"] = status
                if status != 200:
                    raise ToolFailure(_HTTP_ERRORS.get(status, "DeepSeek 工具暂不可用，请稍后再试"))
                body = bytearray()
                for chunk in response.iter_bytes():
                    if len(body) + len(chunk) > max_bytes or time.monotonic() - started > timeout:
                        raise ToolFailure("DeepSeek 返回内容过大或处理超时")
                    body.extend(chunk)
                if time.monotonic() - started > timeout:
                    raise ToolFailure("DeepSeek 处理超时。本次请求可能已送达，请核对后再试")
        value = json.loads(body)
        # OCR may already have sent HTTP 200 to keep the connection open.
        if not isinstance(value, dict) or "error" in value:
            raise ToolFailure("DeepSeek 未完成处理，请检查输入或稍后手动重试")
        yield value


def public_url(value):
    if not isinstance(value, str) or len(value) > 2048 or re.search(r"[\s\\\x00-\x1f\x7f]", value):
        raise ValueError("请输入有效的公开网页链接")
    try:
        parts = urlsplit(value)
        host = (parts.hostname or "").rstrip(".").encode("idna").decode("ascii").lower()
        if (parts.scheme not in {"https", "http"} or parts.username is not None
                or parts.port not in (None, 443 if parts.scheme == "https" else 80)
                or not re.fullmatch(r"(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+(?:[a-z]{2,63}|xn--[a-z0-9-]{2,59})", host)
                or host.rsplit(".", 1)[-1] in {"local", "localhost", "internal", "lan", "test", "invalid", "onion", "home"}):
            raise ValueError()
        # Don't send common credential-bearing links to the provider.
        if any(re.search(r"token|signature|password|secret|credential|authorization|(?:^|[_-])key$|^sig$",
                         key, re.I) for key, _ in parse_qsl(parts.query, keep_blank_values=True)):
            raise ValueError()
    except (ValueError, UnicodeError):
        raise ValueError("仅支持不含登录凭据的公开 HTTP(S) 网页链接") from None
    return urlunsplit((parts.scheme, host, parts.path or "/", parts.query, ""))


def _text(value, maximum):
    return value.strip()[:maximum] if isinstance(value, str) else ""


def search(settings, query, confirmed):
    require_confirmation(settings, confirmed)
    if not isinstance(query, str) or not 1 <= len(query.strip()) <= 128:
        raise ValueError("请输入 1～128 个字符的公开关键词")
    with _request(settings, "search", json={"query": query.strip(), "count": 6, "need_content": False}) as value:
        if not isinstance(value.get("results"), list):
            raise ToolFailure("DeepSeek 搜索结果格式异常")
    results, seen = [], set()
    for item in value["results"]:
        if not isinstance(item, dict):
            continue
        try:
            url = public_url(item.get("url"))
        except ValueError:
            continue
        if url not in seen:
            seen.add(url)
            results.append({"url": url, "title": _text(item.get("title"), 180) or urlsplit(url).hostname,
                            "site": _text(item.get("site_name"), 80) or urlsplit(url).hostname,
                            "excerpt": _text(item.get("snippet"), 450) or _text(item.get("summary"), 450),
                            "date": _text(item.get("date"), 32), "readable": _fetch_allowed(settings, url)})
        if len(results) == 6:
            break
    return {"results": results}


def _fetch_allowed(settings, url):
    parsed = urlsplit(url)
    host = parsed.hostname
    domains = [name.strip().lower() for name in settings.public_fetch_hosts.split(",") if name.strip()]
    return parsed.scheme == "https" and not parsed.query and any(
        host == domain or host.endswith("." + domain) for domain in domains)


def fetch(settings, url, confirmed):
    require_confirmation(settings, confirmed)
    url = public_url(url)
    if not _fetch_allowed(settings, url):
        raise ValueError("仅可读取已配置来源的公开 HTTPS 链接，请去掉查询参数或打开原网页")
    with _request(settings, "fetch", json={"url": url}) as value:
        content = _text(value.get("content"), 12001)
        if not content:
            raise ToolFailure("这条网页没有可读取的正文")
    return {"url": url, "title": _text(value.get("title"), 180) or urlsplit(url).hostname,
            "text": content[:12000], "truncated": len(content) > 12000}


def recognize(settings, content, filename, media_type, expected_pages):
    with _request(settings, "ocr", timeout=190, max_bytes=5 * 1024 * 1024,
                  data={"purpose": "text_recognition"}, files={"file": (filename, content, media_type)}) as value:
        return _recognized_pages(value, expected_pages)


def _recognized_pages(value, expected_pages):
    pages = value.get("pages")
    if (value.get("object") != "ocr.result" or type(value.get("page_count")) is not int
            or value["page_count"] != expected_pages or not isinstance(pages, list) or len(pages) != expected_pages):
        raise ToolFailure("OCR 返回页数与待识别页数不一致，请缩小范围后再试")
    indexed = {}
    for page in pages:
        if not isinstance(page, dict) or type(page.get("index")) is not int:
            raise ToolFailure("OCR 返回了无效的页码")
        index = page["index"]
        if index not in range(expected_pages) or index in indexed or not isinstance(page.get("text"), str):
            raise ToolFailure("OCR 返回了无效的页码或文字")
        if page.get("finish_reason") != "stop":
            raise ToolFailure("OCR 返回了未完整识别的页面，请缩小范围或提高扫描清晰度")
        raw = page["text"]
        text = re.sub(r"<[｜|]box[｜|]>.*?<(?:/[｜|]|[｜|]/)box[｜|]>", "", raw, flags=re.S)
        text = re.sub(r"<(?:/[｜|]|[｜|]/?)ref[｜|]>", "", text)
        indexed[index] = text.replace("\x00", "").strip()
    return [indexed[index] for index in range(expected_pages)]


def _wav_duration(content):
    if not content or len(content) > MAX_AUDIO_BYTES:
        raise ValueError("请选择不超过 4 MB 的录音")
    try:
        with wave.open(io.BytesIO(content), "rb") as audio:
            frames, rate = audio.getnframes(), audio.getframerate()
            if (audio.getsampwidth() != 2 or audio.getnchannels() != 1
                    or rate not in {8000, 16000, 22050, 24000, 32000, 44100, 48000}
                    or not 0 < frames <= rate * MAX_SPEECH_SECONDS
                    or len(audio.readframes(frames)) != frames * 2):
                raise wave.Error()
    except (wave.Error, EOFError):
        raise ValueError("录音需为完整的 16 位单声道 WAV，最长 30 秒") from None
    return frames * 1000 // rate


def transcribe(settings, content, confirmed):
    require_confirmation(settings, confirmed)
    duration = _wav_duration(content)
    with _request(settings, "asr", timeout=45, files={"file": ("recording.wav", content, "audio/wav")}) as value:
        text = _text(value.get("text"), 4000)
        if not text:
            raise ToolFailure("没有识别到语音，可以重新录音或直接输入")
    return {"text": text, "durationMs": duration}


def speak(settings, text, confirmed, before_send=None):
    from websockets.exceptions import InvalidStatus
    from websockets.sync.client import connect

    require_confirmation(settings, confirmed)
    if not isinstance(text, str) or not 1 <= len(text.strip()) <= 600:
        raise ValueError("请选择 1～600 个字符朗读")
    pcm = bytearray()
    deadline = time.monotonic() + 45
    with _attempt(settings, "tts") as metadata:
        try:
            with connect("wss://api.deepseek.com/v1/tools/tts?format=pcm",
                         additional_headers={"Authorization": f"Bearer {settings.deepseek_tools_key.strip()}"},
                         open_timeout=5, close_timeout=1, max_size=MAX_AUDIO_BYTES) as socket:
                metadata["httpStatus"] = 101
                with before_send() if before_send is not None else nullcontext():
                    socket.send(json.dumps({"event": "text", "text": text.strip()}, ensure_ascii=False))
                socket.send('{"event":"finish_text"}')
                while time.monotonic() < deadline:
                    message = socket.recv(timeout=max(0, deadline - time.monotonic()))
                    if isinstance(message, bytes):
                        if len(pcm) + len(message) > MAX_AUDIO_BYTES:
                            raise ToolFailure("合成音频过大，请缩短朗读内容")
                        pcm.extend(message)
                    else:
                        event = json.loads(message)
                        if event.get("event") == "finish":
                            if event.get("code") != "success" or not pcm or len(pcm) % 2:
                                raise ToolFailure("DeepSeek 未完成语音合成")
                            break
                else:
                    raise TimeoutError()
        except InvalidStatus as error:
            status = error.response.status_code
            metadata["httpStatus"] = status
            raise ToolFailure(_HTTP_ERRORS.get(status, "DeepSeek 语音合成暂不可用")) from None
    output = io.BytesIO()
    with wave.open(output, "wb") as audio:
        audio.setparams((1, 2, 24000, 0, "NONE", "not compressed"))
        audio.writeframes(pcm)
    return {"audio": base64.b64encode(output.getvalue()).decode("ascii"), "type": "audio/wav"}
