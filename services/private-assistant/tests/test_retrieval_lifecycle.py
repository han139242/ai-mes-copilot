"""Real local embeddings and PG vectors. Cloud extraction/generation is never invoked here."""
import os
import uuid
from pathlib import Path
from contextlib import contextmanager

import pytest

from assistant.answers import business_intent, graph_citations, merge_citations, route
from assistant.config import Settings
from assistant.database import Databases
from assistant.knowledge import Knowledge
from assistant.vectors import pieces_to_chunks


def test_locator_survives_every_local_chunk():
    chunks = pieces_to_chunks([{"locator":"检验!A4:C4","text":"台灯装配与检验"*200}])
    assert len(chunks)>3
    assert all(c["locator"]=="检验!A4:C4" and len(c["text"])<=240 for c in chunks)


@pytest.mark.skipif(os.environ.get("PRIVATE_INTEGRATION") != "1", reason="requires dedicated PostgreSQL")
def test_exact_identifier_recovers_past_dense_limit_and_excludes_stale_chunks(monkeypatch):
    import numpy as np
    from assistant import vectors

    settings = Settings(llm_url="", llm_key="", llm_model="")
    dbs = Databases(settings)
    uid = 215_000_000_000 + int(uuid.uuid4().hex[:8], 16)
    dbs.ensure(uid)
    kb = Knowledge(settings, dbs)
    active = kb.upload(uid, "合成编号.txt", "DRV-07 精确目标".encode())
    stale = kb.upload(uid, "旧版合成编号.txt", "DRV-07 已撤销".encode())
    decoy = kb.upload(uid, "近似编号.txt", "DRV-070 干扰编号".encode())
    model = settings.embedding_model
    close = "[" + ",".join(["1", "0"] + ["0"] * (settings.embedding_dim - 2)) + "]"
    distant = "[" + ",".join(["0", "1"] + ["0"] * (settings.embedding_dim - 2)) + "]"
    monkeypatch.setattr(vectors, "embed", lambda *args, **kwargs: np.array([[1] + [0] * (settings.embedding_dim - 1)]))
    with dbs.connect(uid) as db:
        for item, texts, embedding, current in (
            (active, ["DRV-07 精确目标"], distant, True),
            (stale, ["DRV-07 已撤销"], close, False),
            (decoy, ["DRV-070 干扰编号"] * 41, close, True),
        ):
            doc = db.execute("SELECT hash,name FROM documents WHERE id=%s", (item["id"],)).fetchone()
            db.execute("UPDATE documents SET vector_version=1,active_hash=hash,active_name=name,deleted=%s WHERE id=%s",
                       (not current, item["id"]))
            for i, value in enumerate(texts):
                db.execute("""INSERT INTO knowledge_chunks
                    (id,document_id,document_version,document_hash,locator,content,embedding,model)
                    VALUES (%s,%s,1,%s,%s,%s,%s::vector,%s)""",
                    (f"{item['id']}-{i}", item["id"], doc["hash"], "行 1", value, embedding, model))
    result = kb.vectors.retrieve(uid, "DRV-07 是什么？", limit=1)
    assert [(row["documentId"], row.get("retrieval")) for row in result] == [(active["id"], "identifier")]


def test_explicit_mode_and_visible_fallback():
    assert route("为什么有影响","rag",True)[0]=="rag"
    assert route("驱动板换版影响哪道工序","auto",True)[0]=="fusion"
    actual,reason=route("跨文件关联","graph",False)
    assert actual=="rag" and "图谱尚未就绪" in reason


def test_live_business_handoff_preserves_document_help_and_explicit_read_only():
    assert business_intent("帮我创建一个 L100 生产订单")=="write"
    assert business_intent("只查不改，当前库存还有多少")=="read"
    assert business_intent("查询工单 WO-123 的状态")=="read"
    assert business_intent("资料中库存管理的定义是什么") is None
    assert business_intent("SOP 里如何查询设备预警") is None
    assert business_intent("不要执行，只解释创建工单需要哪些资料") is None
    assert business_intent("反查成品批次 C3-LP-20261030-001 用了哪些原料批") == "read"
    assert business_intent("文档中怎么查询成品批次 C3-LP-20261030-001 的来源") is None


def test_graph_reference_must_match_active_source_and_real_locator():
    identity="a"*32; digest="b"*64
    doc={"id":identity,"version":2,"hash":digest,"name":"标准.txt",
         "pieces":[{"locator":"行 4","text":"驱动板为 DRV-08"}]}
    chunk={"file_path":f"{identity}:v2:{digest}:标准.txt","chunk_id":"real-chunk",
           "content":"【来源定位：行 4】\n驱动板为 DRV-08"}
    assert len(graph_citations({"chunks":[chunk]},[doc]))==1
    assert not graph_citations({"chunks":[{**chunk,"file_path":f"{identity}:v1:{digest}:标准.txt"}]},[doc])
    assert not graph_citations({"chunks":[{**chunk,"content":"【来源定位：行 99】\n不存在的定位"}]},[doc])
    assert not graph_citations({"chunks":[{**chunk,"content":"【来源定位：行 4】\n凭空改成 DRV-99"}]},[doc])
    assert len(merge_citations(graph_citations({"chunks":[chunk,chunk]},[doc])))==1


@pytest.mark.skipif(os.environ.get("PRIVATE_INTEGRATION")!="1",reason="requires dedicated stage2 PostgreSQL and local models")
def test_graph_failure_does_not_block_real_rag_and_update_delete_revokes_sources(monkeypatch):
    settings=Settings(llm_url="",llm_key="",llm_model="")
    dbs=Databases(settings)
    # A fresh reserved synthetic identity prevents modifying another test/user's documents.
    uid=10_000_000+int(uuid.uuid4().hex[:8],16)
    other=uid+5_000_000_000
    dbs.ensure(uid); dbs.ensure(other)
    kb=Knowledge(settings,dbs)
    kb.preferences(uid,{"modelConsent":True,"graphEnabled":True})
    def unavailable(*args,**kwargs):
        raise RuntimeError("synthetic graph outage")
    monkeypatch.setattr(kb,"worker",unavailable)
    first=kb.upload(uid,"合成台灯说明.txt","合成教学样例：L100 台灯使用 DRV-07 驱动板，装配后执行调光稳定性检查。".encode())
    kb.rebuild(uid,first["jobId"])
    listing=kb.listing(uid)
    assert listing["ragReady"] and not listing["graphReady"]
    assert listing["jobs"][0]["state"]=="partial"
    result=kb.ask(uid,"L100 使用什么驱动板","fusion")
    assert result["mode"]=="rag" and not result["generated"]
    assert any("DRV-07" in c["text"] and c["version"]==1 for c in result["citations"])
    assert not kb.ask(other,"L100 使用什么驱动板","rag")["citations"]
    with pytest.raises(ValueError): kb.preview(other,first["id"])
    with pytest.raises(ValueError): kb.download(other,first["id"])

    update=kb.upload(uid,"合成台灯说明.txt","合成教学样例：L100 台灯改用 DRV-08 驱动板，换版后进行调光稳定性检查。".encode(),first["id"])
    pending=kb.ask(uid,"L100 使用什么驱动板","rag")
    assert pending["citations"] and all(c["version"]==1 for c in pending["citations"])
    assert "沿用已发布的旧版本" in pending["routeReason"]
    with monkeypatch.context() as outage:
        outage.setattr(kb.vectors,"index",unavailable)
        kb.rebuild(uid,update["jobId"])
    retained=kb.ask(uid,"L100 使用什么驱动板","rag")
    assert retained["citations"] and all(c["version"]==1 for c in retained["citations"])
    assert kb.listing(uid)["jobs"][0]["state"]=="partial"
    kb.retry(uid,update["jobId"])
    kb.rebuild(uid,update["jobId"])
    new=kb.ask(uid,"L100 使用什么驱动板","rag")
    assert new["citations"] and all(c["version"]==2 and "DRV-07" not in c["text"] for c in new["citations"])
    with dbs.connect(uid) as db:
        assert db.execute("SELECT count(*) AS n FROM history WHERE NOT valid").fetchone()["n"]>=1
    assert kb.preview(uid,first["id"],1)["active"] is False
    deletion=kb.delete(uid,first["id"])
    assert deletion["revoked"]
    assert not kb.ask(uid,"L100 使用什么驱动板","rag")["citations"]
    with dbs.connect(uid) as db:
        assert db.execute("SELECT count(*) AS n FROM knowledge_chunks").fetchone()["n"]==0
    with pytest.raises(ValueError): kb.download(uid,first["id"])
    assert not kb.ask(uid,"L900 这个不存在的型号用什么驱动板","rag")["citations"]


@pytest.mark.skipif(os.environ.get("PRIVATE_INTEGRATION")!="1",reason="requires dedicated stage2 PostgreSQL")
def test_missing_original_can_be_restored_and_corrupt_original_is_preserved():
    settings=Settings(llm_url="",llm_key="",llm_model="")
    dbs=Databases(settings)
    uid=10_000_000+int(uuid.uuid4().hex[:8],16)
    dbs.ensure(uid); kb=Knowledge(settings,dbs)
    content="合成资料：L200 驱动板为 DRV-09。".encode()
    first=kb.upload(uid,"合成恢复.txt",content)
    with dbs.connect(uid) as db:
        original=Path(db.execute("SELECT path FROM documents WHERE id=%s",(first["id"],)).fetchone()["path"])
    original.unlink()  # This test owns the generated original and deliberately simulates local loss.
    with pytest.raises(ValueError,match="丢失"): kb.download(uid,first["id"])
    restored=kb.upload(uid,"合成恢复.txt",content,first["id"])
    assert restored["restored"] and restored["version"]==1 and original.read_bytes()==content
    original.write_bytes(b"synthetic local corruption")
    with pytest.raises(ValueError,match="校验失败"): kb.preview(uid,first["id"])
    changed=kb.upload(uid,"合成恢复.txt",content,first["id"])
    assert changed["version"]==2 and original.read_bytes()==b"synthetic local corruption"
    assert kb.preview(uid,first["id"])["publication"]=="pending"


@pytest.mark.skipif(os.environ.get("PRIVATE_INTEGRATION")!="1",reason="requires dedicated stage2 PostgreSQL and local models")
def test_task_lock_contention_is_retryable_instead_of_stuck_running(monkeypatch):
    settings=Settings(llm_url="",llm_key="",llm_model="")
    dbs=Databases(settings); uid=10_000_000+int(uuid.uuid4().hex[:8],16)
    dbs.ensure(uid); kb=Knowledge(settings,dbs)
    first=kb.upload(uid,"合成锁竞争.txt","合成台灯 L100 使用 DRV-07。".encode())
    kb.rebuild(uid,first["jobId"])
    with dbs.locked(uid) as db: job=kb.queue(db)
    acquire=dbs.locked; counter={"n":0}
    @contextmanager
    def competing_lock(user):
        counter["n"]+=1
        if counter["n"]==2: raise ValueError("合成锁竞争")
        with acquire(user) as db: yield db
    with monkeypatch.context() as patch:
        patch.setattr(dbs,"locked",competing_lock)
        kb.rebuild(uid,job)
    with dbs.connect(uid) as db:
        assert db.execute("SELECT state FROM jobs WHERE id=%s",(job,)).fetchone()["state"]=="failed"
    kb.retry(uid,job); kb.rebuild(uid,job)
    assert kb.listing(uid)["ragReady"]
