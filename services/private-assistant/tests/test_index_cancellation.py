"""Real PG publication/ownership races, with synthetic local embeddings and no API."""
import os
import uuid

import numpy as np
import pytest

from assistant.config import Settings
from assistant.database import Databases
from assistant.index_queue import IndexQueue
from assistant.knowledge import Knowledge

pytestmark = pytest.mark.skipif(os.environ.get('PRIVATE_INTEGRATION') != '1',
                               reason='requires dedicated stage2 PostgreSQL')


@pytest.mark.parametrize('retry_immediately', [False, True])
def test_cancel_during_embedding_cannot_publish_even_after_immediate_retry(monkeypatch, retry_immediately):
    settings = Settings(llm_url='', llm_key='', llm_model='')
    dbs = Databases(settings)
    uid = 8_000_000_000 + int(uuid.uuid4().hex[:8], 16)
    dbs.ensure(uid)
    kb = Knowledge(settings, dbs)
    monkeypatch.setattr('assistant.vectors.embed',
                        lambda s, texts, **kwargs: np.ones((len(texts), 512), dtype=np.float32))
    first = kb.upload(uid, '合成取消测试.md', b'L100 DRV-07')
    kb.rebuild(uid, first['jobId'])
    update = kb.upload(uid, '合成取消测试.md', b'L100 DRV-08', first['id'])
    def cancelling_embed(s, texts, **kwargs):
        kb.cancel(uid, update['jobId'])
        if retry_immediately:
            kb.retry(uid, update['jobId'])
            kb.rebuild(uid, update['jobId'])  # Another attempt cannot steal the active lease.
        return np.ones((len(texts), 512), dtype=np.float32)
    monkeypatch.setattr('assistant.vectors.embed', cancelling_embed)
    kb.rebuild(uid, update['jobId'])
    with dbs.connect(uid) as db:
        doc = db.execute('SELECT vector_version,active_hash FROM documents WHERE id=%s', (first['id'],)).fetchone()
        job = db.execute('SELECT state,attempts FROM jobs WHERE id=%s', (update['jobId'],)).fetchone()
        chunks = db.execute('SELECT DISTINCT document_version FROM knowledge_chunks').fetchall()
    assert doc['vector_version'] == 1
    assert chunks == [{'document_version': 1}]
    assert job == {'state': 'queued' if retry_immediately else 'cancelled', 'attempts': 1}
    if retry_immediately:
        monkeypatch.setattr('assistant.vectors.embed',
                            lambda s, texts, **kwargs: np.ones((len(texts), 512), dtype=np.float32))
        kb.rebuild(uid, update['jobId'])
        assert kb.listing(uid)['documents'][0]['vector_version'] == 2


def test_recovery_preserves_live_lease_and_recent_work_but_recovers_abandoned_work():
    settings = Settings(llm_url='', llm_key='', llm_model='')
    dbs = Databases(settings)
    uid = 8_000_000_000 + int(uuid.uuid4().hex[:8], 16)
    dbs.ensure(uid)
    kb = Knowledge(settings, dbs)
    queue = IndexQueue(dbs, kb)
    job = kb.upload(uid, '合成任务恢复.md', b'L100 DRV-07')['jobId']
    with dbs.connect(uid) as db:
        db.execute("UPDATE jobs SET state='running',started=now() WHERE id=%s", (job,))
    with dbs.index_lease(uid) as acquired:
        assert acquired
        assert queue.collect(uid) == []
        with dbs.connect(uid) as db:
            assert db.execute('SELECT state FROM jobs WHERE id=%s', (job,)).fetchone()['state'] == 'running'
    queue.collect(uid)
    with dbs.connect(uid) as db:
        assert db.execute('SELECT state FROM jobs WHERE id=%s', (job,)).fetchone()['state'] == 'running'
        db.execute("UPDATE jobs SET started=now()-interval '1 minute' WHERE id=%s", (job,))
    IndexQueue(dbs, kb).collect(uid)
    with dbs.connect(uid) as db:
        assert db.execute('SELECT state FROM jobs WHERE id=%s', (job,)).fetchone()['state'] == 'failed'
