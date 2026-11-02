# -*- coding: utf-8 -*-
"""在演示库上准备一张可直接现场演示的整机工单。

背景：演示库里的 WO-9 已全部领料、WO-5 的组件工单还没生产，两者都点不出
「生成领料单 → 领料转线边 → 报工 → 检验 → 成品入库 → 销售发货 → 退料」这条链。
本脚本按正式 API 新造一张 L200 生产订单：先把三张组件工单生产完并入库到半成品仓，
整机工单停在「已下达、未领料」，现场从生成领料单开始逐步操作。

用法：
  MYSQL_BIN="D:/MySQL/MySQL Server 8.0/bin" \
  RUNTIME_LOGIN_USER=admin RUNTIME_LOGIN_PASSWORD=Admin@12345 \
  python scripts/prepare_lamp_demo.py --database mes_lamp_order_e2e --port 3311 \
      --api-base http://127.0.0.1:8089 --run-key lamp-demo-prep --apply
"""

import argparse
import datetime
import importlib.util
import json
import os
import re
import sys
from pathlib import Path
from cli_output import write_json

ROOT = Path(__file__).resolve().parents[1]
NOTE = "课程演示准备，非实物测量；组件按当前配置演练"


def load_rehearsal():
    path = ROOT / "scripts" / "run_lamp_order_rehearsal.py"
    spec = importlib.util.spec_from_file_location("lamp_rehearsal", path)
    module = importlib.util.module_from_spec(spec)
    sys.modules["lamp_rehearsal"] = module
    spec.loader.exec_module(module)
    return module


class DemoPreparation:
    """复用演练脚本的取数、API 与工序执行能力，只做「组件先做完、整机留白」这一种编排。"""

    def __init__(self, rehearsal):
        self.run = rehearsal

    def ensure_teams(self):
        """演示库还没有演练班组时补上；已有就直接复用，不改动原有组织数据。"""
        run = self.run
        if not run.rows("org_department", "department_code='LAMP-REH-DEPT' AND deleted_flag=0"):
            run.api(
                "POST",
                "/api/org/department",
                {
                    "departmentCode": "LAMP-REH-DEPT",
                    "departmentName": "台灯工程演练部门",
                    "parentId": 0,
                    "remark": NOTE,
                },
                key="demo-department",
            )
        department = run.one("org_department", "department_code='LAMP-REH-DEPT' AND deleted_flag=0")
        teams = {}
        for suffix in ["HEAD", "ARM", "BASE", "FINAL"]:
            ecode = "LAMP-REH-EMP-" + suffix
            if not run.rows("org_employee", f"employee_no='{ecode}'"):
                run.api(
                    "POST",
                    "/api/org/employee",
                    {
                        "employeeNo": ecode,
                        "employeeName": "演练-" + suffix,
                        "departmentId": department["department_id"],
                        "positionName": "虚拟演练岗位",
                        "status": "ENABLE",
                        "skillLevel": "高级",
                        "remark": NOTE,
                    },
                    key="demo-employee-" + suffix,
                )
            employee = run.one("org_employee", f"employee_no='{ecode}'")
            tcode = "LAMP-REH-TEAM-" + suffix
            if not run.rows("org_team", f"team_code='{tcode}'"):
                run.api(
                    "POST",
                    "/api/org/team",
                    {
                        "teamCode": tcode,
                        "teamName": "演练-" + suffix + "班组",
                        "departmentId": department["department_id"],
                        "leaderId": employee["employee_id"],
                        "remark": NOTE,
                    },
                    key="demo-team-" + suffix,
                )
            team = run.one("org_team", f"team_code='{tcode}'")
            if not run.rows(
                "org_team_member",
                f"team_id={team['team_id']} AND employee_id={employee['employee_id']}" " AND deleted_flag=0",
            ):
                run.api(
                    "POST",
                    "/api/org/team-member",
                    {
                        "teamId": team["team_id"],
                        "employeeId": employee["employee_id"],
                        "joinDate": "2026-10-25",
                        "status": "ENABLE",
                    },
                    key="demo-member-" + suffix,
                )
            teams[suffix] = team["team_id"]
        run.state["teams"] = teams
        run.save()

    def bind_stations(self, line_id):
        """把组件工序绑到整机线上已启用的同类型工位。

        演示库的工序编码是 LAMP-OP-H-ASSY 这一套，和 db/lamp_station_operation_v1.sql
        里写的 LAMP-OP-H10 不是同一套，所以那个脚本在这个库上是空跑。
        这里按「装配类工序 → PRODUCTION 工位、检查类工序 → INSPECTION 工位」补绑定，
        只补缺失的行，不动已有配置，也不新建虚构工位。
        """
        run = self.run
        # 优先挑没有未关闭安灯的工位：演示库在 8 号工位上挂着一张 ACTIVE 的缺料安灯，
        # 那是给「安灯处置」准备的演示数据，不能为了准备演示去关掉它。
        # 备用共享工位（WS-FG-01）原本是 ENABLE 未投产状态，这里把它启用成 RUNNING。
        run.sql(
            "UPDATE factory_station SET status='RUNNING',version=version+1,updated_time=NOW()"
            f" WHERE line_id={line_id} AND deleted_flag=0 AND status='ENABLE'"
            " AND station_type='PRODUCTION'"
        )
        stations = run.rows(
            "factory_station", f"line_id={line_id} AND deleted_flag=0 AND status='RUNNING'" " ORDER BY station_id"
        )
        open_andon = open_andon_stations(run)
        by_type = {}
        for station in stations:
            if station["station_id"] in open_andon:
                continue
            by_type.setdefault(station["station_type"], station["station_id"])
        if "PRODUCTION" not in by_type or "INSPECTION" not in by_type:
            raise RuntimeError("整机线缺少可用（且无未关闭安灯）的生产或检验工位，无法绑定组件工序")
        operations = run.rows(
            "process_operation",
            "deleted_flag=0 AND status='ENABLE'"
            " AND operation_code IN ('LAMP-OP-H-ASSY','LAMP-OP-H-TEST',"
            "'LAMP-OP-A-ASSY','LAMP-OP-A-CHECK','LAMP-OP-B-ASSY',"
            "'LAMP-OP-B-TEST','LAMP-OP-SENSOR')",
        )
        inspection_codes = {"LAMP-OP-H-TEST", "LAMP-OP-A-CHECK", "LAMP-OP-B-TEST", "LAMP-OP-SENSOR"}
        for operation in operations:
            station_type = "INSPECTION" if operation["operation_code"] in inspection_codes else "PRODUCTION"
            station_id = by_type.get(station_type)
            if station_id is None:
                raise RuntimeError(f"整机线缺少可用的 {station_type} 工位，无法绑定组件工序")
            if run.rows(
                "factory_station_operation",
                f"station_id={station_id} AND operation_id={operation['operation_id']}" " AND deleted_flag=0",
            ):
                continue
            run.api(
                "POST",
                "/api/factory/station-operation",
                {
                    "stationId": station_id,
                    "operationId": operation["operation_id"],
                    "isDefault": 1,
                    "status": "ENABLE",
                    "remark": NOTE,
                },
                key=f"demo-station-op-{station_id}-{operation['operation_id']}",
            )

    def prepare(self):
        run = self.run
        run.login()
        self.ensure_teams()
        run.ensure_schedules(datetime.date.today().isoformat())
        product = run.one("mdm_product", "product_code='LAMP-L200' AND deleted_flag=0")
        customer = run.rows("mdm_customer", "deleted_flag=0 ORDER BY customer_id")[0]
        tag = NOTE + "；" + run.args.run_key
        if not run.rows("mfg_production_order", f"remark='{tag}' AND deleted_flag=0"):
            run.api(
                "POST",
                "/api/mfg/production-order",
                {
                    "customerId": customer["customer_id"],
                    "productId": product["product_id"],
                    "orderQuantity": 2,
                    "deliveryDate": (datetime.date.today() + datetime.timedelta(days=10)).isoformat(),
                    "sourceType": "MANUAL",
                    "remark": tag,
                },
                key="demo-production-order",
            )
        order = run.one("mfg_production_order", f"remark='{tag}' AND deleted_flag=0")
        oid = order["production_order_id"]
        if not run.rows("mfg_production_plan", f"production_order_id={oid} AND deleted_flag=0"):
            draft = run.api("POST", "/api/mfg/schedule-scenario/manual-draft/open")
            choices = run.api("GET", f"/api/mfg/schedule-scenario/{draft['scenarioId']}/orders/{oid}/eligible-lines")
            if not choices:
                raise RuntimeError("演示订单没有候选产线")
            item = run.api(
                "POST",
                f"/api/mfg/schedule-scenario/{draft['scenarioId']}/items",
                {"scenarioVersion": draft["version"], "productionOrderId": oid, "lineId": choices[0]["lineId"]},
                key="demo-schedule-item",
            )
            current = run.api("GET", f"/api/mfg/schedule-scenario/{draft['scenarioId']}")
            run.api(
                "PUT",
                f"/api/mfg/schedule-scenario/{draft['scenarioId']}" f"/items/{item['scheduleItemId']}/confirm",
                {"scenarioVersion": current["version"], "itemVersion": item["version"], "remark": NOTE},
                key="demo-schedule-confirm",
            )
        plan = run.one("mfg_production_plan", f"production_order_id={oid} AND plan_type='ROOT' AND deleted_flag=0")
        run.ensure_schedules(plan["planned_start_time"][:10])
        if not run.rows("mfg_work_order", f"plan_id={plan['plan_id']} AND deleted_flag=0"):
            route = run.one(
                "process_route", f"product_id={product['product_id']} AND status='RELEASED' AND deleted_flag=0"
            )
            bom = run.one("mdm_bom", "bom_code='LAMP-BOM-L200-V1'")
            run.api(
                "POST",
                f"/api/mfg/work-order/generate/{plan['plan_id']}",
                {
                    "bomId": bom["bom_id"],
                    "routeId": route["route_id"],
                    "remark": NOTE,
                    "requestKey": run.args.run_key + "-generate",
                },
                key="demo-generate",
            )
        root = run.one("mfg_work_order", f"plan_id={plan['plan_id']} AND work_order_type='ROOT' AND deleted_flag=0")
        children = run.rows(
            "mfg_work_order",
            f"root_work_order_id={root['work_order_id']}" " AND work_order_type='COMPONENT' AND deleted_flag=0",
        )
        self.bind_stations(plan["line_id"])
        user = run.one("sys_user", "username='admin' AND deleted_flag=0")
        previous = user["employee_id"]
        try:
            for work in children:
                run.execute_work_order(work["work_order_id"])
        finally:
            run.sql(
                "UPDATE sys_user SET employee_id="
                f"{'NULL' if previous is None else int(previous)}"
                f" WHERE user_id={user['user_id']}"
            )
        write_json(
                {
                    "productionOrderNo": order["production_order_no"],
                    "rootWorkOrderNo": root["work_order_no"],
                    "componentWorkOrders": [w["work_order_no"] for w in children],
                    "rootStatus": run.one("mfg_work_order", f"work_order_id={root['work_order_id']}")["status"],
                    "nextStep": "现场从「生成领料单」开始，再转线边、报工、检验、成品入库、销售发货、退料",
                },
                ensure_ascii=False,
                indent=2,
            )


def open_andon_stations(run):
    """当前未关闭（待处理/处置中）的安灯工位集合。

    挂着未关闭安灯的工位不能开工，绑定组件工序时要避开它们。
    """
    station_ids = set()
    for row in run.rows("mfg_andon_alert", "deleted_flag=0 AND status IN ('ACTIVE','PROCESSING')"):
        station_ids.add(row["station_id"])
    return station_ids


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", required=True)
    parser.add_argument("--port", type=int, choices=[3307, 3310, 3311], default=3311)
    parser.add_argument("--api-base", default="http://127.0.0.1:8089")
    parser.add_argument("--run-key", default="lamp-demo-prep")
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    if not re.fullmatch("mes_lamp_(clean|order_e2e)[A-Za-z0-9_]*", args.database):
        parser.error("仅允许本机台灯演练库或演示库")
    if not re.fullmatch("lamp-demo-[A-Za-z0-9_-]+", args.run_key):
        parser.error("run-key 必须以 lamp-demo- 开头")
    if not args.apply:
        write_json({"database": args.database, "preview": True, "remark": NOTE}, ensure_ascii=False)
        return
    module = load_rehearsal()
    run = module.Rehearsal(args)
    if run.sql('SELECT CONCAT(DATABASE(),":",@@port)') != args.database + ":" + str(args.port):
        raise RuntimeError("数据库身份不匹配")
    DemoPreparation(run).prepare()


if __name__ == "__main__":
    main()
