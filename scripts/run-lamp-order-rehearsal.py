"""在已授权的本机纯台灯库演练10台L200；业务单据走正式API，所有结果均为演练输入。"""

import argparse
from decimal import Decimal
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.error
import urllib.request


def local_now():
    """当前本地时间：显式取带时区的时间再去掉时区，不依赖进程默认时区配置。"""
    return datetime.now(tz=timezone.utc).astimezone().replace(tzinfo=None)


ROOT = Path(__file__).resolve().parents[1]
NOTE = "课程演练输入，非实物测量或真实到货；老化及排产工时以当前配置演练，非最终业务确认"


class Rehearsal:
    def __init__(self, args):
        self.args = args
        self.directory = ROOT / "tmp" / args.run_key
        self.directory.mkdir(parents=True, exist_ok=True)
        self.file = self.directory / "result.json"
        self.state = (
            json.loads(self.file.read_text(encoding="utf-8"))
            if self.file.exists()
            else {"database": args.database, "runKey": args.run_key, "remark": NOTE, "actions": {}, "stages": []}
        )
        if self.state["database"] != args.database:
            raise RuntimeError("演练记录的目标库不一致")
        self.binary = Path(os.environ.get("MYSQL_BIN", r"C:\Program Files\MySQL\MySQL Server 8.0\bin"))
        self.connection = [
            "--no-defaults",
            "--protocol=TCP",
            "--host=127.0.0.1",
            "--port=" + str(args.port),
            "--user=" + os.environ.get("MYSQL_USER", "root"),
            "--default-character-set=utf8mb4",
        ]
        self.token = None
        self.api_base = args.api_base.rstrip("/")

    def save(self):
        self.file.write_text(json.dumps(self.state, ensure_ascii=False, indent=2), encoding="utf-8")

    def sql(self, text):
        result = subprocess.run(
            [
                str(self.binary / "mysql.exe"),
                *self.connection,
                "--database=" + self.args.database,
                "--batch",
                "--raw",
                "--skip-column-names",
            ],
            input=text,
            text=True,
            encoding="utf-8",
            capture_output=True,
            timeout=120,
        )
        if result.returncode:
            raise RuntimeError(result.stderr.strip())
        return result.stdout.strip()

    def rows(self, table, where="1=1"):
        if not re.fullmatch("[a-z_]+", table):
            raise ValueError(table)
        columns = self.sql(
            "SELECT column_name FROM information_schema.columns "
            "WHERE table_schema=DATABASE() "
            f"AND table_name='{table}' ORDER BY ordinal_position"
        ).splitlines()
        fields = ",".join("'" + c + "',`" + c + "`" for c in columns)
        payload = self.sql(f"SELECT JSON_OBJECT({fields}) FROM `{table}` WHERE {where}")
        return [json.loads(line) for line in payload.splitlines()]

    def open_andon_stations(self):
        """当前未关闭（待处理/处置中）的安灯工位集合。"""
        station_ids = set()
        for row in self.rows("mfg_andon_alert", "deleted_flag=0 AND status IN ('ACTIVE','PROCESSING')"):
            station_ids.add(row["station_id"])
        return station_ids

    def one(self, table, where):
        rows = self.rows(table, where)
        if len(rows) != 1:
            raise RuntimeError(f"{table}期望1行，实际{len(rows)}：{where}")
        return rows[0]

    def api(self, method, path, body=None, key=None):
        if key and key in self.state["actions"]:
            return self.state["actions"][key]
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(
            self.api_base + path,
            data=data,
            method=method,
            headers={
                "Content-Type": "application/json",
                **({"Authorization": "Bearer " + self.token} if self.token else {}),
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                result = json.load(response)
        except urllib.error.HTTPError as error:
            result = json.loads(error.read().decode("utf-8"))
        if result.get("code") != 200:
            raise RuntimeError(f"{method} {path}: " + json.dumps(result, ensure_ascii=False))
        if path.endswith("/login"):
            self.token = result["token"]
            return result.get("data")
        result = result.get("data")
        if key:
            self.state["actions"][key] = result
            self.save()
            print("完成：" + key, flush=True)
        return result

    def login(self):
        self.api(
            "POST",
            "/api/auth/login",
            {"username": os.environ["RUNTIME_LOGIN_USER"], "password": os.environ["RUNTIME_LOGIN_PASSWORD"]},
        )

    def backup(self):
        if self.state.get("backup"):
            return
        backup = self.directory / "before-rehearsal.sql"
        if backup.exists():
            raise RuntimeError("备份存在但没有完成记录，拒绝覆盖")
        result = subprocess.run(
            [
                str(self.binary / "mysqldump.exe"),
                *self.connection,
                "--single-transaction",
                "--no-tablespaces",
                "--set-gtid-purged=OFF",
                "--result-file=" + str(backup),
                self.args.database,
            ],
            capture_output=True,
            timeout=120,
        )
        if result.returncode or b"-- Dump completed" not in backup.read_bytes()[-500:]:
            raise RuntimeError("备份未完成")
        self.state["backup"] = {
            "path": str(backup.relative_to(ROOT)),
            "sha256": hashlib.sha256(backup.read_bytes()).hexdigest(),
        }
        self.save()

    def finish_inspection(self, inspection_id):
        inspection = self.one("qms_inspection_order", f"inspection_id={inspection_id}")
        if inspection["status"] == "FINISHED":
            return
        if not inspection["scheme_id"]:
            scheme = self.one("qms_inspection_scheme", "scheme_code='LAMP-V18-QS-L200-IQC' AND deleted_flag=0")
            self.api(
                "PUT",
                "/api/qms/inspection-order",
                {
                    "inspectionId": inspection_id,
                    "version": inspection["version"],
                    "schemeId": scheme["scheme_id"],
                    "remark": NOTE,
                },
                key=f"inspection-scheme-{inspection_id}",
            )
            inspection = self.one("qms_inspection_order", f"inspection_id={inspection_id}")
        inspection_items = []
        for item in self.rows("qms_inspection_scheme_item", f"scheme_id={inspection['scheme_id']} AND deleted_flag=0"):
            standard = self.one("qms_inspection_standard", f"standard_id={item['standard_id']}")
            inspection_items.append((item["item_id"], standard["standard_value"]))
        results = []
        quantity = int(inspection["sample_quantity"])
        if inspection["report_id"]:
            report = self.one("mfg_work_report", f"report_id={inspection['report_id']}")
            quantity = int(report["qualified_quantity"] + report["defect_quantity"])
        elif inspection["inspection_type"] == "OUTGOING":
            quantity = 10
        if quantity <= 0:
            raise RuntimeError(f"检验单{inspection_id}缺少实际待检数量")
        for number in range(1, quantity + 1):
            for item_id, value in inspection_items:
                results.append(
                    {
                        "pieceNo": f"REH-{inspection_id}-{number:03d}",
                        "itemId": item_id,
                        "measuredValue": value,
                        "result": "PASS",
                        "remark": NOTE,
                    }
                )
        self.api(
            "PUT",
            f"/api/qms/inspection-order/{inspection_id}/submit-piece",
            {"results": results, "remark": NOTE},
            key=f"inspection-pass-{inspection_id}",
        )

    def purchase(self):
        if "prepared" not in self.state["stages"]:
            raise RuntimeError("先完成prepare")
        self.login()
        demand = {}

        def expand(bom_id, quantity):
            for item in self.rows("mdm_bom_item", f"bom_id={bom_id} AND deleted_flag=0"):
                amount = quantity * Decimal(str(item["quantity"]))
                if item["child_bom_id"]:
                    expand(item["child_bom_id"], amount)
                else:
                    demand[item["material_id"]] = demand.get(item["material_id"], Decimal(0)) + amount

        expand(self.state["rootBomId"], Decimal(10))
        warehouse = self.one("wms_warehouse", "warehouse_type='RAW' AND deleted_flag=0")
        location = self.rows("wms_location", f"warehouse_id={warehouse['warehouse_id']} AND deleted_flag=0")[0]
        for mid, amount in demand.items():
            code = f"LAMP-REH-20261025-M{mid}"
            if not self.rows("wms_material_batch", f"batch_no='{code}'"):
                self.api(
                    "POST",
                    "/api/wms/material-batch",
                    {"batchNo": code, "materialId": mid, "receivedDate": "2026-10-25", "remark": NOTE},
                    key=f"batch-{mid}",
                )
            batch = self.one("wms_material_batch", f"batch_no='{code}'")
            created = self.api(
                "POST",
                "/api/wms/inbound-order/full",
                {
                    "inboundNo": "IN-" + code,
                    "inboundType": "PURCHASE",
                    "warehouseId": warehouse["warehouse_id"],
                    "inboundTime": local_now().isoformat(timespec="seconds"),
                    "remark": NOTE,
                    "details": [
                        {
                            "materialId": mid,
                            "batchId": batch["batch_id"],
                            "locationId": location["location_id"],
                            "inboundQuantity": int(amount),
                            "remark": NOTE,
                        }
                    ],
                },
                key=f"purchase-{mid}",
            )
            # 单号由服务端统一生成（短单号规则），不能拿客户端传的 inboundNo 反查，
            # 只能按返回的 inboundId 定位这一行。
            inbound = self.one("wms_inbound_order", f"inbound_id={created['inboundId']}")
            if not self.rows(
                "qms_inspection_order",
                f"batch_id={batch['batch_id']} AND inspection_type='INCOMING' AND deleted_flag=0",
            ):
                scheme = self.one("qms_inspection_scheme", "scheme_code='LAMP-V18-QS-L200-IQC' AND deleted_flag=0")
                self.api(
                    "POST",
                    "/api/qms/inspection-order",
                    {
                        "inspectionType": "INCOMING",
                        "schemeId": scheme["scheme_id"],
                        "batchId": batch["batch_id"],
                        "sampleQuantity": int(amount),
                        "remark": NOTE,
                    },
                    key=f"incoming-{mid}",
                )
            inspection = self.one(
                "qms_inspection_order",
                f"batch_id={batch['batch_id']} AND inspection_type='INCOMING' AND deleted_flag=0",
            )
            self.finish_inspection(inspection["inspection_id"])
            self.api(
                "PUT",
                f"/api/wms/inbound-order/{inbound['inbound_id']}/confirm",
                {"remark": NOTE},
                key=f"purchase-confirm-{mid}",
            )
        self.state["rawDemand"] = {str(mid): int(value) for mid, value in demand.items()}
        self.state["stages"].append("purchased") if "purchased" not in self.state["stages"] else None
        self.save()

    def production(self):
        if "purchased" not in self.state["stages"]:
            raise RuntimeError("先完成purchase")
        self.login()
        code = "LAMP-REH-CUSTOMER"
        if not self.rows("mdm_customer", f"customer_code='{code}' AND deleted_flag=0"):
            self.api(
                "POST",
                "/api/mdm/customer",
                {"customerCode": code, "customerName": "台灯演练客户（虚拟）", "status": "ENABLE", "remark": NOTE},
                key="customer",
            )
        customer = self.one("mdm_customer", f"customer_code='{code}' AND deleted_flag=0")
        product = self.one("mdm_product", "product_code='LAMP-L200' AND deleted_flag=0")
        tag = NOTE + "；" + self.args.run_key
        if not self.rows("mfg_production_order", f"remark='{tag}' AND deleted_flag=0"):
            self.api(
                "POST",
                "/api/mfg/production-order",
                {
                    "customerId": customer["customer_id"],
                    "productId": product["product_id"],
                    "orderQuantity": 10,
                    "deliveryDate": (local_now().date() + timedelta(days=10)).isoformat(),
                    "sourceType": "MANUAL",
                    "remark": tag,
                },
                key="production-order",
            )
        order = self.one("mfg_production_order", f"remark='{tag}' AND deleted_flag=0")
        self.state["productionOrderId"] = order["production_order_id"]
        self.save()
        oid = order["production_order_id"]
        if not self.rows("mfg_production_plan", f"production_order_id={oid} AND deleted_flag=0"):
            draft = self.api("POST", "/api/mfg/schedule-scenario/manual-draft/open")
            choices = self.api("GET", f"/api/mfg/schedule-scenario/{draft['scenarioId']}/orders/{oid}/eligible-lines")
            if not choices:
                raise RuntimeError("演练订单没有候选产线")
            item = self.api(
                "POST",
                f"/api/mfg/schedule-scenario/{draft['scenarioId']}/items",
                {"scenarioVersion": draft["version"], "productionOrderId": oid, "lineId": choices[0]["lineId"]},
                key="schedule-item",
            )
            item = self.align_schedule_window(draft["scenarioId"], item, choices[0]["lineId"])
            current = self.api("GET", f"/api/mfg/schedule-scenario/{draft['scenarioId']}")
            self.api(
                "PUT",
                f"/api/mfg/schedule-scenario/{draft['scenarioId']}" f"/items/{item['scheduleItemId']}/confirm",
                {"scenarioVersion": current["version"], "itemVersion": item["version"], "remark": NOTE},
                key="schedule-confirm",
            )
        plan = self.one("mfg_production_plan", f"production_order_id={oid} AND plan_type='ROOT' AND deleted_flag=0")
        self.state["planId"] = plan["plan_id"]
        self.save()
        self.ensure_schedules(plan["planned_start_time"][:10])
        if not self.rows("mfg_work_order", f"plan_id={plan['plan_id']} AND deleted_flag=0"):
            route = self.one(
                "process_route", f"product_id={product['product_id']} AND status='RELEASED' AND deleted_flag=0"
            )
            self.api(
                "POST",
                f"/api/mfg/work-order/generate/{plan['plan_id']}",
                {
                    "bomId": self.state["rootBomId"],
                    "routeId": route["route_id"],
                    "remark": NOTE,
                    "requestKey": self.args.run_key + "-generate",
                },
                key="generate-work-orders",
            )
        root = self.one("mfg_work_order", f"plan_id={plan['plan_id']} AND work_order_type='ROOT' AND deleted_flag=0")
        self.state["rootWorkOrderId"] = root["work_order_id"]
        self.save()
        children = self.rows(
            "mfg_work_order",
            f"root_work_order_id={root['work_order_id']}" " AND work_order_type='COMPONENT' AND deleted_flag=0",
        )
        user = self.one("sys_user", "username='admin' AND deleted_flag=0")
        if "originalAdminEmployeeId" not in self.state:
            self.state["originalAdminEmployeeId"] = user["employee_id"]
            self.save()
        # 整机工单预留一小批防滑脚垫不投料，留出可退回的余量。
        cushion = self.one("mdm_material", "material_code='LAMP-M17' AND deleted_flag=0")
        reserve = {cushion["material_id"]: 8}
        self.state["reservedForReturn"] = {str(key): float(value) for key, value in reserve.items()}
        self.save()
        try:
            for work in [*children, root]:
                leave = reserve if work["work_order_id"] == root["work_order_id"] else None
                self.execute_work_order(work["work_order_id"], leave_back=leave)
        finally:
            previous = self.state["originalAdminEmployeeId"]
            self.sql(
                "UPDATE sys_user SET employee_id="
                f"{'NULL' if previous is None else int(previous)}"
                f" WHERE user_id={user['user_id']}"
            )
        self.state["stages"].append("produced") if "produced" not in self.state["stages"] else None
        self.save()

    def align_schedule_window(self, scenario_id, item, line_id, lead_hours=0, avoid=None):
        """把排程草案条目挪到下一段班次窗口里。

        草稿条目默认取「最早可用时刻」，也就是当前这一刻，而确认与下达都要求
        计划开始时间晚于现在（后端留 2 分钟容差）。演练的 prepare/purchase 阶段
        要花几分钟，等走到这一步时草稿窗口往往已经落到过去，下达工单就会被
        「生产计划时间已过期」拦下。这里按真实班次表把开始时间对齐到未来的
        班次开端，再用工时预览拿到结束时间，等价于排产员在看板上手动拖一下。
        """
        now = local_now().replace(second=0, microsecond=0) + timedelta(minutes=6, hours=lead_hours)
        starts = []
        shifts = self.rows("org_shift", "shift_code LIKE 'LAMP-%' AND deleted_flag=0")
        for day in range(0, 4):
            date = (local_now() + timedelta(days=day)).date()
            for shift in shifts:
                hour, minute = (int(part) for part in str(shift["start_time"])[:5].split(":"))
                start = datetime.combine(date, datetime.min.time()).replace(hour=hour, minute=minute)
                if start > now:
                    starts.append(start)
        starts.sort()
        if not starts:
            raise RuntimeError("未来几天内没有可用的班次窗口")
        last_error = None
        blocked = [(a, b) for a, b in (avoid or []) if a and b]
        for start in starts[:12]:
            payload = start.strftime("%Y-%m-%dT%H:%M:%S")
            try:
                preview = self.api(
                    "GET",
                    f"/api/mfg/schedule-scenario/{scenario_id}/items/"
                    f"{item['scheduleItemId']}/duration-preview"
                    f"?lineId={line_id}&plannedStartTime={payload}",
                )
            except RuntimeError as error:
                last_error = error
                continue
            planned_end = str(preview["endTime"]).replace(" ", "T")[:19]
            if any(start < b and a < datetime.strptime(planned_end, "%Y-%m-%dT%H:%M:%S") for a, b in blocked):
                continue
            current = self.api("GET", f"/api/mfg/schedule-scenario/{scenario_id}")
            placed = self.api(
                "PUT",
                f"/api/mfg/schedule-scenario/{scenario_id}" f"/items/{item['scheduleItemId']}/placement",
                {
                    "scenarioVersion": current["version"],
                    "itemVersion": item["version"],
                    "lineId": line_id,
                    "plannedStartTime": payload,
                    "plannedEndTime": planned_end,
                    "remark": NOTE,
                },
            )
            print("排程窗口对齐：" + payload + " ~ " + planned_end, flush=True)
            return placed
        raise RuntimeError("排程窗口无法对齐到未来班次：" + str(last_error))

    def ensure_schedules(self, date):
        for line in self.rows("factory_line", "line_code LIKE 'LAMP-%' AND deleted_flag=0"):
            suffix = line["line_code"].replace("LAMP-LINE-", "")
            team = self.state["teams"][suffix]
            for shift in self.rows("org_shift", "shift_code LIKE 'LAMP-%' AND deleted_flag=0"):
                where = (
                    f"team_id={team} AND line_id={line['line_id']}"
                    f" AND shift_id={shift['shift_id']}"
                    f" AND work_date='{date}' AND deleted_flag=0"
                )
                existing = self.rows("org_team_schedule", where)
                if not existing:
                    self.api(
                        "POST",
                        "/api/org/team-schedule",
                        {
                            "teamId": team,
                            "lineId": line["line_id"],
                            "shiftId": shift["shift_id"],
                            "workDate": date,
                            "plannedHeadcount": 1,
                            "remark": NOTE,
                        },
                        key=f"schedule-{date}-{suffix}-{shift['shift_id']}",
                    )
                schedule = self.one("org_team_schedule", where)
                if schedule["status"] == "DRAFT":
                    self.api(
                        "PUT",
                        f"/api/org/team-schedule/{schedule['schedule_id']}/release",
                        {},
                        key=f"schedule-release-{schedule['schedule_id']}",
                    )

    def execute_work_order(self, wid, leave_back=None):
        """执行一张工单；leave_back 指定「领了但先不投料」的物料数量，用于制造可退回的余量。"""
        leave_back = leave_back or {}
        work = self.one("mfg_work_order", f"work_order_id={wid}")
        plan = self.one("mfg_production_plan", f"plan_id={work['plan_id']}")
        self.ensure_schedules(plan["planned_start_time"][:10])
        if plan["status"] == "DRAFT":
            self.api(
                "PUT",
                f"/api/mfg/production-plan/{plan['plan_id']}/release",
                {"remark": NOTE},
                key=f"plan-release-{wid}",
            )
        if work["status"] in ["DRAFT", "APPROVED"]:
            self.api("PUT", f"/api/mfg/work-order/{wid}/release", {"remark": NOTE}, key=f"work-release-{wid}")
        warehouse = self.one("wms_warehouse", "warehouse_type='RAW' AND deleted_flag=0")
        issue = self.api(
            "POST",
            f"/api/mfg/work-order/{wid}/generate-material-issue",
            {
                "warehouseId": warehouse["warehouse_id"],
                "remark": NOTE,
                "requestKey": f"{self.args.run_key}-issue-{wid}",
            },
            key=f"issue-{wid}",
        )
        line_side = self.one("wms_warehouse", "warehouse_type='LINE_SIDE' AND deleted_flag=0")
        line_location = self.rows("wms_location", f"warehouse_id={line_side['warehouse_id']} AND deleted_flag=0")[0]
        self.api(
            "PUT",
            f"/api/wms/outbound-order/{issue['outboundId']}/transfer-to-line-side",
            {
                "targetLocationId": line_location["location_id"],
                "remark": NOTE,
                "requestKey": f"{self.args.run_key}-transfer-{wid}",
            },
            key=f"transfer-{wid}",
        )
        line = self.one("factory_line", f"line_id={plan['line_id']}")
        suffix = line["line_code"].replace("LAMP-LINE-", "")
        employee = self.one("org_employee", f"employee_no='LAMP-REH-EMP-{suffix}'")
        self.sql(
            "UPDATE sys_user SET employee_id=" f"{employee['employee_id']} WHERE username='admin' AND deleted_flag=0"
        )
        dispatch = self.api(
            "POST",
            f"/api/mfg/dispatch-order/generate/{wid}",
            {
                "teamId": self.state["teams"][suffix],
                "dueTime": plan["planned_end_time"].replace(" ", "T"),
                "remark": NOTE,
            },
            key=f"dispatch-{wid}",
        )
        self.api(
            "PUT",
            f"/api/mfg/dispatch-order/{dispatch['dispatchId']}/release",
            {"remark": NOTE},
            key=f"dispatch-release-{wid}",
        )
        steps = self.rows("process_route_item", f"route_id={work['route_id']} AND deleted_flag=0 ORDER BY sequence_no")
        for step in steps:
            task = self.one(
                "mfg_task", f"work_order_id={wid} AND operation_id={step['operation_id']} AND deleted_flag=0"
            )
            tid = task["task_id"]
            if task["status"] == "FINISHED":
                continue
            bindings = self.rows(
                "factory_station_operation",
                f"operation_id={step['operation_id']}" " AND deleted_flag=0 AND status='ENABLE'",
            )
            candidates = [self.one("factory_station", f"station_id={b['station_id']}") for b in bindings]
            candidates = [c for c in candidates if c["line_id"] == plan["line_id"]]
            # 挂着未关闭安灯的工位开不了工；那是安灯处置要用的演示数据，不能为了跑演练去关它，
            # 所以优先换到本线其它没有未关闭安灯的同类型工位。
            open_andon = self.open_andon_stations()
            station = next(
                (c for c in candidates if c["station_id"] not in open_andon), candidates[0] if candidates else None
            )
            if station is None:
                raise RuntimeError(f'工单{wid}工序{step["operation_id"]}在本线没有可用工位')
            self.api("PUT", f"/api/mfg/task/{tid}/start", {"stationId": station["station_id"]}, key=f"task-start-{tid}")
            materials = []
            for item in self.rows(
                "mdm_bom_item",
                f"bom_id={work['bom_id']} AND consumption_operation_id={step['operation_id']}" " AND deleted_flag=0",
            ):
                inventories = self.rows(
                    "wms_inventory",
                    f"work_order_id={wid} AND material_id={item['material_id']}"
                    " AND locked_quantity>0 AND deleted_flag=0",
                )
                left = Decimal(str(item["quantity"])) * Decimal(str(work["planned_quantity"]))
                # 预留数量留在线边不投料，用于后续按正式流程办理生产退料。
                hold = min(Decimal(str(leave_back.get(item["material_id"], 0))), left)
                left -= hold
                for inventory in inventories:
                    take = min(left, Decimal(str(inventory["locked_quantity"])))
                    if take > 0:
                        materials.append(
                            {
                                "materialId": item["material_id"],
                                "batchId": inventory["batch_id"],
                                "usedQuantity": float(take),
                                "scrapQuantity": 0,
                                "remark": NOTE,
                            }
                        )
                        left -= take
                if left > 0:
                    raise RuntimeError(f'工单{wid}物料{item["material_id"]}线边不足{left}')
            minutes = (
                Decimal(str(step["batch_duration_minutes"]))
                if step["resource_type"] == "BATCH_EQUIPMENT"
                else Decimal(str(step["standard_time"])) * Decimal(str(work["planned_quantity"]))
            )
            report = self.api(
                "POST",
                "/api/mfg/work-report/full",
                {
                    "taskId": tid,
                    "operatorId": employee["employee_id"],
                    "stationId": station["station_id"],
                    "qualifiedQuantity": work["planned_quantity"],
                    "defectQuantity": 0,
                    "actualWorkHours": float(minutes / 60),
                    "materials": materials,
                    "remark": NOTE,
                },
                key=f"report-{tid}",
            )
            self.api(
                "PUT",
                f"/api/mfg/work-report/{report['reportId']}/audit",
                {"auditResult": "APPROVED", "auditComment": NOTE},
                key=f"report-audit-{tid}",
            )
            for inspection in self.rows("qms_inspection_order", f"report_id={report['reportId']} AND deleted_flag=0"):
                self.finish_inspection(inspection["inspection_id"])
        for inspection in self.rows(
            "qms_inspection_order", f"work_order_id={wid} AND deleted_flag=0 AND status='DRAFT'"
        ):
            self.finish_inspection(inspection["inspection_id"])
        for lot in self.rows("trace_product_lot", f"work_order_id={wid} AND deleted_flag=0"):
            warehouse = self.one(
                "wms_warehouse",
                "warehouse_type='"
                + ("FINISHED" if work["work_order_type"] == "ROOT" else "SEMI_FINISHED")
                + "' AND deleted_flag=0",
            )
            location = self.rows("wms_location", f"warehouse_id={warehouse['warehouse_id']} AND deleted_flag=0")[0]
            inbound = self.api(
                "POST",
                f"/api/wms/inbound-order/generate-production/{lot['product_lot_id']}",
                {"warehouseId": warehouse["warehouse_id"], "locationId": location["location_id"], "remark": NOTE},
                key=f"production-inbound-{lot['product_lot_id']}",
            )
            self.api(
                "PUT",
                f"/api/wms/inbound-order/{inbound['inboundId']}/confirm",
                {"remark": NOTE},
                key=f"production-inbound-confirm-{lot['product_lot_id']}",
            )

    def ship(self):
        if "produced" not in self.state["stages"]:
            raise RuntimeError("先完成production")
        self.login()
        order = self.one("mfg_production_order", f"production_order_id={self.state['productionOrderId']}")
        warehouse = self.one("wms_warehouse", "warehouse_type='FINISHED' AND deleted_flag=0")
        location = self.rows("wms_location", f"warehouse_id={warehouse['warehouse_id']} AND deleted_flag=0")[0]
        outbound = self.api(
            "POST",
            "/api/wms/sales-outbound/generate",
            {
                "productionOrderId": order["production_order_id"],
                "customerId": order["customer_id"],
                "warehouseId": warehouse["warehouse_id"],
                "locationId": location["location_id"],
                "remark": NOTE,
            },
            key="sales-outbound",
        )
        self.state["salesOutboundId"] = outbound["outboundId"]
        self.save()
        inspection = self.api(
            "POST", f"/api/wms/sales-outbound/{outbound['outboundId']}/inspection", {}, key="outgoing-inspection"
        )
        self.finish_inspection(inspection["inspectionId"])
        self.api(
            "PUT", f"/api/wms/sales-outbound/{outbound['outboundId']}/confirm", {"remark": NOTE}, key="sales-confirm"
        )
        self.state["stages"].append("shipped") if "shipped" not in self.state["stages"] else None
        self.save()

    def returns(self):
        """把整机工单预留未投料的物料按正式生产退料流程退回原料仓。"""
        if "produced" not in self.state["stages"]:
            raise RuntimeError("先完成production")
        self.login()
        root = self.state["rootWorkOrderId"]
        reserved = self.state.get("reservedForReturn") or {}
        if not reserved:
            raise RuntimeError("本次演练没有预留未投料物料，无法办理退料")
        issue = self.one(
            "wms_outbound_order",
            f"work_order_id={root} AND outbound_type='MATERIAL_ISSUE'"
            " AND audit_status='APPROVED' AND deleted_flag=0",
        )
        details = []
        for material_id, quantity in reserved.items():
            detail = self.one(
                "wms_outbound_detail",
                f"outbound_id={issue['outbound_id']} AND material_id={material_id}" " AND deleted_flag=0",
            )
            details.append(
                {
                    "outboundDetailId": detail["detail_id"],
                    "returnQuantity": quantity,
                    "returnReason": "整机工单预留未投料，按生产退料退回原料仓；" + NOTE,
                }
            )
        created = self.api(
            "POST",
            "/api/wms/return-order/full",
            {"workOrderId": root, "returnType": "GOOD", "remark": NOTE, "details": details},
            key="return-order",
        )
        return_id = created["returnId"]
        self.api("PUT", f"/api/wms/return-order/{return_id}/confirm", {"remark": NOTE}, key="return-confirm")
        order = self.one("wms_return_order", f"return_id={return_id}")
        if order["audit_status"] != "APPROVED":
            raise RuntimeError("退料单未确认入库：" + str(order["audit_status"]))
        movements = self.rows(
            "wms_stock_movement", f"source_order_type='RETURN_ORDER' AND source_order_id={return_id} AND deleted_flag=0"
        )
        self.state["returnOrder"] = {
            "returnId": return_id,
            "returnNo": order["return_no"],
            "auditStatus": order["audit_status"],
            "details": details,
            "movements": [
                {
                    "movementId": row["movement_id"],
                    "direction": row["direction"],
                    "quantity": row["quantity"],
                    "pairNo": row["pair_no"],
                }
                for row in movements
            ],
        }
        self.state["stages"].append("returned") if "returned" not in self.state["stages"] else None
        self.save()

    def repair_links(self):
        """修复本次演练既有流水的缺失配对号，不改数量，不新造库存或流水。"""
        root = self.state["rootWorkOrderId"]
        work = self.rows("mfg_work_order", f"(work_order_id={root} OR root_work_order_id={root}) AND deleted_flag=0")
        ids = ",".join(str(row["work_order_id"]) for row in work)
        corrections = []
        for source in self.rows(
            "wms_stock_movement",
            f"work_order_id IN ({ids}) AND direction='OUT'" " AND pair_no LIKE 'LI-%' AND deleted_flag=0",
        ):
            receipts = self.rows(
                "wms_stock_movement",
                f"source_order_type='INBOUND_ORDER' AND source_order_id={source['source_order_id']}"
                f" AND direction='IN' AND material_id={source['material_id']}"
                f" AND batch_id={source['batch_id']} AND quantity={source['quantity']}"
                " AND deleted_flag=0",
            )
            if len(receipts) != 1:
                raise RuntimeError("不能唯一核实生产转仓对应入库流水，拒绝修复")
            target = receipts[0]
            if target["pair_no"] == source["pair_no"]:
                continue
            if target["pair_no"] is not None:
                raise RuntimeError("入库流水已有其他配对号，拒绝覆盖")
            corrections.append({"movementId": target["movement_id"], "pairNo": source["pair_no"]})
        if corrections:
            backup = self.directory / "before-pair-link-repair.sql"
            if backup.exists():
                raise RuntimeError("修复备份已存在，请先核查之前的结果")
            result = subprocess.run(
                [
                    str(self.binary / "mysqldump.exe"),
                    *self.connection,
                    "--single-transaction",
                    "--no-tablespaces",
                    "--set-gtid-purged=OFF",
                    "--result-file=" + str(backup),
                    self.args.database,
                ],
                capture_output=True,
                timeout=120,
            )
            if result.returncode or b"-- Dump completed" not in backup.read_bytes()[-500:]:
                raise RuntimeError("配对修复备份失败")
            statements = ["START TRANSACTION"]
            for row in corrections:
                if not re.fullmatch("LI-[0-9]+-[0-9]+", row["pairNo"]):
                    raise RuntimeError("异常配对号")
                statements.append(
                    "UPDATE wms_stock_movement SET pair_no="
                    f"'{row['pairNo']}',version=version+1,updated_time=NOW()"
                    f" WHERE movement_id={row['movementId']} AND pair_no IS NULL"
                )
            statements.extend(
                [
                    "INSERT INTO sys_operation_log "
                    "(user_id,module_code,operation_type,remark,operation_time,deleted_flag) "
                    "SELECT user_id,'WMS_INBOUND_ORDER','REPAIR_TRANSFER_LINK',"
                    "'工程演练对账：仅补齐已存在的生产入库流水配对号，数量与余额未改',NOW(),0 "
                    "FROM sys_user WHERE username='admin' AND deleted_flag=0",
                    "COMMIT",
                ]
            )
            self.sql(";".join(statements) + ";")
            self.state["receiptLinkRepair"] = {
                "rows": corrections,
                "backup": str(backup.relative_to(ROOT)),
                "backupSha256": hashlib.sha256(backup.read_bytes()).hexdigest(),
            }
            self.save()
        print("已核对生产入库流水配对，补齐条数：" + str(len(corrections)))

    def audit(self):
        if "shipped" not in self.state["stages"]:
            raise RuntimeError("先完成ship")
        if "returned" not in self.state["stages"]:
            raise RuntimeError("先完成returns")
        root = self.state["rootWorkOrderId"]
        work = self.rows("mfg_work_order", f"(work_order_id={root} OR root_work_order_id={root}) AND deleted_flag=0")
        ids = ",".join(str(row["work_order_id"]) for row in work)
        lots = self.rows("trace_product_lot", f"work_order_id IN ({ids}) AND deleted_flag=0")
        order = self.one("mfg_production_order", f"production_order_id={self.state['productionOrderId']}")
        shipment = self.one("wms_outbound_order", f"outbound_id={self.state['salesOutboundId']}")
        shipped = Decimal(
            self.sql(
                "SELECT COALESCE(SUM(outbound_quantity),0) FROM wms_outbound_detail"
                f" WHERE outbound_id={shipment['outbound_id']} AND deleted_flag=0"
            )
        )
        screw = Decimal(
            self.sql(
                "SELECT COALESCE(SUM(m.used_quantity),0) FROM mfg_report_material m"
                " JOIN mfg_work_report r ON r.report_id=m.report_id"
                " AND r.audit_status='APPROVED' AND r.deleted_flag=0"
                " JOIN mfg_task t ON t.task_id=r.task_id"
                " JOIN mdm_material raw ON raw.material_id=m.material_id"
                " WHERE m.deleted_flag=0"
                f" AND t.work_order_id IN ({ids}) AND raw.material_code='LAMP-M18'"
            )
        )
        kept = self.state.get("returnOrder") or {}
        if not kept:
            raise RuntimeError("缺少退料记录")
        returned_order = self.one("wms_return_order", f"return_id={kept['returnId']}")
        returned_quantity = Decimal(
            self.sql(
                "SELECT COALESCE(SUM(return_quantity),0) FROM wms_return_detail"
                f" WHERE return_id={kept['returnId']} AND deleted_flag=0"
            )
        )
        returned_expected = sum(Decimal(str(row["returnQuantity"])) for row in kept["details"])
        returned_missing_movements = int(
            self.sql(
                "SELECT COUNT(*) FROM wms_return_detail d WHERE d.deleted_flag=0"
                f" AND d.return_id={kept['returnId']} AND NOT EXISTS (SELECT 1 FROM"
                " wms_stock_movement m WHERE m.deleted_flag=0"
                " AND m.source_order_type='RETURN_ORDER'"
                f" AND m.source_order_id={kept['returnId']}"
                " AND m.direction='IN' AND m.material_id=d.material_id"
                " AND m.quantity=d.return_quantity)"
            )
        )
        checks = {
            "fourWorkOrders": len(work) == 4,
            "allWorkOrdersFinished": all(row["status"] == "FINISHED" for row in work),
            "allWorkOrdersQuantityTen": all(Decimal(str(row["completed_quantity"])) == 10 for row in work),
            "oneRootLotTen": root_lot_is_ten(lots, root),
            "screwsExactlyEighty": screw == 80,
            "orderFinished": order["status"] == "FINISHED",
            "shipmentApprovedTen": shipment["audit_status"] == "APPROVED" and shipped == 10,
            "returnApproved": returned_order["audit_status"] == "APPROVED",
            "returnQuantityMatches": returned_quantity == returned_expected,
            "returnMovementsInbound": returned_missing_movements == 0,
            "noNegativeInventory": self.sql(
                "SELECT COUNT(*) FROM wms_inventory"
                " WHERE available_quantity<0 OR locked_quantity<0"
                " OR inspection_quantity<0"
            )
            == "0",
            "lineSideReleased": self.sql(
                "SELECT COUNT(*) FROM wms_inventory"
                f" WHERE work_order_id IN ({ids})"
                " AND (available_quantity<>0 OR locked_quantity<>0"
                " OR inspection_quantity<>0)"
            )
            == "0",
            "noUnbalancedTransferPairs": self.sql(
                "SELECT COUNT(*) FROM (SELECT pair_no FROM wms_stock_movement"
                " WHERE deleted_flag=0 AND pair_no IS NOT NULL"
                " GROUP BY pair_no HAVING SUM(CASE WHEN direction='IN'"
                " THEN quantity ELSE -quantity END)<>0) pairs"
            )
            == "0",
            "noPendingInspections": self.sql(
                "SELECT COUNT(*) FROM qms_inspection_order WHERE deleted_flag=0"
                f" AND (work_order_id IN ({ids})"
                f" OR inspection_id={shipment['inspection_id']})"
                " AND status<>'FINISHED'"
            )
            == "0",
            "adminEmployeeRestored": self.one("sys_user", "username='admin' AND deleted_flag=0")["employee_id"]
            == self.state["originalAdminEmployeeId"],
        }
        self.state["audit"] = {
            "checks": checks,
            "workOrders": [
                {
                    "id": r["work_order_id"],
                    "no": r["work_order_no"],
                    "type": r["work_order_type"],
                    "quantity": r["completed_quantity"],
                    "status": r["status"],
                }
                for r in work
            ],
            "orderNo": order["production_order_no"],
            "salesOutboundNo": shipment["outbound_no"],
            "productLots": [
                {"id": r["product_lot_id"], "quantity": r["qualified_quantity"], "status": r["status"]} for r in lots
            ],
            "screwsConsumed": int(screw),
            "shippedQuantity": int(shipped),
            "passed": all(checks.values()),
        }
        self.save()
        print(json.dumps(self.state["audit"], ensure_ascii=False, indent=2))
        if not all(checks.values()):
            raise RuntimeError("演练对账失败，请保留记录排查")

    def prepare(self):
        self.backup()
        self.login()
        products = self.rows(
            "mdm_product", "deleted_flag=0 AND product_code IN" " ('LAMP-HEAD','LAMP-ARM','LAMP-BASE-L200','LAMP-L200')"
        )
        ids = ",".join(str(p["product_id"]) for p in products)
        # 工程参数仅确认本次演练所用设计输入，不更改当前数值。
        for parameter in self.rows(
            "mdm_product_parameter", f"deleted_flag=0 AND product_id IN ({ids}) AND required_flag=1"
        ):
            pid = parameter["parameter_id"]
            if parameter["status"] == "DRAFT":
                self.api(
                    "PUT",
                    f"/api/mdm/product-parameter/{pid}",
                    {
                        "version": parameter["version"],
                        "parameterValue": parameter["parameter_value"],
                        "sourceReference": "QST-LAMP-SPEC-01；" + NOTE,
                    },
                    key=f"parameter-source-{pid}",
                )
                current = self.one("mdm_product_parameter", f"parameter_id={pid}")
                self.api(
                    "POST",
                    f"/api/mdm/product-parameter/{pid}/confirm",
                    {"version": current["version"]},
                    key=f"parameter-confirm-{pid}",
                )
        # 发布当前V18方案所引用的标准，保留ALL逐件检验，不改为汇总放行。
        schemes = self.rows(
            "qms_inspection_scheme", f"deleted_flag=0 AND product_id IN ({ids}) AND scheme_code LIKE 'LAMP-V18-%'"
        )
        for scheme in schemes:
            for item in self.rows("qms_inspection_scheme_item", f"deleted_flag=0 AND scheme_id={scheme['scheme_id']}"):
                standard = self.one("qms_inspection_standard", f"standard_id={item['standard_id']}")
                if standard["confirmation_status"] == "DRAFT":
                    self.api(
                        "POST",
                        f"/api/qms/inspection-standard/{standard['standard_id']}/confirm",
                        {"version": standard["version"]},
                        key=f"standard-{standard['standard_id']}",
                    )
            if scheme["status"] != "RELEASED":
                self.api(
                    "PUT",
                    "/api/qms/inspection-scheme",
                    {
                        "schemeId": scheme["scheme_id"],
                        "version": scheme["version"],
                        "status": "RELEASED",
                        "remark": NOTE,
                    },
                    key=f"scheme-{scheme['scheme_id']}",
                )
        # 冻结执行依据要求终检显式对应唯一产出工序；为演练另建版本，不改发布版本内容。
        for scheme in schemes:
            if scheme["inspection_type"] != "FINAL" or scheme["operation_id"] is not None:
                continue
            route = self.one(
                "process_route", f"product_id={scheme['product_id']} AND status='RELEASED' AND deleted_flag=0"
            )
            terminal = self.rows(
                "process_route_item", f"route_id={route['route_id']} AND deleted_flag=0 ORDER BY sequence_no DESC"
            )[0]
            code = scheme["scheme_code"] + "-REH"
            if not self.rows("qms_inspection_scheme", f"scheme_code='{code}' AND deleted_flag=0"):
                self.api(
                    "POST",
                    "/api/qms/inspection-scheme",
                    {
                        "schemeCode": code,
                        "schemeName": "演练-" + scheme["scheme_name"],
                        "productId": scheme["product_id"],
                        "operationId": terminal["operation_id"],
                        "inspectionType": "FINAL",
                        "status": "DRAFT",
                        "remark": NOTE,
                    },
                    key=f"final-scheme-{scheme['scheme_id']}",
                )
            new = self.one("qms_inspection_scheme", f"scheme_code='{code}' AND deleted_flag=0")
            if new["status"] == "DRAFT":
                for item in self.rows(
                    "qms_inspection_scheme_item", f"scheme_id={scheme['scheme_id']} AND deleted_flag=0"
                ):
                    self.api(
                        "POST",
                        "/api/qms/inspection-scheme-item",
                        {
                            "schemeId": new["scheme_id"],
                            "itemId": item["item_id"],
                            "standardId": item["standard_id"],
                            "sampleQuantity": item["sample_quantity"],
                            "samplingMode": item["sampling_mode"],
                            "sequenceNo": item["sequence_no"],
                        },
                        key=f"final-scheme-item-{scheme['scheme_id']}-{item['item_id']}",
                    )
                self.api(
                    "PUT",
                    "/api/qms/inspection-scheme",
                    {"schemeId": new["scheme_id"], "version": new["version"], "status": "RELEASED"},
                    key=f"final-scheme-release-{scheme['scheme_id']}",
                )
            old = self.one("qms_inspection_scheme", f"scheme_id={scheme['scheme_id']}")
            if old["status"] == "RELEASED":
                self.api(
                    "PUT",
                    "/api/qms/inspection-scheme",
                    {"schemeId": old["scheme_id"], "version": old["version"], "status": "DISABLE"},
                    key=f"final-scheme-disable-{scheme['scheme_id']}",
                )
        # 已发布BOM不原地补字段；新建工程演练版本，逐工序绑定直接物料。
        bom_ids = {}
        for code in ["LAMP-HEAD", "LAMP-ARM", "LAMP-BASE-L200", "LAMP-L200"]:
            product = next(p for p in products if p["product_code"] == code)
            original = self.one(
                "mdm_bom", f"deleted_flag=0 AND product_id={product['product_id']}" " AND bom_code NOT LIKE '%-REH-%'"
            )
            bom_code = original["bom_code"] + "-REH-20261025"
            existing = self.rows("mdm_bom", f"bom_code='{bom_code}' AND deleted_flag=0")
            if not existing:
                self.api(
                    "POST",
                    "/api/mdm/bom",
                    {
                        "bomCode": bom_code,
                        "productId": product["product_id"],
                        "bomVersion": "REH-20261025",
                        "effectiveDate": "2026-10-25",
                        "status": "DRAFT",
                        "remark": NOTE,
                    },
                    key="bom-" + code,
                )
            bom = self.one("mdm_bom", f"bom_code='{bom_code}' AND deleted_flag=0")
            bom_ids[original["bom_id"]] = bom["bom_id"]
            if bom["status"] != "RELEASED":
                for item in self.rows("mdm_bom_item", f"bom_id={original['bom_id']} AND deleted_flag=0"):
                    material = self.one("mdm_material", f"material_id={item['material_id']}")
                    m = material["material_code"].replace("LAMP-", "")
                    operation = (
                        "F10"
                        if code == "LAMP-L200"
                        else (
                            "A10"
                            if code == "LAMP-ARM"
                            else (
                                ("H20" if m in ["M03", "M04"] else "H10")
                                if code == "LAMP-HEAD"
                                else (
                                    "B10"
                                    if m in ["M08", "M09", "M10"]
                                    else "B20" if m in ["M05", "M06", "M07"] else "B30"
                                )
                            )
                        )
                    )
                    op = self.one("process_operation", f"operation_code='LAMP-OP-{operation}' AND deleted_flag=0")
                    self.api(
                        "POST",
                        "/api/mdm/bom-item",
                        {
                            "bomId": bom["bom_id"],
                            "materialId": item["material_id"],
                            "quantity": item["quantity"],
                            "lossRate": item["loss_rate"],
                            "sequenceNo": item["sequence_no"],
                            "childBomId": bom_ids.get(item["child_bom_id"]),
                            "consumptionOperationId": op["operation_id"],
                            "remark": NOTE,
                        },
                        key=f"bom-item-{code}-{item['material_id']}",
                    )
                self.api(
                    "PUT",
                    "/api/mdm/bom",
                    {"bomId": bom["bom_id"], "version": bom["version"], "status": "RELEASED"},
                    key="bom-release-" + code,
                )
        root_bom = self.one("mdm_bom", "bom_code='LAMP-BOM-L200-V1'")
        mapped_root_bom_id = bom_ids.get(root_bom.get("bom_id"))
        if mapped_root_bom_id is None:
            raise RuntimeError("根 BOM 未完成演练版本映射：LAMP-BOM-L200-V1")
        self.state["rootBomId"] = mapped_root_bom_id
        # 虚拟人员与专用演练班组，避免把一个人同时排到三条组件线。
        if not self.rows("org_department", "department_code='LAMP-REH-DEPT' AND deleted_flag=0"):
            self.api(
                "POST",
                "/api/org/department",
                {
                    "departmentCode": "LAMP-REH-DEPT",
                    "departmentName": "台灯工程演练部门",
                    "parentId": 0,
                    "remark": NOTE,
                },
                key="department",
            )
        department = self.one("org_department", "department_code='LAMP-REH-DEPT' AND deleted_flag=0")
        teams = {}
        for suffix in ["HEAD", "ARM", "BASE", "FINAL", "QMS", "WMS"]:
            ecode = "LAMP-REH-EMP-" + suffix
            if not self.rows("org_employee", f"employee_no='{ecode}'"):
                self.api(
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
                    key="employee-" + suffix,
                )
            employee = self.one("org_employee", f"employee_no='{ecode}'")
            tcode = "LAMP-REH-TEAM-" + suffix
            if not self.rows("org_team", f"team_code='{tcode}'"):
                self.api(
                    "POST",
                    "/api/org/team",
                    {
                        "teamCode": tcode,
                        "teamName": "演练-" + suffix + "班组",
                        "departmentId": department["department_id"],
                        "leaderId": employee["employee_id"],
                        "remark": NOTE,
                    },
                    key="team-" + suffix,
                )
            team = self.one("org_team", f"team_code='{tcode}'")
            if not self.rows(
                "org_team_member",
                f"team_id={team['team_id']} AND employee_id={employee['employee_id']}" " AND deleted_flag=0",
            ):
                self.api(
                    "POST",
                    "/api/org/team-member",
                    {
                        "teamId": team["team_id"],
                        "employeeId": employee["employee_id"],
                        "joinDate": "2026-10-25",
                        "status": "ENABLE",
                    },
                    key="member-" + suffix,
                )
            teams[suffix] = team["team_id"]
        self.state["teams"] = teams
        self.sql(
            "UPDATE factory_station SET status='RUNNING',version=version+1,"
            "updated_time=NOW() WHERE deleted_flag=0 AND (station_code LIKE 'LAMP-%'"
            " OR station_code IN ('WS-HEAD-01','WS-ARM-01','WS-BASE-01','WS-FG-01',"
            "'WS-AGING-01')) AND status<>'RUNNING';"
            "UPDATE factory_station_operation SET status='ENABLE' WHERE deleted_flag=0"
            " AND station_id IN (SELECT station_id FROM factory_station WHERE"
            " (station_code LIKE 'LAMP-%' OR station_code IN ('WS-HEAD-01','WS-ARM-01',"
            "'WS-BASE-01','WS-FG-01','WS-AGING-01')) AND deleted_flag=0);"
            "UPDATE eam_equipment e JOIN factory_station previous"
            " ON previous.station_id=e.station_id"
            " JOIN factory_line l ON l.line_id=previous.line_id"
            " JOIN factory_station target ON target.station_code=CASE"
            " WHEN e.equipment_code='LAMP-EQ-AGING-RACK' THEN 'WS-AGING-01'"
            " WHEN l.line_code='LAMP-LINE-HEAD' THEN 'WS-HEAD-01'"
            " WHEN l.line_code='LAMP-LINE-ARM' THEN 'WS-ARM-01'"
            " WHEN l.line_code='LAMP-LINE-BASE' THEN 'WS-BASE-01'"
            " ELSE 'WS-FG-01' END"
            " SET e.station_id=target.station_id,e.version=e.version+1"
            " WHERE e.deleted_flag=0 AND e.equipment_code LIKE 'LAMP-%'"
            " AND e.station_id<>target.station_id;"
            "UPDATE eam_equipment SET status='IDLE',version=version+1,"
            "updated_time=NOW(),remark=CONCAT(COALESCE(remark,''),"
            "'；工程演练可用，非实物设备状态') WHERE deleted_flag=0"
            " AND equipment_code LIKE 'LAMP-%'"
            " AND status IN ('STOPPED','DISABLE','OFFLINE');"
        )
        self.state["stages"].append("prepared") if "prepared" not in self.state["stages"] else None
        self.save()


def root_lot_is_ten(lots, root_work_order_id):
    """整机工单应恰好有一条合格数量为 10 的根批次。"""
    matched = 0
    for row in lots:
        if row["work_order_id"] != root_work_order_id:
            continue
        if Decimal(str(row["qualified_quantity"])) == 10:
            matched += 1
    return matched == 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", required=True)
    parser.add_argument("--port", type=int, choices=[3307, 3310, 3311], default=3310)
    parser.add_argument("--api-base", default="http://127.0.0.1:8089")
    parser.add_argument("--run-key", default="lamp-order-rehearsal-20261025")
    parser.add_argument(
        "--stage",
        choices=["prepare", "purchase", "production", "ship", "returns", "repair_links", "audit"],
        default="prepare",
    )
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    if not re.fullmatch("mes_lamp_clean_[A-Za-z0-9_]+", args.database) or not re.fullmatch(
        "lamp-order-rehearsal-[A-Za-z0-9_-]+", args.run_key
    ):
        parser.error("仅允许本机纯台灯演练库及受保护的演练记录目录")
    if not args.apply:
        print(
            json.dumps(
                {"database": args.database, "stage": args.stage, "preview": True, "remark": NOTE}, ensure_ascii=False
            )
        )
        return
    run = Rehearsal(args)
    if run.sql('SELECT CONCAT(DATABASE(),":",@@port)') != args.database + ":" + str(args.port):
        raise RuntimeError("数据库身份不匹配")
    getattr(run, args.stage)()
    print("演练阶段完成：" + args.stage)


if __name__ == "__main__":
    main()
