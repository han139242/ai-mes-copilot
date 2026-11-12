from contextlib import contextmanager
from types import SimpleNamespace

import pytest

from assistant.worker import authorize_model
from assistant.config import Settings


@pytest.mark.parametrize('job_generation,allowed', [('new-attempt', False), ('old-attempt', True)])
def test_old_worker_cannot_resume_model_requests_when_same_job_is_retried(monkeypatch, tmp_path, job_generation, allowed):
    class Database:
        def execute(self, sql, parameters=None):
            row = ({'state': 'running', 'generation': job_generation} if 'FROM jobs' in sql else
                   {'revision': 2, 'model_consent': True, 'graph_enabled': True})
            return SimpleNamespace(fetchone=lambda: row)
    class Databases:
        def __init__(self, settings): pass
        @contextmanager
        def locked(self, uid):
            assert uid == 123
            yield Database()
    monkeypatch.setattr('assistant.database.Databases', Databases)
    payload = {'uid': 123, 'revision': 2, 'generation': 'old-attempt', 'jobId': 'a'*32}
    settings=Settings(root=tmp_path,secret='s'*32)
    sent = []
    if allowed:
        with authorize_model(settings, payload): sent.append(True)
    else:
        with pytest.raises(RuntimeError, match='任务已取消'):
            with authorize_model(settings, payload): sent.append(True)
    assert bool(sent) is allowed
