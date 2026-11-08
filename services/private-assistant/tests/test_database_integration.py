"""Real extensions and role isolation. Run explicitly against the dedicated stage2 server."""
import os
import uuid
import pytest
import psycopg
from assistant.config import Settings
from assistant.database import Databases
from assistant.knowledge import Knowledge

pytestmark = pytest.mark.skipif(os.environ.get("PRIVATE_INTEGRATION") != "1", reason="requires dedicated private PostgreSQL")


def test_physical_database_connection_isolation():
    dbs = Databases(Settings()); dbs.ensure(990001); dbs.ensure(990002)
    with dbs.connect(990001) as connection:
        assert connection.execute("SELECT current_database() AS name").fetchone()["name"] == "mes_kb_u_990001"
        assert {r["extname"] for r in connection.execute("SELECT extname FROM pg_extension")} >= {"vector", "age"}
        privileges = connection.execute(
            "SELECT rolsuper,rolcreatedb,rolcreaterole FROM pg_roles WHERE rolname=current_user").fetchone()
        assert not any(privileges.values())
    _, role, password = dbs.identity(990001)
    with pytest.raises(psycopg.OperationalError, match="permission denied"):
        psycopg.connect(host=dbs.s.pg_host, port=dbs.s.pg_port, user=role, password=password,
                         dbname=dbs.identity(990002)[0], connect_timeout=5)


def test_update_delete_revoke_and_versioned_download(monkeypatch):
    uid = 1_000_000_000+int(uuid.uuid4().hex[:6],16)
    s=Settings(); dbs=Databases(s); dbs.ensure(uid); kb=Knowledge(s, dbs)
    # This test proves lifecycle transactions, not retrieval/model quality.
    monkeypatch.setattr(kb, "worker", lambda *args, **kwargs: {"indexed": 1})
    first=kb.upload(uid, "标准.txt", "L100 电压 12 V".encode())
    kb.rebuild(uid, first["jobId"])
    assert not kb.listing(uid)["blocked"]
    update=kb.upload(uid, "标准.txt", "L100 电压 24 V".encode(), first["id"])
    assert update["version"] == 2 and kb.listing(uid)["blocked"]
    assert kb.download(uid, first["id"], 1)
    kb.rebuild(uid, update["jobId"])
    deletion=kb.delete(uid, first["id"])
    assert deletion["revoked"] and kb.listing(uid)["blocked"]
    with pytest.raises(ValueError): kb.download(uid, first["id"])
    kb.rebuild(uid, deletion["jobId"])
    assert not kb.listing(uid)["documents"]


def test_real_vector_storage_and_distance_with_an_ordinary_role():
    # A database-engine probe, not evidence of semantic retrieval/model accuracy.
    import json
    dbs = Databases(Settings())
    dbs.ensure(990004)
    first = json.dumps([1.0] + [0.0] * 511)
    second = json.dumps([0.0, 1.0] + [0.0] * 510)
    with dbs.connect(990004) as connection:
        try:
            connection.execute("CREATE TABLE stage2_vector_probe(label TEXT, embedding vector(512))")
            connection.execute("INSERT INTO stage2_vector_probe VALUES ('first',%s::vector),('second',%s::vector)",
                               (first, second))
            rows = connection.execute(
                "SELECT label,embedding <=> %s::vector AS distance "
                "FROM stage2_vector_probe ORDER BY distance", (first,)).fetchall()
            assert [row["label"] for row in rows] == ["first", "second"]
            assert rows[0]["distance"] == pytest.approx(0)
            assert rows[1]["distance"] == pytest.approx(1)
        finally:
            connection.rollback()


def test_real_graph_path_and_sources_with_an_ordinary_role():
    import json
    dbs = Databases(Settings())
    dbs.ensure(990004)
    with dbs.connect(990004) as connection:
        try:
            connection.execute('SET search_path=ag_catalog,"$user",public')
            connection.execute("SELECT create_graph('stage2_graph_probe')")
            connection.execute("""SELECT * FROM cypher('stage2_graph_probe', $$
                CREATE (:Product {code:'L100'})-[:USES {source:'synthetic-a'}]->
                  (:Material {code:'DRV-01'})-[:REQUIRES {source:'synthetic-b'}]->
                  (:Step {name:'dimming-test'})
                $$) AS (result agtype)""")
            rows = connection.execute("""SELECT * FROM cypher('stage2_graph_probe', $$
                MATCH (p:Product)-[r:USES]->(m:Material)-[s:REQUIRES]->(t:Step)
                RETURN p.code,m.code,t.name,r.source,s.source
                $$) AS (product agtype,material agtype,step agtype,source_a agtype,source_b agtype)""").fetchall()
            assert len(rows) == 1
            assert {key: json.loads(value) for key, value in rows[0].items()} == {
                "product": "L100", "material": "DRV-01", "step": "dimming-test",
                "source_a": "synthetic-a", "source_b": "synthetic-b"}
        finally:
            connection.rollback()
