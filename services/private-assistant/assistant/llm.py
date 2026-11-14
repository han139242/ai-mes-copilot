import json
from contextlib import ExitStack
from urllib.parse import urlsplit
import httpx
from .config import Settings
from .security import Journal


class ContentFiltered(RuntimeError):
    def __init__(self):
        super().__init__("模型内容审核未通过，本次未生成回答")


def _official_safety(settings, endpoint):
    if not settings.deepseek_safety or settings.llm_model != "deepseek-flash":
        return False
    try:
        url = urlsplit(endpoint)
        return (url.scheme == "https" and url.hostname == "api.deepseek.com"
                and url.port in (None, 443) and url.username is None and not url.query and not url.fragment)
    except ValueError:
        return False


async def complete(settings: Settings, purpose: str, prompt: str, system_prompt=None, history_messages=None,
                   before_send=None, **kwargs):
    messages = ([{"role": "system", "content": system_prompt}] if system_prompt else [])
    messages += history_messages or []
    messages += [{"role": "user", "content": prompt}]
    return await complete_messages(settings,purpose,messages,before_send=before_send)


async def complete_messages(settings, purpose, messages, before_send=None):
    if not all((settings.llm_url, settings.llm_key, settings.llm_model)):
        raise RuntimeError("尚未配置模型服务；未消耗额度")
    journal = Journal(settings.root / "requests.sqlite3")
    endpoint = settings.llm_url.rstrip("/")
    if not endpoint.endswith("/chat/completions"):
        endpoint += "/chat/completions" if endpoint.endswith("/v1") else "/v1/chat/completions"
    body = {"model": settings.llm_model, "messages": messages, "temperature": 0.1,
            "max_tokens": 3000, "stream": False}
    if _official_safety(settings, endpoint):
        body["safety_check"] = {"level": "high"}
    # The authorization guard runs after client preparation, immediately before accepting
    # an outbound attempt. Revocation can prevent new attempts, not recall one already sent.
    async with httpx.AsyncClient(timeout=120, follow_redirects=False) as client:
      with ExitStack() as authorization:
        if before_send is not None:
            authorization.enter_context(before_send())
        # Keep consent/version mutations serialized until all outbound bytes have been sent.
        # Releasing at send completion avoids holding a user lock while the model thinks.
        sending_model_post = False
        async def sent(event, info):
            nonlocal sending_model_post
            if event.endswith(".send_request_body.started"):
                sending_model_post = getattr(info.get("request"),"method",None) == b"POST"
            # HTTPS proxy CONNECT has its own body-complete event and is not the model POST.
            if event.endswith(".send_request_body.complete") and sending_model_post:
                authorization.close()
        call = journal.reserve(purpose, settings.llm_limit)
        status = None
        try:
            # No SDK retries. Every HTTP attempt reserves a durable budget slot first.
            response = await client.post(endpoint,
                headers={"Authorization": f"Bearer {settings.llm_key}"},
                json=body, extensions={"trace":sent})
            status = response.status_code
            if status == 400 and "safety_check" in body and "content exists risk" in response.text.lower():
                raise ContentFiltered()
            if response.status_code != 200:
                raise RuntimeError(f"模型服务返回 HTTP {response.status_code}")
            value = response.json()
            choice = value["choices"][0]
            if choice.get("finish_reason") == "content_filter" or choice["message"].get("refusal"):
                raise ContentFiltered()
            result = choice["message"]["content"]
            if not isinstance(result, str) or not result.strip():
                raise RuntimeError("模型没有返回文本")
            journal.finish(call, "success", json.dumps(value.get("usage", {})))
            return result
        except Exception as exc:
            # Safe diagnostic metadata only; provider bodies and credential-bearing URLs stay out.
            journal.finish(call, "filtered" if isinstance(exc, ContentFiltered) else "failed",
                           json.dumps({"errorType": type(exc).__name__, "httpStatus": status}))
            if isinstance(exc, ContentFiltered):
                raise
            if status in (401,403):
                message = "模型服务未通过认证，请检查后端模型配置"
            elif status == 402:
                message = "模型服务额度不足，文档原文检索仍可使用"
            elif status == 429:
                message = "模型服务当前繁忙，请稍后再试"
            elif isinstance(exc,httpx.TimeoutException):
                message = "模型回答超时，先展示已检索到的依据"
            elif isinstance(exc,httpx.ConnectError):
                message = "暂时无法连接模型服务，先展示本地依据"
            else:
                message = "模型请求失败，请检查服务后重试"
            raise RuntimeError(message + ("；本次尝试已计入额度" if settings.llm_limit > 0 else "")) from None
