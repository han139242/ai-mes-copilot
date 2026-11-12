"""Private, user-authored review sheets copied from server-owned answer history.

No model calls, business writes, or client-supplied evidence. Freshness is checked
against the current document versions whenever a sheet is returned.
"""
import re
import uuid
from psycopg.types.json import Jsonb


def source_status(citations, documents):
    by_id = {row['id']: row for row in documents}
    reasons = []
    for source in citations:
        doc = by_id.get(source.get('documentId'))
        if not doc or doc['deleted']:
            reasons.append('有来源已删除')
        elif (doc['version'] != source.get('version') or doc['vector_version'] != source.get('version')
              or doc['hash'] != source.get('hash') or doc['active_hash'] != source.get('hash')):
            reasons.append('有来源版本已变化或尚未就绪')
    return {'fresh': bool(citations) and not reasons,
            'reason': '；'.join(dict.fromkeys(reasons)) or ('' if citations else '没有可核对的来源')}


class ReviewNotes:
    def __init__(self, databases):
        self.dbs = databases

    @staticmethod
    def ensure(db):
        # The existing per-user ordinary role owns its schema. Add only this
        # optional table on first use; no global or existing-table migration.
        if not db.execute("SELECT to_regclass('public.review_notes') AS name").fetchone()['name']:
            db.execute('''CREATE TABLE review_notes (
                id TEXT PRIMARY KEY, history_id BIGINT NOT NULL UNIQUE,
                question TEXT NOT NULL, answer TEXT NOT NULL, citations JSONB NOT NULL,
                title TEXT NOT NULL, comment TEXT NOT NULL DEFAULT '', tasks JSONB NOT NULL DEFAULT '[]',
                version INTEGER NOT NULL DEFAULT 1,
                created TIMESTAMPTZ NOT NULL DEFAULT now(), updated TIMESTAMPTZ NOT NULL DEFAULT now())''')

    @staticmethod
    def documents(db):
        return db.execute('SELECT id,version,vector_version,hash,active_hash,deleted FROM documents').fetchall()

    @staticmethod
    def present(row, documents):
        return {**row, **source_status(row['citations'], documents)}

    def listing(self, uid):
        with self.dbs.locked(uid) as db:
            self.ensure(db)
            rows = db.execute('SELECT * FROM review_notes ORDER BY updated DESC,id LIMIT 50').fetchall()
            docs = self.documents(db)
        return [self.present(row, docs) for row in rows]

    def add(self, uid, payload):
        identity = payload.get('historyId')
        if type(identity) is not int or identity < 1:
            raise ValueError('请先打开已保存的资料答复')
        with self.dbs.locked(uid) as db:
            self.ensure(db)
            existing = db.execute('SELECT * FROM review_notes WHERE history_id=%s', (identity,)).fetchone()
            if existing:
                return self.present(existing, self.documents(db))
            history = db.execute('SELECT question,response,valid FROM history WHERE id=%s', (identity,)).fetchone()
            if not history or not history['valid']:
                raise ValueError('该答复不存在或依据已变化，请重新核对后保存')
            answer = history['response']
            citations = answer.get('citations') or []
            docs = self.documents(db)
            if not source_status(citations, docs)['fresh']:
                raise ValueError('该答复没有当前有效的资料来源，不能建立研判工作单')
            if db.execute('SELECT count(*) AS n FROM review_notes').fetchone()['n'] >= 50:
                raise ValueError('已保存50份工作单，请先整理后再添加')
            row = db.execute('''INSERT INTO review_notes(id,history_id,question,answer,citations,title)
                VALUES(%s,%s,%s,%s,%s,%s) RETURNING *''',
                (uuid.uuid4().hex, identity, history['question'], str(answer.get('answer', ''))[:20000],
                 Jsonb(citations), history['question'][:100])).fetchone()
        return self.present(row, docs)

    @staticmethod
    def identity(payload):
        identity, version = payload.get('id'), payload.get('version')
        if (not isinstance(identity, str) or not re.fullmatch('[a-f0-9]{32}', identity)
                or type(version) is not int or version < 1):
            raise ValueError('工作单编号或版本无效')
        return identity, version

    def update(self, uid, payload):
        identity, version = self.identity(payload)
        title, comment, tasks = payload.get('title'), payload.get('comment'), payload.get('tasks')
        if not isinstance(title, str) or not 1 <= len(title.strip()) <= 100:
            raise ValueError('工作单标题为1～100字')
        if not isinstance(comment, str) or len(comment) > 2000:
            raise ValueError('人工备注最多2000字')
        if not isinstance(tasks, list) or len(tasks) > 20:
            raise ValueError('每份工作单最多20个核对项')
        cleaned = []
        for task in tasks:
            if (not isinstance(task, dict) or not isinstance(task.get('text'), str)
                    or not 1 <= len(task['text'].strip()) <= 160 or type(task.get('done')) is not bool):
                raise ValueError('请输入1～160字的核对项')
            cleaned.append({'text': task['text'].strip(), 'done': task['done']})
        with self.dbs.locked(uid) as db:
            self.ensure(db)
            row = db.execute('''UPDATE review_notes SET title=%s,comment=%s,tasks=%s,
                version=version+1,updated=now() WHERE id=%s AND version=%s RETURNING *''',
                (title.strip(), comment, Jsonb(cleaned), identity, version)).fetchone()
            if not row:
                raise ValueError('工作单已在别处更新或删除，请刷新后重新编辑；当前草稿仍保留')
            docs = self.documents(db)
        return self.present(row, docs)

    def delete(self, uid, payload):
        identity, version = self.identity(payload)
        with self.dbs.locked(uid) as db:
            self.ensure(db)
            row = db.execute('DELETE FROM review_notes WHERE id=%s AND version=%s RETURNING id',
                             (identity, version)).fetchone()
            if not row:
                raise ValueError('工作单已在别处更新或删除，请刷新后再试')
        return {'deleted': identity}
