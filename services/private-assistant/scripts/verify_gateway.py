"""Loopback demo gateway acceptance; emits neither credentials nor session tokens."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re

import httpx


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url",default="http://127.0.0.1:8089")
    parser.add_argument("--output",required=True)
    args=parser.parse_args()
    if not re.fullmatch(r"http://127\.0\.0\.1:(8089|18089)",args.base_url):
        raise SystemExit("Only the explicitly isolated stage-2 demo listener is supported")
    password=os.environ.get("MES_GATEWAY_TEST_PASSWORD")
    if not password: raise SystemExit("Set MES_GATEWAY_TEST_PASSWORD for the disposable demo accounts")
    client=httpx.Client(base_url=args.base_url,timeout=40,follow_redirects=False)
    results=[]
    def record(name,passed):
        results.append({"name":name,"passed":bool(passed)})
        if not passed: raise RuntimeError("Gateway check failed: "+name)
    def login(username):
        response=client.post("/api/auth/login",json={"username":username,"password":password})
        value=response.json()
        record(username+"_login",response.status_code==200 and bool(value.get("token")))
        return {"Authorization":"Bearer "+value["token"]}
    def call(headers,action,payload=None):
        return client.post("/api/ai/private/dispatch",headers=headers,json={"action":action,"payload":payload or {}})
    record("unauthenticated_dispatch_rejected",call({},"documents.list").status_code==401)
    admin=login("admin"); operator=login("lamp_quality"); restricted=login("lamp_operator")
    record("original_restricted_role_cannot_use_ai_private_api",call(restricted,"documents.list").status_code==403)
    response=call(admin,"documents.list"); record("authenticated_private_listing",response.status_code==200)
    listing=response.json()["data"]; docs=listing["documents"]
    record("synthetic_documents_present",len(docs)==2 and all(d["name"].startswith("合成示例-") for d in docs))
    own=call(operator,"documents.list"); record("second_user_private_listing",own.status_code==200)
    operator_docs=own.json()["data"]["documents"]
    record("separate_user_document_catalog",not set(d["id"] for d in operator_docs)&set(d["id"] for d in docs))
    document=docs[0]
    response=call(admin,"documents.download",{"documentId":document["id"]})
    record("authenticated_original_download",response.status_code==200)
    raw=base64.b64decode(response.json()["data"]["content"])
    record("download_matches_source_hash",hashlib.sha256(raw).hexdigest()==document["hash"])
    blocked=call(operator,"documents.download",{"documentId":document["id"],"uid":1})
    record("foreign_original_and_forged_payload_uid_rejected",blocked.status_code==400)
    blocked=call(operator,"documents.preview",{"documentId":document["id"]})
    record("foreign_preview_rejected",blocked.status_code==400)
    history=call(operator,"history"); record("second_user_history_authorized",history.status_code==200)
    main_ids={d["id"] for d in docs}
    record("history_does_not_contain_other_users_sources",not any(
        c.get("documentId") in main_ids for row in history.json()["data"] for c in row["response"].get("citations",[])))
    response=client.delete("/api/auth/face/admin/2147483646",headers=operator)
    record("ordinary_user_cannot_admin_reset_face",response.status_code==403)
    response=client.get("/api/auth/face/me",headers=admin)
    record("own_face_status_without_template_disclosure",response.status_code==200 and
           not any(k in response.json().get("data",{}) for k in ("embedding","ciphertext","template","score")))
    status=call(admin,"status").json()["data"]
    used=status["budget"]["used"]
    response=client.post("/api/auth/logout",headers=admin)
    record("existing_mes_logout",response.status_code==200)
    record("logged_out_token_cannot_read_private_documents",call(admin,"documents.list").status_code==401)
    record("other_user_session_remains_valid",call(operator,"status").status_code==200)
    final=call(operator,"status").json()["data"]["budget"]["used"]
    record("gateway_checks_used_no_model_calls",final==used)
    report={"scope":"isolated loopback demo","total":len(results),"passed":sum(r["passed"] for r in results),
            "modelCalls":0,"checks":results}
    output=Path(args.output); output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
    print(json.dumps({k:v for k,v in report.items() if k!="checks"}))


if __name__=="__main__": main()
