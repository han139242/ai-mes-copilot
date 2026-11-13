"""Conservative domain identifiers, never guessed synonyms or model-invented links."""
import re
import hashlib
import json

IDENTIFIER=re.compile(r"[A-Za-z][A-Za-z0-9]*(?:-[A-Za-z0-9]+)+|[A-Za-z]{1,8}\d{2,}")
MODIFIERS=re.compile(r"装配工序|检验标准|检验规范|检测设备|生产设备|驱动板|检验码|产品|物料|工序|设备|故障|标准|岗位|型号|示例|编号|[\s：:（）()\[\]【】\"'“”‘’]")
DOMAIN_PREFIXES={"PROC","QC","SOP","STD","DRV","LED","PCB","ADP","MAT","OP","RIG","EQ","DEV","ERR","FLICKER"}


def graph_profile(settings):
    # Bump the pipeline revision whenever extraction guidance/chunking/normalization changes.
    value={"pipeline":"lamp-domain-v3-lightrag-1.5.7-packed480-wordpiece512-overlap30", "provider":settings.llm_url.rstrip("/"),
           "model":settings.llm_model,"embedding":settings.embedding_model}
    return hashlib.sha256(json.dumps(value,sort_keys=True).encode()).hexdigest()


def identifier_tokens(text):
    return [m[0] for m in IDENTIFIER.finditer(text)
            if any(c.isdigit() for c in m[0]) or m[0].split("-",1)[0].upper() in DOMAIN_PREFIXES]


def identifier_pattern(token):
    return r"(?<![A-Za-z0-9_-])" + re.escape(token) + r"(?![A-Za-z0-9_-])"


def missing_identifiers(question, citations):
    tokens = list(dict.fromkeys(token.upper() for token in identifier_tokens(question)))
    source_text = "\n".join(row["text"] for row in citations)
    return [token for token in tokens if not re.search(identifier_pattern(token), source_text, re.IGNORECASE)]


def contains_target(question, citations):
    tokens = identifier_tokens(question)
    return not tokens or not missing_identifiers(tokens[0], citations)


def is_comparison(question):
    return (len({token.upper() for token in identifier_tokens(question)}) > 1
            and bool(re.search(r"对比|比较|区别|差异|不同|分别|\bcompare\b|\bdifferences?\b", question, re.IGNORECASE)))


def has_query_evidence(question, citations):
    tokens = identifier_tokens(question)
    if is_comparison(question):
        return any(not missing_identifiers(token, citations) for token in tokens)
    return contains_target(question, citations)


def canonical_entity(name):
    matches=list(IDENTIFIER.finditer(name))
    if len(matches)!=1: return name
    found=matches[0]
    remainder=name[:found.start()]+name[found.end():]
    if MODIFIERS.sub("",remainder): return name
    return found[0].upper()


async def normalize_graph(rag):
    """SDK edit API synchronizes graph, vector indexes and source tracking without an LLM."""
    graph=rag.chunk_entity_relation_graph
    labels=await graph.get_all_labels()
    if len(labels)>2000: raise ValueError("图谱实体超过首版处理上限，请分批整理资料")
    edits=[]
    for label in labels:
        target=canonical_entity(label)
        if target==label or not await graph.has_node(label): continue
        existing=await graph.get_node(target)
        original=await graph.get_node(label)
        if existing and existing.get("entity_type")!=original.get("entity_type"):
            continue  # An identifier collision across incompatible types needs a person, not a guess.
        result=await rag.aedit_entity(label,{"entity_name":target},allow_rename=True,allow_merge=True)
        summary=result.get("operation_summary",{})
        if summary.get("operation_status")!="success" or summary.get("final_entity")!=target:
            raise RuntimeError("图谱编号合并未成功，候选图谱不予发布")
        if not await graph.has_node(target) or await graph.has_node(label):
            raise RuntimeError("图谱编号合并校验未通过，候选图谱不予发布")
        edits.append({"from":label,"to":target})
    return edits
