import json
import pytest
from assistant.chunking import located_chunks
from assistant.answers import graph_citations


class CharacterTokenizer:
    def encode(self,text):return list(text)
    def decode(self,tokens):return ''.join(tokens)


def test_adjacent_short_pieces_share_extraction_without_losing_real_locators():
    pieces=[{'locator':f'段落 {n}','text':f'L100 第{n}项教学事实。'} for n in range(1,7)]
    chunks=located_chunks(CharacterTokenizer(),json.dumps(pieces),chunk_token_size=240)
    assert len(chunks)==1
    assert all(f'【来源定位：{piece["locator"]}】\n{piece["text"]}' in chunks[0]['content'] for piece in pieces)


def test_packed_graph_citations_are_split_and_validated_against_each_original_piece():
    doc={'id':'a'*32,'version':1,'hash':'b'*64,'name':'装配.docx','pieces':[
        {'locator':'段落 2','text':'L100 使用 DRV-07。'}, {'locator':'段落 3','text':'PROC-DIM 采用 QC-DIM-01。'}]}
    path=f"{doc['id']}:v1:{doc['hash']}:装配.docx"
    data={'chunks':[{'chunk_id':'packed','file_path':path,'content':
        '【来源定位：段落 2】\nL100 使用 DRV-07。\n\n【来源定位：段落 3】\nPROC-DIM 采用 QC-DIM-01。'}]}
    rows=graph_citations(data,[doc])
    assert [row['locator'] for row in rows]==['段落 2','段落 3']
    data['chunks'][0]['content']+='\n\n【来源定位：段落 99】\nL100 使用 DRV-99。'
    assert len(graph_citations(data,[doc]))==2


def test_long_paragraphs_stay_bounded_and_every_continuation_has_its_locator():
    text='台灯 L100 的装配和检验依据。'*100
    chunks=located_chunks(CharacterTokenizer(),json.dumps([{'locator':'第 2 页','text':text}]),chunk_token_size=120)
    assert len(chunks)>2
    assert all(chunk['tokens']<=120 and chunk['content'].startswith('【来源定位：第 2 页】\n') for chunk in chunks)
    assert [c['chunk_order_index'] for c in chunks]==list(range(len(chunks)))


def test_actual_embedding_budget_is_checked_without_truncating_its_inputs():
    from types import SimpleNamespace
    class EmbeddingTokenizer:
        def encode(self,text):return SimpleNamespace(ids=list(text)*8)
    tokenizer=EmbeddingTokenizer()
    chunks=located_chunks(CharacterTokenizer(),json.dumps([{'locator':'行 1','text':'台灯测试'*100}]),
                          chunk_token_size=480,embedding_tokenizer=tokenizer)
    assert len(chunks)>5
    assert all(len(tokenizer.encode(chunk['content']).ids)<=512 for chunk in chunks)


def test_unicode_characters_are_never_decoded_from_partial_token_sequences():
    class ByteTokenizer:
        def encode(self,text):return list(text.encode('utf-8'))
        def decode(self,tokens):return bytes(tokens).decode('utf-8',errors='replace')
    original='锂电池涂漆后出现闪烁，先复核实际记录。'*60
    chunks=located_chunks(ByteTokenizer(),json.dumps([{'locator':'段落 1','text':original}]),chunk_token_size=120)
    assert len(chunks)>3
    for chunk in chunks:
        excerpt=chunk['content'].split('\n',1)[1]
        assert '\ufffd' not in excerpt and excerpt in original


def test_sdk_callback_accepts_all_six_positional_arguments():
    from types import SimpleNamespace
    from assistant.worker import graph_chunker
    class EmbeddingTokenizer:
        def encode(self,text):return SimpleNamespace(ids=list(text))
    callback=graph_chunker(EmbeddingTokenizer())
    result=callback(CharacterTokenizer(),json.dumps([{'locator':'行 1','text':'L100 DRV-07'}]),None,False,30,480)
    assert result and 'L100 DRV-07' in result[0]['content']


def test_explicit_graph_keywords_are_taken_only_from_the_question():
    from assistant.worker import exact_query_keywords
    assert exact_query_keywords('L100的驱动板、装配工序和检验规范怎样关联？')=={
        'll_keywords':['L100'],'hl_keywords':['驱动板','工序','检验','关联']}
    assert exact_query_keywords('为什么会发生这种情况？')=={}
    assert exact_query_keywords('QC-DIM-01 是什么？')['ll_keywords']==['QC-DIM-01']


@pytest.mark.parametrize('mode', ['graph', 'fusion'])
@pytest.mark.parametrize('local_has_target', [True, False])
def test_packed_sources_preserve_target_beyond_citation_limit(monkeypatch, tmp_path, mode, local_has_target):
    from contextlib import contextmanager
    from types import SimpleNamespace
    from assistant.config import Settings
    from assistant.knowledge import Knowledge
    from assistant.ontology import contains_target

    pieces = [{'locator': f'paragraph {n}', 'text': f'General note {n}.'} for n in range(1, 11)]
    pieces.append({'locator': 'paragraph 11', 'text': 'L100 uses DRV-07.'})
    doc = {'id': 'a'*32, 'version': 1, 'hash': 'b'*64, 'name': 'notes.docx', 'pieces': pieces}
    chunks = located_chunks(CharacterTokenizer(), json.dumps(pieces), chunk_token_size=480)
    assert len(chunks) == 1
    chunks[0].update(chunk_id='packed', file_path=f"{doc['id']}:v1:{doc['hash']}:{doc['name']}")
    graph_data = {'chunks': chunks, 'entities': [{'entity_name': 'L100'}], 'relationships': []}
    assert len(graph_citations(graph_data, [doc])) == 11
    local = [{**{key: doc[key] for key in ('name', 'version', 'hash')},
              'documentId': doc['id'], 'locator': 'paragraph 11',
              'text': pieces[-1]['text'], 'retrieval': 'vector'}] if local_has_target else []
    state = {'generation': 'gtest', 'graph_revision': 1, 'revision': 1, 'answer_revision': 1,
             'model_consent': False, 'preferences': {}}

    class Database:
        def execute(self, sql, parameters=None):
            if 'FROM private_state' in sql:
                return SimpleNamespace(fetchone=lambda: state)
            if 'FROM history' in sql or 'SELECT name,version,vector_version' in sql:
                return SimpleNamespace(fetchall=lambda: [])
            if 'active_name AS name' in sql:
                return SimpleNamespace(fetchall=lambda: [doc])
            raise AssertionError(f'Unexpected database operation: {sql}')

    class Databases:
        @contextmanager
        def connect(self, uid):
            assert uid == 123
            yield Database()

    kb = Knowledge(Settings(root=tmp_path, llm_url='', llm_key='', llm_model=''), Databases())
    monkeypatch.setattr(kb.vectors, 'retrieve', lambda uid, question: local)
    monkeypatch.setattr(kb, 'worker', lambda *args, **kwargs: {'data': graph_data})
    monkeypatch.setattr(kb, 'remember', lambda uid, question, session, state, result: result)
    answer = kb.ask(123, 'L100 relationship', mode)
    assert answer['requestedMode'] == answer['mode'] == mode
    assert not answer['generated']
    assert contains_target('L100 relationship', answer['citations'])
    assert any(source['locator'] == 'paragraph 11' and source['text'] == 'L100 uses DRV-07.'
               for source in answer['citations'])
    assert 1 <= len(answer['citations']) <= 10
    assert [source['id'] for source in answer['citations']] == [
        f'S{number}' for number in range(1, len(answer['citations'])+1)]
