"""Verify a fresh private synthetic corpus with real local embeddings and no cloud calls."""
import argparse
import base64
from dataclasses import replace
import hashlib
import json
from pathlib import Path
import time
import uuid

from assistant.config import Settings
from assistant.database import Databases
from assistant.knowledge import Knowledge
from assistant.security import Journal


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundle',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    args.output.mkdir(parents=True,exist_ok=True)
    manifest=json.loads((args.bundle/'manifest.json').read_text(encoding='utf-8'))
    assert manifest.get('synthetic') is True
    assert all(Path(entry['name']).name==entry['name'] for entry in manifest['files'])
    paths=[args.bundle/'上传这10份'/entry['name'] for entry in manifest['files']]
    assert len(paths)==10 and {p.suffix for p in paths}=={'.md','.txt','.docx','.pdf','.xlsx'}
    for p,entry in zip(paths,manifest['files']): assert hashlib.sha256(p.read_bytes()).hexdigest()==entry['sha256']
    settings=replace(Settings(),llm_url='',llm_key='',llm_model='');settings.validate()
    dbs=Databases(settings);kb=Knowledge(settings,dbs)
    uid=200_000_000_000+int(uuid.uuid4().hex[:8],16);other=uid+5_000_000_000
    for user in (uid,other):dbs.ensure(user)
    calls_before=Journal(settings.root/'requests.sqlite3').status(0,include_calls=False)['used']
    entries=[kb.upload(uid,p.name,p.read_bytes()) for p in paths]
    kb.rebuild(uid,entries[-1]['jobId'])
    listing=kb.listing(uid)
    assert listing['ragReady'] and all(d['vector_state']=='ready' for d in listing['documents'])
    for p,entry in zip(paths,entries): assert base64.b64decode(kb.download(uid,entry['id'])['content'])==p.read_bytes()
    questions=Path(__file__).resolve().parents[1]/'examples/lamp-showcase/questions.json'
    results=[];started=time.monotonic()
    for case in json.loads(questions.read_text(encoding='utf-8')):
        answer=kb.ask(uid,case['question'],'rag',session='showcase-'+case['id'])
        citations=answer['citations']; text='\n'.join(c['text'] for c in citations)
        checks={'noCloudGeneration':not answer['generated']}
        if case.get('expect')=='business': checks['businessHandoff']=bool(answer.get('businessHandoff'))
        elif case.get('expect')=='absent':checks['noFalseEvidence']=not citations
        else:
            checks['documentRoute']=not answer.get('businessHandoff',False)
            checks['facts']=all(token in text for token in case.get('tokens',[]))
            checks['sources']=all(any(c['name'].startswith(prefix) for c in citations) for prefix in case.get('sources',[]))
            checks['sourceDiversity']=len({c['documentId'] for c in citations})>=case.get('minSources',1)
            checks['originalsVerified']=all(any(piece['locator']==c['locator'] and c['text'] in piece['text']
                for piece in kb.preview(uid,c['documentId'],c['version'],c['locator'])['pieces']) for c in citations)
        results.append({'id':case['id'],'question':case['question'],'passed':all(checks.values()),
                        'checks':checks,'citations':citations,'answer':answer['answer']})
    isolated=kb.ask(other,'L100 当前生效的驱动板是什么？','rag')
    assert not isolated['citations']
    with dbs.connect(uid) as db: chunks=db.execute('SELECT count(*) AS n FROM knowledge_chunks').fetchone()['n']
    calls_after=Journal(settings.root/'requests.sqlite3').status(0,include_calls=False)['used']
    report={'synthetic':True,'uid':uid,'otherUid':other,'files':10,'chunks':chunks,
            'total':len(results),'passed':sum(c['passed'] for c in results),'cloudCallDelta':calls_after-calls_before,
            'crossUserIsolation':True,'originalRoundtrip':True,'seconds':round(time.monotonic()-started,2),'cases':results}
    (args.output/'results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in report.items() if k!='cases'},ensure_ascii=False))
    print(json.dumps({'failed':[{'id':c['id'],'checks':c['checks']} for c in results if not c['passed']]}))
    raise SystemExit(report['passed']!=report['total'] or report['cloudCallDelta']!=0)


if __name__=='__main__':main()
