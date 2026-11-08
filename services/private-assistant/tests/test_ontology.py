import asyncio
import pytest
from assistant.ontology import canonical_entity, contains_target, identifier_tokens, normalize_graph


def test_only_unambiguous_identifier_and_category_modifiers_are_canonicalized():
    assert canonical_entity("装配工序PROC-DIM")=="PROC-DIM"
    assert canonical_entity("DRV-07驱动板")=="DRV-07"
    assert canonical_entity("检验标准QC-DIM-01")=="QC-DIM-01"
    assert canonical_entity("产品 l100")=="L100"
    assert canonical_entity("更换DRV-07")=="更换DRV-07"
    assert canonical_entity("L100 和 L200")=="L100 和 L200"
    assert canonical_entity("驱动板")=="驱动板"


def test_final_evidence_requires_exact_numeric_or_domain_identifier():
    assert identifier_tokens("PROC-DIM 的规范是什么") == ["PROC-DIM"]
    assert not identifier_tokens("is this read-only")
    assert not contains_target("L900 用哪个驱动板",[{"text":"L100 使用 DRV-07"}])
    assert not contains_target("PROC-DIM 用什么规范",[{"text":"PROC-NEW 使用 QC-02"}])
    assert not contains_target("L100 用什么规范",[{"text":"L1000 使用 QC-02"}])
    assert contains_target("L100 是否能改成 DRV-99",[{"text":"L100 使用 DRV-07"}])


def test_sdk_returned_merge_failure_cannot_be_reported_as_normalization_success():
    class Graph:
        async def get_all_labels(self): return ["装配工序PROC-DIM"]
        async def has_node(self,name): return True
        async def get_node(self,name): return {"entity_type":"工序"}
    class Rag:
        chunk_entity_relation_graph=Graph()
        async def aedit_entity(self,*args,**kwargs):
            return {"operation_summary":{"operation_status":"failure","final_entity":"装配工序PROC-DIM"}}
    with pytest.raises(RuntimeError,match="不予发布"):
        asyncio.run(normalize_graph(Rag()))
