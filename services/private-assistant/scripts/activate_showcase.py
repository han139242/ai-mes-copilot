"""Explicitly activate a verified synthetic bundle in an empty MES demo account.

Requires --allow-model; zero/default budget means no local call limit. Never changes the
system's unlimited policy, resets accounting, edits a face, or replaces files.
The current user's original documents must be empty or exactly this same bundle.
"""
import argparse
from dataclasses import replace
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import uuid

import httpx
from assistant.config import Settings
from assistant.database import Databases
from assistant.face import Faces
from assistant.knowledge import Knowledge
from assistant.security import Journal
from assistant.job_policy import save_policy, assert_sources


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundle',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--username',choices=['lamp_quality'],default='lamp_quality')
    parser.add_argument('--allow-model',action='store_true')
    parser.add_argument('--max-new-calls',type=int,default=0)
    args=parser.parse_args()
    if not args.allow_model or args.max_new_calls<0:
        parser.error('Explicit --allow-model is required; --max-new-calls accepts zero (unlimited) or a positive limit')
    password=os.environ.get('MES_GATEWAY_TEST_PASSWORD')
    if not password:parser.error('Set the local demonstration account password in MES_GATEWAY_TEST_PASSWORD')
    manifest=json.loads((args.bundle/'manifest.json').read_text(encoding='utf-8'))
    if manifest.get('synthetic') is not True or len(manifest['files'])!=10:
        parser.error('Only the verified ten-document synthetic bundle is supported')
    files=[]
    for entry in manifest['files']:
        name=entry['name']
        if Path(name).name!=name or Path(name).suffix not in {'.md','.txt','.docx','.pdf','.xlsx'}:
            parser.error('Invalid synthetic filename')
        path=args.bundle/'上传这10份'/name
        content=path.read_bytes()
        if hashlib.sha256(content).hexdigest()!=entry['sha256']:parser.error('Bundle hash mismatch')
        files.append((name,content,entry['sha256']))
    args.output.mkdir(parents=True,exist_ok=True)
    response=httpx.post('http://127.0.0.1:8089/api/auth/login',json={'username':args.username,'password':password},timeout=30)
    response.raise_for_status(); login=response.json()
    # MES supplies the trusted numeric identity; neither a guessed UID nor a UI preference does.
    token=login.get('token')
    if not isinstance(token,str):raise RuntimeError('Demo authentication failed; no data changed')
    response=httpx.get('http://127.0.0.1:8089/api/auth/info',headers={'Authorization':'Bearer '+token},timeout=30)
    response.raise_for_status()
    user=response.json().get('data') or {}
    uid=user.get('userId')
    if type(uid) is not int or uid<=1:
        raise RuntimeError('Expected the authenticated non-admin demo account identity')
    response=httpx.post('http://127.0.0.1:8089/api/ai/private/dispatch',
        headers={'Authorization':'Bearer '+token},json={'action':'documents.list','payload':{}},timeout=40)
    response.raise_for_status()
    if not response.json().get('data'):
        raise RuntimeError('The demo account cannot access private knowledge through MES')
    settings=Settings();settings.validate()
    if not all((settings.llm_url,settings.llm_key,settings.llm_model)):
        raise RuntimeError('Model configuration is unavailable; no data changed')
    journal=Journal(settings.root/'requests.sqlite3');before=journal.status(0,include_calls=False)['used']
    cap=before+args.max_new_calls if args.max_new_calls else 0
    if settings.llm_limit>0: cap=min(cap,settings.llm_limit) if cap else settings.llm_limit
    # This bound is inherited by SDK subprocesses, and applies only to this process tree.
    os.environ['PRIVATE_LLM_LIMIT']=str(cap)
    settings=replace(settings,llm_limit=cap)
    dbs=Databases(settings);dbs.ensure(uid);kb=Knowledge(settings,dbs)
    owner_face=Faces(settings,dbs).status(1)
    listing=kb.listing(uid)
    expected={name:digest for name,_,digest in files}
    if any(doc['name'] not in expected or doc['hash']!=expected[doc['name']] for doc in listing['documents']):
        raise RuntimeError('This demo account already has other documents; nothing was replaced')
    original_prefs=listing['preferences']
    results=[]
    with dbs.index_lease(uid) as acquired:
        if not acquired:raise RuntimeError('Another index task is active; retry later')
        existing={d['name'] for d in listing['documents']}
        for name,content,_ in files:
            if name not in existing:
                kb.upload(uid,name,content,validation_policy={'sources':expected,'ceiling':cap})
        # Queue and durable grant are established before the database transaction
        # commits. After a crash, a background worker inherits this exact ceiling.
        with dbs.locked(uid) as db:
            docs=db.execute('SELECT name,hash FROM documents WHERE NOT deleted').fetchall()
            assert_sources({'sources':expected},docs)
            current_state=db.execute('SELECT * FROM private_state WHERE id=1').fetchone()
            if not kb.graph_ready(current_state):
                db.execute('UPDATE private_state SET model_consent=true,graph_enabled=true WHERE id=1')
                job=kb.queue(db)
                # The last upload already bound this job; an idempotent rerun
                # without uploads can instead queue a new graph attempt.
                from assistant.job_policy import load_policy
                grant=load_policy(settings,uid,job)
                if grant is None:save_policy(settings,uid,job,expected,cap)
                elif grant['sources']!=expected or grant['ceiling']!=cap:
                    raise RuntimeError('Existing validation task has a different source or budget grant; inspect it before retrying')
            else:job=None
        if job:kb._rebuild(uid,job,'g'+uuid.uuid4().hex[:20])
        current=kb.listing(uid)
        if not current['graphReady']:
            raise RuntimeError('Graph did not publish; inspect task status. Existing documents remain available locally.')
        plan=[
            ('cross','L100 的驱动板、装配工序和检验规范怎样关联？','fusion',1),
            ('basic','L100 当前生效的驱动板是什么？','rag',1),
            ('change','ECO-L100-02 批准了吗？现在可以把 L100 换成 DRV-08 吗？','rag',1),
            ('missing','L100 的色温误差合格限值是多少？','rag',1),
        ]
        skipped=[]
        for tag,question,mode,reserve in plan:
            used=journal.status(0,include_calls=False)['used']
            if cap and cap-used<reserve:
                skipped.append(tag);continue
            answer=kb.ask(uid,question,mode,session='showcase-live-'+tag)
            results.append({'id':tag,'question':question,**answer})
        graph=kb.graph(uid)
    after=journal.status(0,include_calls=False)['used']
    report={'username':args.username,'uid':uid,'files':len(current['documents']),
        'graphReady':current['graphReady'],'nodes':len(graph.get('nodes',[])),
        'edges':len(graph.get('edges',[])),'newModelCalls':after-before,'maxNewCalls':args.max_new_calls,
        'originalPreferences':original_prefs,'ownerFaceUnchanged':Faces(settings,dbs).status(1)==owner_face,
        'answers':results,'skippedOptionalQuestions':skipped}
    assert (not args.max_new_calls or after-before<=args.max_new_calls) and report['ownerFaceUnchanged']
    (args.output/'live-results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2,default=str),encoding='utf-8')
    print(json.dumps({k:v for k,v in report.items() if k not in {'answers','originalPreferences'}}))
    if not {'basic','cross'}<={r['id'] for r in results if r.get('generated')}:
        raise SystemExit('Index complete; the authorized budget did not cover both required generated-answer checks')


if __name__=='__main__':main()
