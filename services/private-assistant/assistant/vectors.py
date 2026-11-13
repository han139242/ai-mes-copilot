"""Local document embeddings, independently available from cloud graph extraction."""
import hashlib
import json
import re
from threading import Lock

import numpy as np
from .ontology import has_query_evidence, identifier_pattern, missing_identifiers

_MODEL_LOCK = Lock()
_MODELS = {}

_CANDIDATES = """SELECT c.id AS "chunkId",d.id AS "documentId",d.active_name AS name,
        d.vector_version AS version,d.active_hash AS hash,c.locator,c.content AS text,
        1-(c.embedding <=> %s::vector) AS score
    FROM knowledge_chunks c JOIN documents d ON d.id=c.document_id
    WHERE NOT d.deleted AND d.vector_version>0
      AND c.document_version=d.vector_version AND c.document_hash=d.active_hash AND c.model=%s"""

# Facets describe the kind of evidence requested. A source can cover more than one.
_FACETS = (
    (r"单台|用量|多少(?:个|颗|套|件)|几个|物料清单|BOM", r"物料清单|用量|每台|单台", r"(?:^|\|)\s*[A-Z]+\d+=\d+(?:\.\d+)?\s*(?=\||$)"),
    (r"驱动板|用料|物料清单|产品资料", r"型号|物料清单|产品规格", r"驱动板|当前生效"),
    (r"装配|哪道工序|哪.*工序", r"装配|工艺|作业指引", r"装配工序|作业步骤|工序完成"),
    (r"检验规范|检验标准|按什么规范|按哪.*规范|哪.*检验", r"检验规范|检验标准", r"检验规范|检验依据|检验工位"),
    (r"设备记录|点检|检验工位|哪.*工位", r"点检|设备", r"工位|点检|设备状态"),
    (r"闪烁|故障|异常|核对|复测", r"故障排查|异常处理", r"复测|重新记录|排查"),
    (r"限值|阈值|误差范围|合格界限", r"规范|标准|规格", r"限值|误差范围|合格界限|阈值"),
)


def evidence_order(question, candidates):
    """Rerank the bounded dense pool using requested facets and source diversity."""
    tokens = missing_identifiers(question, [])
    facets = [(filename, body) for query, filename, body in _FACETS if re.search(query, question, re.I)]
    metric = re.search(r"[\u4e00-\u9fff]{2}(?:误差|偏差|公差)", question)
    ranked = []
    for row in candidates:
        matched = len(tokens) - len(missing_identifiers(question, [row]))
        coverage = {i for i, (filename, body) in enumerate(facets)
                    if re.search(filename, row["name"], re.I) and re.search(body, row["text"], re.I)}
        # A named metric can be defined in a separate paragraph from the product header.
        exact_metric = bool(metric and metric[0] in row["text"])
        score = float(row["score"]) + 0.12 * matched / max(1, len(tokens)) + (0.22 if exact_metric else 0)
        ranked.append((row, score, coverage, matched or exact_metric))
    ordered, covered, documents = [], set(), set()
    while ranked:
        best = max(range(len(ranked)), key=lambda i: (
            ranked[i][1] + 0.12 * bool(ranked[i][2]) + 0.14 * len(ranked[i][2] - covered)
            + (0.025 if ranked[i][0]["documentId"] not in documents else 0)
            - (0.17 if tokens and not ranked[i][3] else 0)
        ))
        row, _, coverage, _ = ranked.pop(best)
        ordered.append(row)
        covered.update(coverage)
        documents.add(row["documentId"])
    return ordered


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
            candidates = db.execute(
                _CANDIDATES + " ORDER BY c.embedding <=> %s::vector,c.id LIMIT 40",
                (vector,self.s.embedding_model,vector)).fetchall()
            selected = self.select_dense(candidates, question, limit)
            missing = missing_identifiers(question, selected)
            if missing:
                selected = self.recover_identifiers(db, vector, question, selected, missing, limit)
        return selected if has_query_evidence(question, selected) else []

    @staticmethod
    def select_dense(candidates, question, limit):
        selected, per_document = [], {}
        eligible = [row for row in candidates if float(row["score"]) >= 0.42]
        for row in evidence_order(question, eligible):
            if per_document.get(row["documentId"], 0) >= 3:
                continue
            per_document[row["documentId"]] = per_document.get(row["documentId"],0)+1
            row["score"] = round(float(row["score"]),4)
            selected.append(row)
            if len(selected) == limit:
                break
        return selected

    def recover_identifiers(self, db, vector, question, selected, missing, limit):
        # PostgreSQL ARE uses the same explicit identifier boundaries. Prioritize
        # the best available source for each missing code, capped at one per code.
        recovered = []
        for token in missing[:min(4, limit)]:
            if not missing_identifiers(token, recovered):
                continue
            row = db.execute(
                _CANDIDATES + " AND c.content ~* %s ORDER BY c.embedding <=> %s::vector,c.id LIMIT 1",
                (vector,self.s.embedding_model,identifier_pattern(token),vector)).fetchone()
            if row:
                recovered.append({**row, "score": round(float(row["score"]), 4), "retrieval": "identifier"})
        if not recovered:
            return selected
        sources = recovered + selected
        reserved = []
        for token in missing_identifiers(question, [])[:min(4, limit)]:
            if missing_identifiers(token, reserved):
                source = next((row for row in sources if not missing_identifiers(token, [row])), None)
                if source:
                    reserved.append(source)
        ranked = reserved + selected
        reserved_ids = {row["chunkId"] for row in reserved}
        result, seen, per_document = [], set(), {}
        for row in ranked:
            doc = row["documentId"]
            if row["chunkId"] in seen or (row["chunkId"] not in reserved_ids and per_document.get(doc, 0) >= 3):
                continue
            result.append(row)
            seen.add(row["chunkId"])
            per_document[doc] = per_document.get(doc, 0) + 1
            if len(result) >= limit:
                break
        return result
