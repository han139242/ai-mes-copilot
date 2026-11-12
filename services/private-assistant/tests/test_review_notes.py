import os
import uuid
from dataclasses import replace
import pytest
from assistant.review_notes import ReviewNotes, source_status
from assistant.config import Settings
from assistant.database import Databases
from assistant.knowledge import Knowledge
from psycopg.types.json import Jsonb


def test_source_status_requires_the_current_published_hash_and_version():
    source={'documentId':'a','version':1,'hash':'h'}
    doc={'id':'a','version':1,'vector_version':1,'hash':'h','active_hash':'h','deleted':False}
    assert source_status([source],[doc])['fresh']
    for changed in ({'version':2},{'vector_version':0},{'hash':'other'},{'active_hash':'other'},{'deleted':True}):
        assert not source_status([source],[{**doc,**changed}])['fresh']
    assert not source_status([source],[])['fresh']
    assert not source_status([],[])['fresh']


@pytest.fixture
def private_sheet():
    if os.environ.get('PRIVATE_INTEGRATION')!='1':pytest.skip('requires dedicated private PostgreSQL')
    settings=replace(Settings(),llm_url='',llm_key='',llm_model='')
    dbs=Databases(settings);uid=600_000_000_000+int(uuid.uuid4().hex[:8],16);dbs.ensure(uid)
    kb=Knowledge(settings,dbs)
    doc=kb.upload(uid,'synthetic.md',b'L100 DRV-07')
    with dbs.locked(uid) as db:
        db.execute('UPDATE documents SET vector_version=version,vector_state=\'ready\',active_hash=hash WHERE id=%s',(doc['id'],))
        item=db.execute('SELECT * FROM documents WHERE id=%s',(doc['id'],)).fetchone()
        source={'id':'S1','documentId':item['id'],'version':1,'hash':item['hash'],
                'name':item['name'],'locator':'行 1','text':'L100 DRV-07'}
        history=db.execute('INSERT INTO history(question,response,revision) VALUES(%s,%s,0) RETURNING id',
            ('current board?',Jsonb({'answer':'DRV-07 [S1]','citations':[source]}))).fetchone()['id']
    return dbs,uid,kb,doc,history,ReviewNotes(dbs)


def test_sheet_uses_server_history_and_survives_history_clear(private_sheet):
    dbs,uid,kb,doc,history,notes=private_sheet
    saved=notes.add(uid,{'historyId':history,'answer':'forged','citations':[],'uid':1})
    assert saved['answer']=='DRV-07 [S1]' and saved['fresh']
    assert notes.add(uid,{'historyId':history})['id']==saved['id']
    assert len(notes.listing(uid))==1
    with dbs.connect(uid) as db:db.execute('DELETE FROM history')
    assert ReviewNotes(dbs).listing(uid)[0]['answer']=='DRV-07 [S1]'


def test_sheet_isolated_from_another_private_database(private_sheet):
    dbs,uid,kb,doc,history,notes=private_sheet
    saved=notes.add(uid,{'historyId':history})
    other=uid+10_000_000_000;dbs.ensure(other)
    assert notes.listing(other)==[]
    with pytest.raises(ValueError):notes.add(other,{'historyId':history})
    with pytest.raises(ValueError):notes.delete(other,{'id':saved['id'],'version':1})
    assert len(notes.listing(uid))==1


def test_source_updates_and_deletes_mark_saved_sheet_stale(private_sheet):
    dbs,uid,kb,doc,history,notes=private_sheet
    notes.add(uid,{'historyId':history})
    kb.upload(uid,'synthetic.md',b'L100 DRV-08',doc['id'])
    assert not notes.listing(uid)[0]['fresh']
    kb.delete(uid,doc['id'])
    result=notes.listing(uid)[0]
    assert not result['fresh'] and '删除' in result['reason']


def test_sheet_manual_checklist_uses_optimistic_version_and_never_changes_sources(private_sheet):
    dbs,uid,kb,doc,history,notes=private_sheet
    saved=notes.add(uid,{'historyId':history})
    edit={**saved,'title':'shift handover','comment':'manual observation only',
          'tasks':[{'text':'check source','done':True}], 'answer':'forged','citations':[]}
    updated=notes.update(uid,edit)
    assert updated['version']==2 and updated['tasks'][0]['done']
    assert updated['answer']==saved['answer'] and updated['citations']==saved['citations']
    with pytest.raises(ValueError,match='别处更新'):notes.update(uid,edit)
    with pytest.raises(ValueError):notes.delete(uid,{'id':saved['id'],'version':1})
    notes.delete(uid,{'id':saved['id'],'version':2})
    assert notes.listing(uid)==[]


@pytest.mark.parametrize('bad',[{'tasks':[{'text':'','done':True}]},{'tasks':[{'text':'x','done':'yes'}]},
                               {'title':''},{'comment':'x'*2001},{'version':True}])
def test_sheet_rejects_invalid_edits(private_sheet,bad):
    *_,history,notes=private_sheet
    saved=notes.add(private_sheet[1],{'historyId':history})
    with pytest.raises(ValueError):notes.update(private_sheet[1],{**saved,**bad})


def test_unverified_history_cannot_be_saved(private_sheet):
    dbs,uid,kb,doc,history,notes=private_sheet
    for response,valid in (({'answer':'unsupported','citations':[]},True),({'answer':'old','citations':[]},False)):
        with dbs.connect(uid) as db:
            identity=db.execute('INSERT INTO history(question,response,valid) VALUES(%s,%s,%s) RETURNING id',
                               ('test',Jsonb(response),valid)).fetchone()['id']
        with pytest.raises(ValueError):notes.add(uid,{'historyId':identity})
