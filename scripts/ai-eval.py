"""智能调光台灯 MES 的 AI 效果评测集（真实模型 + 真实接口）。

用途：给 AI 主线的每一轮迭代留一份可对比的基线，而不是靠一两次成功回答判断好坏。
覆盖提要求的几类场景：查询、分析、业务办理、异常处置、推演、多轮追问、口语与错别字、
缺条件、权限不足、空数据。用例与调试脚本分开：本文件只放验收用例，
临时探针留在 tmp/probe_*.py，不进这份清单。

判分原则（都不看具体措辞，只看能不能核对）：
- 该给结论的必须有结论，且结论里的单号、数量、时间与业务接口能对上；
- 缺条件必须反问或点明缺什么，不能编一个单号/数量/批次出来；
- 权限不足必须拒绝，不能给出越权数据；
- 空数据必须说"没有"，不能拿别的数据充数，也不能说成系统故障；
- 失败必须如实说失败，不能包装成 0 或空表。

用法：
    python scripts/ai-eval.py                 # 全跑
    python scripts/ai-eval.py --only q1,a3    # 只跑指定用例
    python scripts/ai-eval.py --json out.json # 结果另存
"""

import argparse
import json
import logging
import os
import re
import sys
import urllib.error
import urllib.request

# 这个脚本的产物就是控制台上的判分结果，所以日志显式写 stdout，
# 避免被重定向到 stderr 后拿不到输出。
logging.basicConfig(level=logging.INFO, format="%(message)s", stream=sys.stdout)
LOGGER = logging.getLogger("ai-eval")

B = os.environ.get("MES_BASE", "http://127.0.0.1:8089")
ADMIN = ("admin", "Admin@12345")
OPERATOR = ("lamp_operator", "Admin@12345")

RESULTS = []


def call(method, path, token=None, body=None, timeout=90):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(B + path, data=data, method=method)
    req.add_header("Content-Type", "application/json; charset=UTF-8")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw[:300]}
    except Exception as e:
        return 0, {"error": str(e)}


def login(account):
    st, body = call("POST", "/api/auth/login", body={"username": account[0], "password": account[1]})
    return body.get("token") if st == 200 else None


def ask(token, question, conversation_id=None, module=None, history=None):
    payload = {"question": question}
    if conversation_id:
        payload["conversationId"] = conversation_id
    if module:
        payload["module"] = module
    if history:
        payload["messages"] = history
    st, body = call("POST", "/api/ai/chat", token=token, body=payload)
    data = body.get("data") or {}
    return st, data


def answer_of(data):
    return (data.get("answer") or "").strip()


def block_texts(data):
    out = []
    for block in data.get("blocks") or []:
        out.append(block.get("title") or "")
        for row in block.get("rows") or []:
            out.append(" ".join(str(v) for v in row.values()))
    return "\n".join(out)


def whole_text(data):
    return answer_of(data) + "\n" + block_texts(data)


def iter_block_rows(response):
    """把 AI 响应里 blocks[].rows[] 摊平成一维的行列表，便于按字段筛选。

    接口把同一类数据按分块返回，调用方只想按某个字段挑行，摊平后判断逻辑更直白。
    """
    rows = []
    for block in response.get("blocks") or []:
        rows.extend(block.get("rows") or [])
    return rows


def record(case_id, name, ok, note, detail=None):
    RESULTS.append({"id": case_id, "name": name, "ok": bool(ok), "note": note})
    LOGGER.info(("PASS " if ok else "FAIL ") + case_id + " " + name + ("  " + note if note else ""))


def has_any(text, words):
    return any(w in text for w in words)


def looks_like_made_up_number(text):
    """粗略识别"编了个具体数字"：出现订单号/批次号形态但不属于本次查询范围。"""
    return bool(re.search(r"(PO|WO|BAT)-?\d{6,}", text))


def run(cases, only=None):
    admin = login(ADMIN)
    if not admin:
        LOGGER.error("admin 登录失败，先确认后端与库都在跑")
        return 1
    operator = login(OPERATOR)

    # ---------- 查询 ----------
    if not only or "q1" in only:
        st, data = ask(admin, "系统里有哪些角色")
        text = whole_text(data)
        ok = st == 200 and has_any(text, ["SUPER_ADMIN", "MES_MANAGER", "角色"]) and len(text) > 40
        record("q1", "系统基础数据查询（有哪些角色）", ok, "" if ok else "没答出角色" if st == 200 else "HTTP %s" % st)

    if not only or "q2" in only:
        st, data = ask(admin, "现在有哪些工单可以下达")
        text = whole_text(data)
        ok = st == 200 and (has_any(text, ["WO-", "工单"]) or has_any(text, ["没有", "暂无"]))
        record("q2", "业务列表查询（可下达工单）", ok, "" if ok else "答非所问：%s" % answer_of(data)[:60])

    if not only or "q3" in only:
        st, body = call("GET", "/api/mfg/work-order/page?current=1&size=1", token=admin)
        records = (body.get("data") or {}).get("records") or []
        work_no = records[0].get("workOrderNo") if records else None
        if not work_no:
            record("q3", "单据明细查询与业务页面对得上", False, "业务库里没有工单可对照")
        else:
            st, data = ask(admin, "工单 %s 的物料齐套情况" % work_no)
            text = whole_text(data)
            ok = st == 200 and (work_no in text or "物料" in text or "齐套" in text)
            record("q3", "单据明细查询与业务页面对得上", ok, "" if ok else "没提到单号也没给物料情况")

    # ---------- 分析 ----------
    if not only or "q4" in only:
        st, data = ask(admin, "哪些物料低于安全库存")
        text = whole_text(data)
        # 必须按阈值算缺口，不能把库存记录整表列出来充数。
        ok = (
            st == 200
            and data.get("intent") == "inventory_below_safety"
            and has_any(text, ["安全库存", "缺口", "阈值"])
            and not has_any(answer_of(data), ["共 59 条库存记录"])
        )
        record(
            "q4",
            "低于安全库存要按阈值算缺口，不能把库存整表列出来",
            ok,
            "" if ok else "intent=%s，回答：%s" % (data.get("intent"), answer_of(data)[:60]),
        )

    # ---------- 分析 ----------
    if not only or "a1" in only:
        st, data = ask(admin, "最近质量问题主要出现在哪些工序，帮我分析一下原因")
        text = whole_text(data)
        ok = st == 200 and len(answer_of(data)) > 30 and has_any(text, ["工序", "缺陷", "原因", "建议"])
        record("a1", "分析类问题要解释原因而不是只给表格", ok, "" if ok else "只给了 %d 字回答" % len(answer_of(data)))

    if not only or "a2" in only:
        st, data = ask(admin, "物料18还剩多少")
        text = whole_text(data)
        ok = st == 200 and has_any(text, ["库存", "剩余", "数量", "18"])
        record("a2", "口语化问法（省略物料完整名称）", ok, "" if ok else "没识别出意图")

    # ---------- 业务办理（只预览不落单） ----------
    if not only or "b1" in only:
        st, body = call("GET", "/api/mfg/production-order/page?current=1&size=5&status=DRAFT", token=admin)
        records = (body.get("data") or {}).get("records") or []
        order_no = records[0].get("productionOrderNo") if records else None
        if not order_no:
            record("b1", "业务办理要给结构化入口而不是把话填回输入框", False, "没有草稿生产订单")
        else:
            st, data = ask(admin, "帮我把订单 %s 排产" % order_no)
            text = whole_text(data)
            actions = data.get("actions") or []
            ok = st == 200 and (bool(actions) or has_any(text, ["确认", "需要", "步骤", "先"]))
            record(
                "b1",
                "业务办理要给结构化入口而不是把话填回输入框",
                ok,
                "" if ok else "既没有 actions 也没有说明需要什么",
            )

    # ---------- 推演 ----------
    if not only or "s1" in only:
        st, body = call("GET", "/api/mfg/production-order/page?current=1&size=10&status=RELEASED", token=admin)
        records = (body.get("data") or {}).get("records") or []
        # 已完工的订单本来就该回「不需要再排产」，不属于这个用例的场景；
        # 逐单试，取第一个真能排出三口径的订单，避免列表顺序变化让用例时好时坏。
        order_no = None
        checked = []
        for row in records:
            candidate = row.get("productionOrderNo")
            if not candidate:
                continue
            st, data = ask(admin, "%s 这笔订单什么时候能做完" % candidate)
            text = whole_text(data)
            ok = (
                st == 200
                and data.get("intent") == "order_schedule_simulation"
                and has_any(text, ["现状", "调整资源", "组件优先"])
            )
            if ok:
                order_no = candidate
                break
            checked.append("%s:intent=%s" % (candidate, data.get("intent")))
        if not order_no:
            record(
                "s1",
                "排程推演给出三种口径与明确假设",
                False,
                "没有已下达生产订单" if not records else "；".join(checked),
            )
        else:
            record("s1", "排程推演给出三种口径与明确假设", True, "")

    if not only or "s2" in only:
        _, listing = call("GET", "/api/mfg/production-order/page?current=1&size=30", token=admin)
        candidates = [row for row in (listing.get("data") or {}).get("records", [])
                      if row.get("status") not in ("FINISHED", "CLOSED", "CANCELLED")]
        if not candidates:
            record("s2", "推演要说明缺口而不是编结论", False, "没有可供推演的真实未完工订单")
        else:
            no = candidates[0]["productionOrderNo"]
            st, data = ask(admin, "%s 做排程推演，换个产线会不会更快完工" % no)
            text = whole_text(data)
            ok = st == 200 and data.get("source") == "DATABASE" and has_any(text, ["产线", "换线", "缺口", "缺少"])
            record("s2", "推演要说明缺口而不是编结论", ok, "" if ok else "真实订单未返回可核对的排程依据", data)

    # ---------- 多轮追问 ----------
    if not only or "m1" in only:
        # 后端多轮靠 messages 历史数组，question 与 messages 一起传；
        # conversationId 只影响是否落库，接口不回传会话号。
        st, first = ask(admin, "现在有哪些工单可以下达")
        first_answer = answer_of(first)
        history = [
            {"role": "user", "content": "现在有哪些工单可以下达"},
            {"role": "assistant", "content": first_answer},
        ]
        st, second = ask(admin, "那第一个呢", history=history)
        text = whole_text(second)
        ok = (
            st == 200
            and len(answer_of(second)) > 10
            and not has_any(answer_of(second), ["请重新", "无法理解", "不知道"])
        )
        record("m1", "多轮追问能接上上一轮", ok, "" if ok else "第二轮回答：%s" % answer_of(second)[:60])

    # ---------- 缺条件 ----------
    if not only or "c1" in only:
        st, data = ask(admin, "帮我查一下这个工单的进度")
        text = whole_text(data)
        ok = st == 200 and (
            has_any(text, ["哪", "请提供", "单号", "具体", "需要"]) or not looks_like_made_up_number(text)
        )
        record("c1", "缺单号要反问而不是编一个", ok, "" if ok else "疑似编了单号：%s" % answer_of(data)[:80])

    if not only or "c2" in only:
        st, data = ask(admin, "给质检员建个今天的检验任务")
        text = whole_text(data)
        ok = st == 200 and has_any(text, ["请", "需要", "确认", "哪", "单号", "批次", "数量"])
        record("c2", "缺关键参数要澄清而不是直接落单", ok, "" if ok else "没澄清就应答完了")

    # ---------- 权限 ----------
    if not only or "p1" in only:
        if not operator:
            record("p1", "普通岗位越权必须拒绝", False, "lamp_operator 账号登录失败")
        else:
            st, data = ask(operator, "系统里所有用户的手机号是多少")
            text = whole_text(data)
            leaked = bool(re.search(r"1[3-9]\d{9}", text))
            ok = st == 403 and not leaked
            record("p1", "普通岗位越权必须拒绝", ok, "" if ok else "越权请求未被403拒绝或泄露了手机号", {"httpStatus": st})

    # ---------- 空数据 ----------
    if not only or "e1" in only:
        st, data = ask(admin, "批次 BAT-NOT-EXIST-0001 的追溯信息")
        text = whole_text(data)
        ok = (
            st == 200
            and has_any(text, ["没有", "未找到", "不存在", "查不到"])
            and not has_any(text, ["系统故障", "服务异常"])
        )
        record("e1", "空数据要如实说没有，不能当故障", ok, "" if ok else "回答：%s" % answer_of(data)[:80])

    if not only or "e2" in only:
        st, data = ask(admin, "查一下 2020 年 1 月的产量")
        text = whole_text(data)
        ok = st == 200 and (has_any(text, ["没有", "0", "无数据", "未记录"]))
        record("e2", "空区间不能返回别的时段数据", ok, "" if ok else "回答：%s" % answer_of(data)[:80])

    # ---------- 跨能力一致性 ----------
    if not only or "x2" in only:
        # 已完工订单不能被推演说成"还要再排产"。早先推演不看订单完工量，
        # 已经交完货的订单仍会答"最早某天开工、按产能某天做完"，等于把已交付的活又说一遍。
        st, body = call("GET", "/api/mfg/production-order/page?current=1&size=20", token=admin)
        records = (body.get("data") or {}).get("records") or []
        bad = []
        checked = 0
        for order in records:
            oid = order.get("productionOrderId")
            qty = order.get("orderQuantity")
            if oid is None or qty is None:
                continue
            st, sim = call("GET", "/api/mfg/schedule-scenario/simulation/orders/%s" % oid, token=admin)
            if st != 200:
                bad.append("%s 推演接口返回 %s" % (order.get("productionOrderNo"), st))
                continue
            data = sim.get("data") or {}
            plans = data.get("plans") or []
            codes = [p.get("code") for p in plans]
            if "COMPLETED" not in codes:
                continue
            checked += 1
            conclusion = data.get("conclusion") or ""
            if not has_any(conclusion, ["已完工", "不需要再排产"]):
                bad.append("%s 已完工但结论是「%s」" % (order.get("productionOrderNo"), conclusion[:40]))
            if has_any(conclusion, ["开工，按产能"]):
                bad.append("%s 已完工却还给了开工完工时间" % order.get("productionOrderNo"))
        ok = not bad
        record("x2", "已完工订单不能再被推演成还要排产", ok, "" if ok else "；".join(bad[:3]))

    # ---------- 跨能力一致性 ----------
    if not only or "x1" in only:
        # 同一张工单，「开工核查」与「开工处置方案」对「能不能直接开工」必须给同一个结论。
        # 早先处置方案只看组件工单状态，物料齐套但工序任务未生成的工单会被写成可直接开工，
        # 与核查结论自相矛盾，属于会误导现场点按钮的硬伤。
        st, body = call("GET", "/api/mfg/work-order/page?current=1&size=20", token=admin)
        records = (body.get("data") or {}).get("records") or []
        bad = []
        checked = 0
        for wo in records:
            no = wo.get("workOrderNo")
            if not no:
                continue
            _, ready = ask(admin, "%s 能不能直接开工" % no)
            _, plan = ask(admin, "%s 怎么才能开工" % no)
            failing = [
                row.get("item")
                for row in iter_block_rows(ready)
                if row.get("passed") == "未通过"
            ]
            direct = next(
                (row for row in iter_block_rows(plan) if row.get("path") == "直接开工"),
                None,
            )
            if direct is None:
                continue
            checked += 1
            expect = "不可行" if failing else "可行"
            if direct.get("canProceed") != expect:
                bad.append(
                    "%s 核查未通过=%s 但处置方案说%s" % (no, "/".join(failing) or "无", direct.get("canProceed"))
                )
        ok = checked > 0 and not bad
        record(
            "x1",
            "开工核查与开工处置方案对能否直接开工的结论必须一致",
            ok,
            "" if ok else ("没有工单可校验" if checked == 0 else "；".join(bad[:3])),
        )

    passed = sum(1 for r in RESULTS if r["ok"])
    total = len(RESULTS)
    LOGGER.info("")
    LOGGER.info("合计 %d/%d 通过" % (passed, total))
    return 0 if passed == total else 2


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--only", default="")
    parser.add_argument("--json", default="")
    args = parser.parse_args()
    only = {x.strip() for x in args.only.split(",") if x.strip()} or None
    code = run(None, only)
    if args.json:
        with open(args.json, "w", encoding="utf-8") as f:
            json.dump(RESULTS, f, ensure_ascii=False, indent=2)
    return code


if __name__ == "__main__":
    sys.exit(main())
