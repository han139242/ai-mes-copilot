"""Local document embeddings, independently available from cloud graph extraction."""
import hashlib
import json
import re
from threading import Lock

import numpy as np
from .ontology import contains_target, identifier_tokens

_MODEL_LOCK = Lock()
_MODELS = {}


def pieces_to_chunks(pieces, length=240, overlap=40):
    chunks = []
    for piece in pieces:
        text = piece["text"].strip()
        for start in range(0, len(text), length-overlap):
            content = text[start:start+length]
            if content:
                chunks.append({"locator": piece["locator"], "text": content})
            if start+length >= len(text):
                break
    return chunks


def embed(settings, texts, query=False):
    # Models contain no user state; document/query inputs are never cached globally.
    from fastembed import TextEmbedding
    cache = str(settings.root.parent / "models" / "embedding")
    with _MODEL_LOCK:
        key = (cache, settings.embedding_model)
        if key not in _MODELS:
            _MODELS[key] = TextEmbedding(model_name=settings.embedding_model, cache_dir=cache,
                                        threads=2, local_files_only=True)
        model = _MODELS[key]
        values = list(model.query_embed(texts) if query else model.embed(texts))
    vectors = np.asarray(values, dtype=np.float32)
    if vectors.shape != (len(texts), settings.embedding_dim) or not np.isfinite(vectors).all():
        raise RuntimeError("本地嵌入模型未返回有效向量")
    return vectors


class VectorIndex:
    def __init__(self, settings, databases):
        self.s, self.dbs = settings, databases

    def index(self, uid, document, job, generation):
        chunks = pieces_to_chunks(document["pieces"])
        values = embed(self.s, [c["text"] for c in chunks])
        with self.dbs.locked(uid) as db:
            attempt = db.execute("""SELECT j.state,j.generation,j.revision,s.revision AS current_revision
                FROM jobs j CROSS JOIN private_state s WHERE j.id=%s AND s.id=1""", (job,)).fetchone()
            if (not attempt or attempt['state'] != 'running' or attempt['generation'] != generation
                    or attempt['revision'] != attempt['current_revision']):
                return False
            current = db.execute("SELECT version,deleted FROM documents WHERE id=%s",
                                 (document["id"],)).fetchone()
            if not current or current["deleted"] or current["version"] != document["version"]:
                return False
            db.execute("DELETE FROM knowledge_chunks WHERE document_id=%s", (document["id"],))
            for n, (chunk, vector) in enumerate(zip(chunks, values)):
                identity = f"{document['id']}:v{document['version']}:{n}:{document['hash']}"
                db.execute("""INSERT INTO knowledge_chunks
                    (id,document_id,document_version,document_hash,locator,content,embedding,model)
                    VALUES (%s,%s,%s,%s,%s,%s,%s::vector,%s)""",
                    (hashlib.sha256(identity.encode()).hexdigest(), document["id"], document["version"],
                     document["hash"], chunk["locator"], chunk["text"],
                     json.dumps(vector.tolist()), self.s.embedding_model))
            db.execute("""UPDATE documents SET vector_state='ready',vector_version=version,vector_error=null,
                active_hash=hash,active_name=name,active_pieces=pieces WHERE id=%s""",
                       (document["id"],))
            db.execute("UPDATE private_state SET answer_revision=answer_revision+1 WHERE id=1")
            db.execute("UPDATE history SET valid=false WHERE valid")
        return True

    def retrieve(self, uid, question, limit=8):
        vector = json.dumps(embed(self.s, [question[:400]], query=True)[0].tolist())
        with self.dbs.connect(uid) as db:
            candidates = db.execute("""SELECT c.id AS "chunkId",d.id AS "documentId",d.active_name AS name,
                    d.vector_version AS version,d.active_hash AS hash,c.locator,c.content AS text,
                    1-(c.embedding <=> %s::vector) AS score
                FROM knowledge_chunks c JOIN documents d ON d.id=c.document_id
                WHERE NOT d.deleted AND d.vector_version>0
                  AND c.document_version=d.vector_version AND c.document_hash=d.active_hash AND c.model=%s
                ORDER BY c.embedding <=> %s::vector LIMIT 40""", (vector,self.s.embedding_model,vector)).fetchall()
        codes = {code.casefold() for code in identifier_tokens(question)}
        for row in candidates:
            content = (row["name"] + " " + row["text"]).casefold()
            row["rank"] = float(row["score"]) + (0.12 if codes and all(c in content for c in codes) else 0)
        candidates.sort(key=lambda row: row["rank"], reverse=True)
        selected, per_document = [], {}
        for row in candidates:
            if float(row["score"]) < 0.42:
                continue
            if per_document.get(row["documentId"], 0) >= 3:
                continue
            per_document[row["documentId"]] = per_document.get(row["documentId"],0)+1
            row.pop("rank")
            row["score"] = round(float(row["score"]),4)
            selected.append(row)
            if len(selected) == limit:
                break
        # Check after scoring and per-file limits; a discarded weak match is not evidence.
        return selected if contains_target(question,selected) else []
