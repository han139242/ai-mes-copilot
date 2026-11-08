"""Actual PostgreSQL copying, with synthetic cache rows; never invokes the model."""
import os
import uuid

import pytest

from assistant.config import Settings
from assistant.database import Databases
from assistant.knowledge import Knowledge
from assistant.ontology import graph_profile


@pytest.mark.skipif(os.environ.get("PRIVATE_INTEGRATION")!="1",reason="requires dedicated stage2 PostgreSQL")
def test_only_identical_sources_and_profile_can_reuse_extractions():
    s=Settings(llm_url="https://example.invalid",llm_key="synthetic-key",llm_model="synthetic-model")
    d=Databases(s); uid=10_000_000+int(uuid.uuid4().hex[:8],16); d.ensure(uid); kb=Knowledge(s,d)
    with d.connect(uid) as db:
        db.execute("""CREATE TABLE lightrag_llm_cache(workspace TEXT,id TEXT,original_prompt TEXT,return_value TEXT,
            chunk_id TEXT,cache_type TEXT,queryparam JSONB,create_time TIMESTAMP,update_time TIMESTAMP,PRIMARY KEY(workspace,id));
            CREATE TABLE lightrag_doc_chunks(id TEXT,workspace TEXT,full_doc_id TEXT,file_path TEXT,
                llm_cache_list JSONB DEFAULT '[]',PRIMARY KEY(workspace,id));""")
        db.execute("INSERT INTO graph_profiles(generation,signature) VALUES('gsource',%s)",(graph_profile(s),))
        db.execute("INSERT INTO lightrag_doc_chunks(id,workspace,full_doc_id,file_path) VALUES('chunk-1','gsource','doc1-v1','doc1:v1:hash1:name'),('chunk-2','gsource','doc2-v1','doc2:v1:oldhash:name')")
        db.execute("""INSERT INTO lightrag_llm_cache(workspace,id,original_prompt,return_value,chunk_id,cache_type) VALUES
            ('gsource','unchanged','synthetic prompt','synthetic extraction','chunk-1','extract'),
            ('gsource','outdated','synthetic prompt','old extraction','chunk-2','extract'),
            ('gsource','answer','synthetic prompt','old query answer','chunk-1','query')""")
        manifest=[{'key':'doc1-v1','source':'doc1:v1:hash1:name'},{'key':'doc2-v2','source':'doc2:v2:newhash:name'}]
        assert kb.reuse_extractions(db,'gsource','gtarget',manifest)==1
        assert [r['id'] for r in db.execute("SELECT id FROM lightrag_llm_cache WHERE workspace='gtarget'")]==['unchanged']
        assert kb.reuse_extractions(db,'gsource','gchanged',[{'key':'doc1-v1','source':'doc1:v1:changed:name'}])==0
        retained=kb.retain_extractions(db,'gsource',manifest)
        assert retained and retained.startswith('c')
        kb.purge_old(uid,db,None,retained)
        assert db.execute('SELECT count(*) AS n FROM lightrag_llm_cache').fetchone()['n']==1
        assert db.execute('SELECT count(*) AS n FROM lightrag_doc_chunks').fetchone()['n']==1
        assert kb.reuse_extractions(db,None,'gafterdelete',manifest)==1
        db.execute("UPDATE graph_profiles SET signature='different-model' WHERE generation=%s",(retained,))
        assert kb.reuse_extractions(db,None,'gnewmodel',manifest)==0
        kb.purge_old(uid,db,None)
        assert db.execute('SELECT count(*) AS n FROM lightrag_llm_cache').fetchone()['n']==0
        assert db.execute('SELECT count(*) AS n FROM graph_profiles').fetchone()['n']==0


@pytest.mark.skipif(os.environ.get("PRIVATE_INTEGRATION")!="1",reason="requires dedicated stage2 PostgreSQL")
def test_shared_extraction_remains_available_after_first_owning_document_is_deleted():
    s=Settings(llm_url="https://example.invalid",llm_key="synthetic-key",llm_model="synthetic-model")
    d=Databases(s);uid=10_000_000+int(uuid.uuid4().hex[:8],16);d.ensure(uid);kb=Knowledge(s,d)
    with d.connect(uid) as db:
        db.execute("""CREATE TABLE lightrag_llm_cache(workspace TEXT,id TEXT,original_prompt TEXT,return_value TEXT,
            chunk_id TEXT,cache_type TEXT,queryparam JSONB,create_time TIMESTAMP,update_time TIMESTAMP,PRIMARY KEY(workspace,id));
            CREATE TABLE lightrag_doc_chunks(id TEXT,workspace TEXT,full_doc_id TEXT,file_path TEXT,
                llm_cache_list JSONB DEFAULT '[]',PRIMARY KEY(workspace,id));""")
        db.execute("INSERT INTO graph_profiles(generation,signature) VALUES('gsource',%s)",(graph_profile(s),))
        db.execute("""INSERT INTO lightrag_doc_chunks VALUES
            ('chunk-a','gsource','doc-a-v1','doc-a:v1:hash-a:name','["default:extract:shared"]'),
            ('chunk-b','gsource','doc-b-v1','doc-b:v1:hash-b:name','["default:extract:shared"]'),
            ('chunk-c','gsource','doc-c-v1','doc-c:v1:hash-c:name','["default:extract:shared"]')""")
        db.execute("""INSERT INTO lightrag_llm_cache(workspace,id,original_prompt,return_value,chunk_id,cache_type)
            VALUES('gsource','default:extract:shared','same actual source text','same extraction','chunk-a','extract')""")
        # B and C survive A's deletion, even though the cache was originally assigned to A.
        manifest=[{'key':'doc-b-v1','source':'doc-b:v1:hash-b:name'},{'key':'doc-c-v1','source':'doc-c:v1:hash-c:name'}]
        retained=kb.retain_extractions(db,'gsource',manifest);assert retained
        kb.purge_old(uid,db,None,retained)
        rows=db.execute('SELECT chunk_id FROM lightrag_llm_cache').fetchall()
        assert [r['chunk_id'] for r in rows]==['chunk-b']
        assert {r['full_doc_id'] for r in db.execute('SELECT full_doc_id FROM lightrag_doc_chunks')}=={'doc-b-v1','doc-c-v1'}
        assert kb.reuse_extractions(db,None,'gtarget',manifest)==1
        # Before a rebuild, deleting B must still retain C's reference to that same cache key.
        last=[{'key':'doc-c-v1','source':'doc-c:v1:hash-c:name'}]
        retained_again=kb.retain_extractions(db,None,last);assert retained_again
        kb.purge_old(uid,db,None,retained_again)
        assert [r['chunk_id'] for r in db.execute('SELECT chunk_id FROM lightrag_llm_cache')]==['chunk-c']
        assert [r['full_doc_id'] for r in db.execute('SELECT full_doc_id FROM lightrag_doc_chunks')]==['doc-c-v1']
        assert kb.reuse_extractions(db,None,'gfinal',last)==1
