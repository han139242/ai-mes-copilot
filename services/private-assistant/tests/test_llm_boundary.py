import asyncio
from contextlib import contextmanager
import json
from types import SimpleNamespace

import httpx
import pytest

from assistant.config import Settings
from assistant.llm import complete
from assistant.security import Journal


@pytest.mark.parametrize("url,expected",[
    ("https://example.invalid","https://example.invalid/v1/chat/completions"),
    ("https://example.invalid/v1/","https://example.invalid/v1/chat/completions"),
    ("https://example.invalid/v1/chat/completions","https://example.invalid/v1/chat/completions"),
])
def test_compatible_endpoint_and_no_hidden_retry(tmp_path,monkeypatch,url,expected):
    seen=[]
    class Client:
        async def __aenter__(self): return self
        async def __aexit__(self,*args): pass
        async def post(self,endpoint,**kwargs):
            seen.append(endpoint)
            return httpx.Response(200,json={"choices":[{"message":{"content":"合成回答 [S1]"}}],"usage":{}})
    monkeypatch.setattr(httpx,"AsyncClient",lambda **kwargs:Client())
    s=Settings(root=tmp_path,llm_url=url,llm_key="synthetic-key",llm_model="synthetic-model",llm_limit=1)
    assert asyncio.run(complete(s,"synthetic-test","synthetic prompt"))=="合成回答 [S1]"
    assert seen==[expected]
    with pytest.raises(RuntimeError,match="额度已用完"):
        asyncio.run(complete(s,"after-restart","synthetic prompt"))
    assert len(seen)==1 and Journal(tmp_path/"requests.sqlite3").status(1)["used"]==1


def test_failure_is_counted_once_and_provider_body_is_not_propagated(tmp_path,monkeypatch):
    seen=[]
    class Client:
        async def __aenter__(self): return self
        async def __aexit__(self,*args): pass
        async def post(self,*args,**kwargs):
            seen.append(True)
            return httpx.Response(503,text="provider body with synthetic sensitive value")
    monkeypatch.setattr(httpx,"AsyncClient",lambda **kwargs:Client())
    s=Settings(root=tmp_path,llm_url="https://example.invalid",llm_key="synthetic-key",llm_model="synthetic-model",llm_limit=20)
    with pytest.raises(RuntimeError,match="本次尝试已计入额度") as failure:
        asyncio.run(complete(s,"synthetic-test","synthetic prompt"))
    assert "provider body" not in str(failure.value)
    assert len(seen)==1
    assert Journal(tmp_path/"requests.sqlite3").status(20)["calls"][0]["state"]=="failed"


def test_revocation_guard_is_checked_after_client_preparation_before_budget_or_outbound(tmp_path,monkeypatch):
    consent={"allowed":True}; sent=[]
    class Client:
        async def __aenter__(self):
            consent["allowed"]=False
            return self
        async def __aexit__(self,*args): pass
        async def post(self,*args,**kwargs): sent.append(True)
    monkeypatch.setattr(httpx,"AsyncClient",lambda **kwargs:Client())
    @contextmanager
    def authorize():
        if not consent["allowed"]: raise ValueError("云处理授权已撤销")
        yield
    s=Settings(root=tmp_path,llm_url="https://example.invalid",llm_key="synthetic-key",llm_model="synthetic-model")
    with pytest.raises(ValueError,match="已撤销"):
        asyncio.run(complete(s,"synthetic-test","synthetic prompt",before_send=authorize))
    assert not sent and Journal(tmp_path/"requests.sqlite3").status(20)["used"]==0


def test_authorization_covers_reservation_and_real_send_but_not_model_response_wait(tmp_path,monkeypatch):
    # A real loopback HTTP transport exercises httpcore's body-sent trace; no provider is used.
    state={"locked":False,"responding":False,"released_before_answer":False}
    reserve=Journal.reserve
    def reservation(self,*args,**kwargs):
        assert state["locked"]
        return reserve(self,*args,**kwargs)
    monkeypatch.setattr(Journal,"reserve",reservation)
    @contextmanager
    def authorize():
        state["locked"]=True
        try: yield
        finally:
            state["released_before_answer"]=not state["responding"]
            state["locked"]=False
    async def run():
        async def handle(reader,writer):
            header=await reader.readuntil(b"\r\n\r\n")
            length=int(next(line.split(b":",1)[1] for line in header.split(b"\r\n") if line.lower().startswith(b"content-length:")))
            await reader.readexactly(length)
            await asyncio.sleep(0.05)
            assert not state["locked"],"The user must be able to revoke consent while waiting for an answer"
            state["responding"]=True
            content=json.dumps({"choices":[{"message":{"content":"synthetic loopback reply"}}]}).encode()
            writer.write(b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+str(len(content)).encode()+b"\r\nConnection: close\r\n\r\n"+content)
            await writer.drain(); writer.close(); await writer.wait_closed()
        server=await asyncio.start_server(handle,"127.0.0.1",0)
        port=server.sockets[0].getsockname()[1]
        try:
            s=Settings(root=tmp_path,llm_url=f"http://127.0.0.1:{port}",llm_key="synthetic-key",llm_model="synthetic-model")
            assert await complete(s,"synthetic-loopback","synthetic prompt",before_send=authorize)=="synthetic loopback reply"
        finally:
            server.close(); await server.wait_closed()
    asyncio.run(run())
    assert state["released_before_answer"] and not state["locked"]


def test_https_proxy_connect_does_not_release_model_authorization(tmp_path,monkeypatch):
    state={"locked":False,"releases":0}
    @contextmanager
    def authorize():
        state["locked"]=True
        try: yield
        finally:
            state["locked"]=False; state["releases"]+=1
    class Client:
        async def __aenter__(self): return self
        async def __aexit__(self,*args): pass
        async def post(self,*args,**kwargs):
            trace=kwargs["extensions"]["trace"]
            await trace("http11.send_request_body.started",{"request":SimpleNamespace(method=b"CONNECT")})
            await trace("http11.send_request_body.complete",{})
            assert state["locked"],"A proxy tunnel cannot release the model request's authorization"
            await trace("http11.send_request_body.started",{"request":SimpleNamespace(method=b"POST")})
            assert state["locked"]
            await trace("http11.send_request_body.complete",{})
            assert not state["locked"]
            return httpx.Response(200,json={"choices":[{"message":{"content":"synthetic proxy reply"}}]})
    monkeypatch.setattr(httpx,"AsyncClient",lambda **kwargs:Client())
    s=Settings(root=tmp_path,llm_url="https://example.invalid",llm_key="synthetic-key",llm_model="synthetic-model")
    assert asyncio.run(complete(s,"synthetic-proxy","synthetic",before_send=authorize))=="synthetic proxy reply"
    assert state["releases"]==1
