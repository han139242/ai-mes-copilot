from fastapi import FastAPI, Request
from fastapi.testclient import TestClient

from assistant.config import Settings
from assistant.model_proxy import gateway_model_token, proxy


def test_loopback_model_adapter_requires_separate_token_and_only_passes_text(tmp_path,monkeypatch):
    s=Settings(root=tmp_path,secret="synthetic-gateway-secret-value-0001")
    app=FastAPI()
    calls=[]
    async def fake_complete(settings,purpose,messages):
        calls.append((purpose,messages))
        return "合成回答"
    monkeypatch.setattr("assistant.model_proxy.complete_messages",fake_complete)
    @app.post("/model")
    async def endpoint(request:Request): return await proxy(request,s)
    client=TestClient(app)
    body={"messages":[{"role":"user","content":"合成问题"}]}
    assert client.post("/model",json=body).status_code==401
    headers={"Authorization":"Bearer "+gateway_model_token(s)}
    assert client.post("/model",headers=headers,json={"messages":[{"role":"tool","content":"unexpected"}]}).status_code==400
    assert not calls
    reply=client.post("/model",headers=headers,json=body)
    assert reply.status_code==200 and reply.json()["choices"][0]["message"]["content"]=="合成回答"
    assert calls==[("mes-business-model",body["messages"])]
