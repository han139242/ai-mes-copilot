#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""按正式排班服务补齐台灯产线的班组日期排班（滚动窗口，可重复执行）。

背景：制造准备的 SCHEDULE 检查要求「当天每条启用产线都有已生效的班组排班」。
演示日期一旦往后挪，原脚本写死的日期就会失效，因此这里用滚动窗口补齐：
从今天起连续 N 天，为每条启用的产线、每个启用的班次各生成一条排班并生效。

排班不直接写库，全部走 /api/org/team-schedule 的正式接口，保证编号、唯一性和状态流转
都由领域服务校验，和页面手工操作完全一致。

用法：
    python scripts/apply-lamp-team-schedule.py                # 默认补 30 天
    python scripts/apply-lamp-team-schedule.py --days 60
    python scripts/apply-lamp-team-schedule.py --dry-run
"""

import argparse
import datetime
import json
import logging
import sys
import urllib.error
import urllib.request

logging.basicConfig(level=logging.INFO, format="%(message)s")
LOGGER = logging.getLogger(__name__)


class ScheduleError(RuntimeError):
    """排班补齐遇到可预期的失败，由入口统一打印后退出。"""


def request(base, path, method="GET", body=None, token=None):
    url = base.rstrip("/") + path
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        payload = error.read().decode("utf-8", "replace")
        raise ScheduleError("请求失败 {} {} -> {} {}".format(method, path, error.code, payload)) from error


def login(base, username, password):
    result = request(base, "/api/auth/login", "POST", {"username": username, "password": password})
    token = result.get("token")
    if not token:
        raise ScheduleError("登录失败：" + json.dumps(result, ensure_ascii=False))
    return token


def fetch_all(base, path, token, key="records"):
    rows, current = [], 1
    while True:
        result = request(
            base, "{}{}current={}&size=200".format(path, "&" if "?" in path else "?", current), token=token
        )
        page = result.get("data") or {}
        batch = page.get(key) or []
        rows.extend(batch)
        total = page.get("total") or 0
        if not batch or len(rows) >= total:
            return rows
        current += 1


def run():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:8089")
    parser.add_argument("--username", default="admin")
    parser.add_argument("--password", default="Admin@12345")
    parser.add_argument("--days", type=int, default=30)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    token = login(args.base, args.username, args.password)

    lines = [row for row in fetch_all(args.base, "/api/factory/line/page", token) if row.get("status") == "RUNNING"]
    shifts = [row for row in fetch_all(args.base, "/api/org/shift/page", token)]
    teams = [row for row in fetch_all(args.base, "/api/org/team/page", token)]
    if not lines or not shifts or not teams:
        raise ScheduleError("产线 / 班次 / 班组数据不完整，先执行演示种子")

    existing = {
        (row.get("teamId"), row.get("lineId"), row.get("shiftId"), str(row.get("workDate")))
        for row in fetch_all(args.base, "/api/org/team-schedule/page", token)
    }

    # 组件线用组件班组，总装线用总装班组，其余产线退回第一个班组。
    def pick_team(line):
        code = (line.get("lineCode") or "").upper()
        for team in teams:
            team_code = (team.get("teamCode") or "").upper()
            if "FINAL" in code and "FINAL" in team_code:
                return team
            if "FINAL" not in code and "COMPONENT" in team_code:
                return team
        return teams[0]

    created = released = 0
    today = datetime.date.today()
    for offset in range(args.days):
        work_date = today + datetime.timedelta(days=offset)
        for line in lines:
            team = pick_team(line)
            for shift in shifts:
                key = (team.get("teamId"), line.get("lineId"), shift.get("shiftId"), work_date.isoformat())
                if key in existing:
                    continue
                payload = {
                    "teamId": team.get("teamId"),
                    "lineId": line.get("lineId"),
                    "shiftId": shift.get("shiftId"),
                    "workDate": work_date.isoformat(),
                    "plannedHeadcount": 3,
                    "remark": "演示排班（滚动补齐）",
                }
                if args.dry_run:
                    LOGGER.info("[dry-run] 将创建 %s", payload)
                    continue
                request(args.base, "/api/org/team-schedule", "POST", payload, token)
                created += 1
                # 新建是草稿，立刻走生效接口，制造准备只认已生效排班。
                rows = fetch_all(
                    args.base,
                    "/api/org/team-schedule/page?teamId={}&lineId={}&workDate={}".format(
                        team.get("teamId"), line.get("lineId"), work_date.isoformat()
                    ),
                    token,
                )
                for row in rows:
                    if row.get("shiftId") == shift.get("shiftId") and row.get("status") == "DRAFT":
                        request(
                            args.base,
                            "/api/org/team-schedule/{}/release".format(row.get("scheduleId")),
                            "PUT",
                            None,
                            token,
                        )
                        released += 1

    LOGGER.info("产线 {} 条、班次 {} 个、班组 {} 个".format(len(lines), len(shifts), len(teams)))
    LOGGER.info("新建排班 {} 条，生效 {} 条，窗口 {} 天（{} 起）".format(created, released, args.days, today))

    for product_id, product_code in ((6, "LAMP-L200"), (5, "LAMP-L100")):
        report = request(
            args.base, "/api/mdm/manufacturing-preparation/{}?date={}".format(product_id, today), token=token
        )
        data = report.get("data") or {}
        schedule = next((c for c in data.get("checks") or [] if c.get("code") == "SCHEDULE"), {})
        LOGGER.info(
            "{} {} executionReady={} SCHEDULE={}".format(
                product_code, today, data.get("executionReady"), schedule.get("status")
            )
        )


if __name__ == "__main__":
    try:
        run()
    except ScheduleError as error:
        LOGGER.info("%s", error)
        sys.exit(1)
