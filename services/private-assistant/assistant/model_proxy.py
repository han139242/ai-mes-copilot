"""Optional loopback-only MES model adapter sharing the durable validation budget."""
import hashlib
import hmac
import json
import uuid

from fastapi import HTTPException

from .llm import ContentFiltered, complete_messages


def gateway_model_token(settings):
    return hmac.new(settings.secret.encode(), b"mes-business-model-v1", hashlib.sha256).hexdigest()


async def proxy(request, settings):
    expected = "Bearer " + gateway_model_token(settings)
    if len(settings.secret.encode()) < 32 or not hmac.compare_digest(request.headers.get("authorization", ""), expected):
        raise HTTPException(401, "无效的本机模型网关凭据")
    body = bytearray()
    async for part in request.stream():
        body.extend(part)
        if len(body) > 200_000:
            raise HTTPException(413, "请求过大")
    try:
        value = json.loads(body)
        messages = value["messages"]
        if value.get("stream") or not isinstance(messages,list) or not 1 <= len(messages) <= 40:
            raise ValueError()
        if any(not isinstance(m,dict) or m.get("role") not in {"system","user","assistant"}
               or not isinstance(m.get("content"),str) for m in messages):
            raise ValueError()
        messages = [{"role":m["role"],"content":m["content"]} for m in messages]
    except (ValueError,TypeError,KeyError):
        raise HTTPException(400, "仅支持有界的文本对话请求") from None
    try:
        answer = await complete_messages(settings,"mes-business-model",messages)
        reason = "stop"
    except ContentFiltered:
        answer, reason = "", "content_filter"
    except RuntimeError as exc:
        raise HTTPException(503,str(exc)) from None
    return {"id":"mes-"+uuid.uuid4().hex,"object":"chat.completion",
            "choices":[{"index":0,"message":{"role":"assistant","content":answer},"finish_reason":reason}]}
