from dataclasses import replace
import json

import pytest
from assistant.config import Settings
from assistant.job_policy import save_policy,load_policy,assert_sources,bounded_settings


def test_grant_survives_restart_and_bounds_default_unlimited_worker(tmp_path):
    settings=Settings(root=tmp_path,secret='s'*32,llm_limit=0)
    job='a'*32;sources={'合成资料.md':'b'*64}
    save_policy(settings,3,job,sources,40)
    restored=load_policy(Settings(root=tmp_path,secret='s'*32),3,job)
    assert restored['sources']==sources
    assert bounded_settings(settings,restored).llm_limit==40
    assert bounded_settings(replace(settings,llm_limit=30),restored).llm_limit==30
    assert bounded_settings(settings,None).llm_limit==0


def test_explicit_unlimited_grant_preserves_a_separate_configured_positive_limit(tmp_path):
    settings=Settings(root=tmp_path,secret='s'*32,llm_limit=0)
    save_policy(settings,3,'d'*32,{'synthetic.md':'b'*64},0)
    grant=load_policy(settings,3,'d'*32)
    assert grant['ceiling']==0
    assert bounded_settings(settings,grant).llm_limit==0
    assert bounded_settings(replace(settings,llm_limit=50),grant).llm_limit==50


def test_concurrent_extra_or_changed_document_fails_closed(tmp_path):
    settings=Settings(root=tmp_path,secret='s'*32);job='a'*32
    save_policy(settings,3,job,{'合成资料.md':'b'*64},40)
    policy=load_policy(settings,3,job)
    docs=[{'name':'合成资料.md','hash':'b'*64}]
    assert_sources(policy,docs)
    for changed in (docs+[{'name':'私人文件.md','hash':'c'*64}],
                    [{'name':'合成资料.md','hash':'d'*64}],docs*2):
        with pytest.raises(RuntimeError,match='批准的合成文件'):assert_sources(policy,changed)


def test_tampering_cannot_remove_the_budget_or_change_authorized_sources(tmp_path):
    settings=Settings(root=tmp_path,secret='s'*32);job='a'*32
    save_policy(settings,3,job,{'合成资料.md':'b'*64},40)
    path=tmp_path/'users'/'3'/'job-policies'/(job+'.json')
    payload=json.loads(path.read_text(encoding='utf-8'));payload['payload']['ceiling']=999999
    path.write_text(json.dumps(payload),encoding='utf-8')
    with pytest.raises(RuntimeError,match='授权校验失败'):load_policy(settings,3,job)
    assert load_policy(settings,3,'c'*32) is None


@pytest.mark.skipif(__import__('os').environ.get('PRIVATE_INTEGRATION')!='1',reason='requires dedicated private PostgreSQL')
def test_database_snapshot_rejects_a_file_added_after_initial_approval(monkeypatch):
    import uuid
    import hashlib
    from assistant.database import Databases
    from assistant.knowledge import Knowledge
    settings=replace(Settings(),llm_url='',llm_key='',llm_model='')
    dbs=Databases(settings);uid=400_000_000_000+int(uuid.uuid4().hex[:8],16);dbs.ensure(uid)
    kb=Knowledge(settings,dbs)
    content=b'L100 DRV-07';kb.upload(uid,'approved.md',content)
    late=kb.upload(uid,'unapproved.md',b'private-only synthetic fixture')
    save_policy(settings,uid,late['jobId'],{'approved.md':hashlib.sha256(content).hexdigest()},40)
    called=[];monkeypatch.setattr(kb,'worker',lambda *a,**k:called.append(True))
    kb.rebuild(uid,late['jobId'])
    state=kb.listing(uid)
    assert next(j for j in state['jobs'] if j['id']==late['jobId'])['state']=='failed'
    assert not state['ragReady'] and not called


@pytest.mark.skipif(__import__('os').environ.get('PRIVATE_INTEGRATION')!='1',reason='requires dedicated private PostgreSQL')
def test_interrupted_import_cannot_leave_an_unbounded_cloud_job(monkeypatch):
    import uuid
    import hashlib
    from assistant.database import Databases
    from assistant.knowledge import Knowledge
    settings=replace(Settings(),llm_url='',llm_key='',llm_model='')
    dbs=Databases(settings);uid=500_000_000_000+int(uuid.uuid4().hex[:8],16);dbs.ensure(uid)
    kb=Knowledge(settings,dbs)
    with dbs.locked(uid) as db:
        db.execute('UPDATE private_state SET model_consent=true,graph_enabled=true WHERE id=1')
    first=b'L100 DRV-07';second=b'future synthetic document'
    grant={'sources':{'first.md':hashlib.sha256(first).hexdigest(),
                      'second.md':hashlib.sha256(second).hexdigest()},'ceiling':40}
    job=kb.upload(uid,'first.md',first,validation_policy=grant)['jobId']
    # The next file fails before completing the batch, with consent already on.
    with pytest.raises(ValueError):kb.upload(uid,'second.md',b'\xff',validation_policy=grant)
    policy=load_policy(settings,uid,job)
    assert policy['sources']==grant['sources'] and policy['ceiling']==40
    called=[];monkeypatch.setattr(kb,'worker',lambda *a,**k:called.append(True))
    kb.rebuild(uid,job)
    state=kb.listing(uid)
    assert next(j for j in state['jobs'] if j['id']==job)['state']=='failed'
    assert not state['ragReady'] and not called
