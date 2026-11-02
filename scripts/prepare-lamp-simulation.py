"""准备独立台灯库的模拟资源：短名称、专线班组、成员和原料期初调整。

默认只预览；--apply 先备份再写入，--schedule 通过正式接口补齐有效周历内的日期排班。
不生成订单、报工或检验结果，不重置业务数据；库存调整始终配套流水。
依赖项目现有的 pymysql，密码从 MYSQL_PWD / MES_DEMO_PASSWORD 环境变量读取。
"""

import argparse
import datetime as dt
from decimal import Decimal
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.request
from zoneinfo import ZoneInfo

import pymysql

ROOT = Path(__file__).resolve().parents[1]
MARK = "SIM-20261027"
LINES = {
    "HEAD": ("灯头线", 240, "LAMP-REH-TEAM-HEAD", "灯头组"),
    "ARM": ("灯臂线", 240, "LAMP-REH-TEAM-ARM", "灯臂组"),
    "BASE": ("底座线", 160, "LAMP-REH-TEAM-BASE", "底座组"),
    "FINAL": ("总装线", 80, "LAMP-REH-TEAM-FINAL", "总装组"),
    "L100": ("L100专线", 80, "LAMP-TEAM-L100", "L100组"),
    "L200": ("L200专线", 60, "LAMP-TEAM-L200", "L200组"),
    "FLEX": ("柔性线", 40, "LAMP-TEAM-FLEX", "柔性组"),
}
PRODUCTS = {
    "HEAD": "灯头组件",
    "ARM": "灯臂组件",
    "BASE-L100": "L100底座",
    "BASE-L200": "L200底座",
    "L100": "L100台灯",
    "L200": "L200台灯",
}
WAREHOUSES = {"RAW": "原料仓", "LINE": "线边仓", "SEMI": "半成品仓", "FG": "成品仓", "HOLD": "隔离仓"}


def api(base, path, token=None, method="GET", body=None):
    payload = None if body is None else json.dumps(body).encode("utf8")
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(base.rstrip("/") + path, data=payload, headers=headers, method=method)
    with urllib.request.urlopen(request, timeout=30) as response:
        result = json.load(response)
    if str(result.get("code")) not in ("200", "0"):
        raise RuntimeError(f"{method} {path}: {result.get('message', result.get('msg'))}")
    return result


def prepare(db, today, args):
    changed = {
        "renamed": 0,
        "createdTeams": 0,
        "createdEmployees": 0,
        "createdMembers": 0,
        "stockAdjustments": [],
        "lineTeams": [],
    }
    with db.cursor() as c:
        c.execute("SELECT user_id FROM sys_user WHERE username='admin' AND deleted_flag=0")
        actor = c.fetchone()["user_id"]

        def insert(table, values):
            data = dict(
                values,
                created_by=actor,
                created_time=dt.datetime.now(ZoneInfo("Asia/Shanghai")).replace(tzinfo=None),
                deleted_flag=0,
            )
            c.execute(
                "INSERT INTO `"
                + table
                + "` ("
                + ",".join("`" + key + "`" for key in data)
                + ") VALUES ("
                + ",".join(["%s"] * len(data))
                + ")",
                list(data.values()),
            )
            return c.lastrowid

        def rename(table, code_field, code, name_field, name):
            c.execute(
                "UPDATE `" + table + "` SET `" + name_field + "`=%s,version=version+1,updated_time=NOW(),updated_by=%s "
                "WHERE `" + code_field + "`=%s AND deleted_flag=0 AND NOT (`" + name_field + "` <=> %s)",
                (name, actor, code, name),
            )
            changed["renamed"] += c.rowcount

        rename("factory_workshop", "workshop_code", "LAMP-WORKSHOP", "workshop_name", "台灯车间")
        rename("org_department", "department_code", "LAMP-DEPT", "department_name", "生产部")
        rename("org_department", "department_code", "LAMP-REH-DEPT", "department_name", "制造部")
        for key, name in PRODUCTS.items():
            rename("mdm_product", "product_code", "LAMP-" + key, "product_name", name)
            rename("mdm_material", "material_code", "LAMP-" + key, "material_name", name)
            rename(
                "process_route",
                "route_code",
                "LAMP-ROUTE-" + key + "-V01",
                "route_name",
                name.replace("台灯", "") + "工艺",
            )
        for key, name in WAREHOUSES.items():
            rename("wms_warehouse", "warehouse_code", "LAMP-WH-" + key, "warehouse_name", name)
            rename("wms_location", "location_code", "LAMP-LOC-" + key, "location_name", name + "01位")
        for code, name in [
            ("LAMP-TEAM-COMPONENT", "组件支援组"),
            ("LAMP-TEAM-FINAL", "总装支援组"),
            ("LAMP-TEAM-QUALITY", "质检支援组"),
            ("LAMP-REH-TEAM-QMS", "质检组"),
            ("LAMP-REH-TEAM-WMS", "仓储组"),
        ]:
            rename("org_team", "team_code", code, "team_name", name)
        for key, name in [("AM", "上午班"), ("PM", "下午班")]:
            rename("org_shift", "shift_code", "LAMP-SHIFT-" + key, "shift_name", name)
        for key, name in [
            ("HEAD", "灯头装配"),
            ("ARM", "灯臂装配"),
            ("BASE", "底座装配"),
            ("FG", "总装作业"),
            ("AGING", "老化检测"),
        ]:
            rename("factory_station", "station_code", "WS-" + key + "-01", "station_name", name + "位")
        c.execute("SELECT station_code,station_name,line_id FROM factory_station WHERE deleted_flag=0")
        stations = c.fetchall()
        c.execute("SELECT department_id FROM org_department WHERE department_code='LAMP-REH-DEPT' AND deleted_flag=0")
        department = c.fetchone()["department_id"]
        for suffix, (line_name, _capacity, team_code, team_name) in LINES.items():
            line_code = "LAMP-LINE-" + suffix
            c.execute("SELECT * FROM factory_line WHERE line_code=%s AND deleted_flag=0", (line_code,))
            line = c.fetchone()
            if not line:
                raise RuntimeError("缺少产线 " + line_code + "，请先维护产线、能力、工位和周历，再准备模拟班组")
            line_id = line["line_id"]
            rename("factory_line", "line_code", line_code, "line_name", line_name)
            c.execute("SELECT * FROM org_team WHERE team_code=%s AND deleted_flag=0", (team_code,))
            team = c.fetchone()
            if not team:
                team_id = insert(
                    "org_team",
                    dict(
                        team_code=team_code,
                        team_name=team_name,
                        department_id=department,
                        remark=MARK + " 对应" + line_name,
                        version=0,
                    ),
                )
                changed["createdTeams"] += 1
            else:
                team_id = team["team_id"]
                rename("org_team", "team_code", team_code, "team_name", team_name)
            c.execute(
                "SELECT e.employee_id,e.employee_no FROM org_employee e JOIN org_team_member m ON"
                " m.employee_id=e.employee_id "
                "WHERE m.team_id=%s AND m.deleted_flag=0 AND m.status='ENABLE' AND e.deleted_flag=0 AND"
                " e.status='ENABLE' "
                "AND (m.leave_date IS NULL OR m.leave_date>=%s) ORDER BY e.employee_id",
                (team_id, today),
            )
            members = list(c.fetchall())
            if members:
                rename(
                    "org_employee", "employee_no", members[0]["employee_no"], "employee_name", team_name[:-1] + "班长"
                )
            # 只补到三人在职；同一员工不复用到另一条同时开工的产线。
            while len(members) < 3:
                index = len(members) + 1
                employee_no = f"SIM-{suffix}-{index:02}"
                c.execute(
                    "SELECT employee_id FROM org_employee WHERE employee_no=%s AND deleted_flag=0", (employee_no,)
                )
                employee = c.fetchone()
                employee_id = (
                    employee["employee_id"]
                    if employee
                    else insert(
                        "org_employee",
                        dict(
                            employee_no=employee_no,
                            employee_name=team_name[:-1] + ("班长" if index == 1 else f"操作员{index - 1}"),
                            department_id=department,
                            position_name="班长" if index == 1 else "操作员",
                            skill_level="SKILLED",
                            status="ENABLE",
                            remark=MARK + " 虚拟演练人员",
                            version=0,
                        ),
                    )
                )
                if not employee:
                    changed["createdEmployees"] += 1
                insert(
                    "org_team_member", dict(team_id=team_id, employee_id=employee_id, join_date=today, status="ENABLE")
                )
                changed["createdMembers"] += 1
                members.append({"employee_id": employee_id, "employee_no": employee_no})
            c.execute(
                "UPDATE org_team SET leader_id=%s,updated_by=%s,updated_time=NOW(),version=version+1 "
                "WHERE team_id=%s AND leader_id IS NULL",
                (members[0]["employee_id"], actor, team_id),
            )
            changed["lineTeams"].append(
                {
                    "lineId": line_id,
                    "lineName": line_name,
                    "teamId": team_id,
                    "teamName": team_name,
                    "headcount": len(members),
                }
            )
            for row in stations:
                if row["line_id"] != line_id or not row["station_code"].startswith("LAMP-ST-"):
                    continue
                old = row["station_name"]
                if suffix in ("L100", "L200", "FLEX"):
                    short = line_name.replace("专线", "").replace("线", "") + (
                        "总装位" if row["station_code"].endswith("F10") else "终检位"
                    )
                else:
                    short = {
                        "LAMP-ST-HEAD-H-ASSY": "灯头装配位",
                        "LAMP-ST-HEAD-H-TEST": "灯头检测位",
                        "LAMP-ST-ARM-A-ASSY": "灯臂装配位",
                        "LAMP-ST-ARM-A-CHECK": "灯臂检测位",
                        "LAMP-ST-BASE-B-ASSY": "底座装配位",
                        "LAMP-ST-BASE-B-TEST": "底座检测位",
                        "LAMP-ST-BASE-SENSOR": "光感检测位",
                        "LAMP-ST-FINAL-FINAL-ASSY": "整机总装位",
                        "LAMP-ST-FINAL-FUNCTION": "调光检测位",
                        "LAMP-ST-FINAL-AGING": "整机老化位",
                        "LAMP-ST-FINAL-FINAL-CHECK": "整机终检位",
                        "LAMP-ST-FINAL-PACK": "整机包装位",
                        "LAMP-ST-FINAL-AUTO": "光感测试位",
                    }.get(row["station_code"], old)
                rename("factory_station", "station_code", row["station_code"], "station_name", short)
        for key, name in [("QMS", "质检员"), ("WMS", "仓管员")]:
            c.execute(
                "SELECT e.employee_no FROM org_employee e JOIN org_team t ON t.leader_id=e.employee_id "
                "WHERE t.team_code=%s AND t.deleted_flag=0 AND e.deleted_flag=0",
                ("LAMP-REH-TEAM-" + key,),
            )
            employee = c.fetchone()
            if employee:
                rename("org_employee", "employee_no", employee["employee_no"], "employee_name", name)
        # 原料补量以物料合计计算；本次补量有独立标识，重跑不会再次补回已消耗库存。
        c.execute(
            "SELECT warehouse_id FROM wms_warehouse WHERE warehouse_code='LAMP-WH-RAW' AND status='ENABLE' AND"
            " deleted_flag=0"
        )
        warehouse = c.fetchone()["warehouse_id"]
        c.execute(
            "SELECT location_id FROM wms_location WHERE warehouse_id=%s AND location_type='NORMAL' AND status IN"
            " ('IDLE','OCCUPIED','ENABLE') AND deleted_flag=0 ORDER BY location_id LIMIT 1",
            (warehouse,),
        )
        location = c.fetchone()["location_id"]
        c.execute(
            "SELECT material_id,material_code FROM mdm_material WHERE material_code REGEXP"
            " '^LAMP-M(0[1-9]|1[0-8])$' AND material_type IN ('RAW','AUXILIARY') AND status='ENABLE' AND"
            " deleted_flag=0 ORDER BY material_code"
        )
        materials = c.fetchall()
        if len(materials) != 18:
            raise RuntimeError("期望 18 种启用的台灯原辅料，实际数据不完整，已停止")
        for material in materials:
            movement_no = MARK + "-" + material["material_code"].removeprefix("LAMP-")
            c.execute(
                "SELECT log_id FROM sys_operation_log WHERE operation_type='SIM_STOCK_INIT' AND"
                " module_code='wms_inventory' AND biz_id=%s AND remark=%s AND deleted_flag=0",
                (material["material_id"], MARK),
            )
            if c.fetchone():
                continue
            # 已满足数量的物料也登记本轮初始化完成，避免它以后被消耗时重跑又补回。
            insert(
                "sys_operation_log",
                dict(
                    user_id=actor,
                    operation_type="SIM_STOCK_INIT",
                    module_code="wms_inventory",
                    biz_id=material["material_id"],
                    remark=MARK,
                    operation_time=dt.datetime.now(ZoneInfo("Asia/Shanghai")).replace(tzinfo=None),
                    version=0,
                ),
            )
            c.execute("SELECT movement_id FROM wms_stock_movement WHERE movement_no=%s", (movement_no,))
            if c.fetchone():
                continue
            c.execute(
                "SELECT i.* FROM wms_inventory i JOIN wms_material_batch b ON b.batch_id=i.batch_id AND"
                " b.material_id=i.material_id AND b.deleted_flag=0 "
                "JOIN wms_location l ON l.location_id=i.location_id AND l.warehouse_id=i.warehouse_id AND"
                " l.deleted_flag=0 "
                "WHERE i.warehouse_id=%s AND i.material_id=%s AND i.work_order_id=0 AND i.deleted_flag=0 "
                "AND b.quality_status IN ('PASS','CONCESSION') AND b.status IN ('AVAILABLE','ENABLE') "
                "AND l.location_type='NORMAL' AND l.status IN ('IDLE','OCCUPIED','ENABLE') ORDER BY i.inventory_id"
                " FOR UPDATE",
                (warehouse, material["material_id"]),
            )
            stocks = c.fetchall()
            available = sum((row["available_quantity"] for row in stocks), Decimal(0))
            delta = max(Decimal(args.stock) - available, Decimal(0))
            if not delta:
                continue
            if stocks:
                stock = stocks[0]
            else:
                batch = insert(
                    "wms_material_batch",
                    dict(
                        batch_no=MARK + "-" + material["material_code"],
                        material_id=material["material_id"],
                        received_date=today,
                        quality_status="PASS",
                        status="AVAILABLE",
                        source_order_type="DEMO_SEED",
                        remark=MARK + " 模拟期初批次，非采购到货或实测检验",
                        version=0,
                    ),
                )
                inventory = insert(
                    "wms_inventory",
                    dict(
                        warehouse_id=warehouse,
                        location_id=location,
                        material_id=material["material_id"],
                        batch_id=batch,
                        available_quantity=0,
                        locked_quantity=0,
                        inspection_quantity=0,
                        work_order_id=0,
                        remark=MARK + " 演练期初库存",
                        version=0,
                    ),
                )
                stock = {"inventory_id": inventory, "batch_id": batch, "location_id": location}
            insert(
                "wms_stock_movement",
                dict(
                    movement_no=movement_no,
                    movement_type="ADJUST",
                    source_order_type="DEMO_SEED",
                    source_order_id=stock["inventory_id"],
                    material_id=material["material_id"],
                    batch_id=stock["batch_id"],
                    warehouse_id=warehouse,
                    location_id=stock["location_id"],
                    work_order_id=0,
                    quantity=delta,
                    direction="IN",
                    operation_time=dt.datetime.now(ZoneInfo("Asia/Shanghai")).replace(tzinfo=None),
                    remark=MARK + " 用户授权模拟补量，非真实收货",
                    version=0,
                ),
            )
            c.execute(
                "UPDATE wms_inventory SET available_quantity=available_quantity+%s,version=version+1,updated_by=%s"
                ",updated_time=NOW() WHERE inventory_id=%s",
                (delta, actor, stock["inventory_id"]),
            )
            changed["stockAdjustments"].append(
                {
                    "material": material["material_code"],
                    "before": str(available),
                    "delta": str(delta),
                    "after": str(available + delta),
                }
            )
    return changed


def schedule(db, report, args, today, token):
    added = released = 0
    with db.cursor() as c:
        for line in report["lineTeams"]:
            for offset in range(args.days):
                date = today + dt.timedelta(days=offset)
                c.execute(
                    "SELECT DISTINCT shift_id FROM mfg_line_calendar_rule WHERE line_id=%s AND day_of_week=%s "
                    "AND effective_from<=%s AND (effective_to IS NULL OR effective_to>=%s) AND status='ENABLE' AND"
                    " capacity_factor>0 AND deleted_flag=0",
                    (line["lineId"], date.isoweekday(), date, date),
                )
                for rule in c.fetchall():
                    c.execute(
                        "SELECT schedule_id,status FROM org_team_schedule "
                        "WHERE team_id=%s AND line_id=%s AND shift_id=%s"
                        " AND work_date=%s AND deleted_flag=0",
                        (line["teamId"], line["lineId"], rule["shift_id"], date),
                    )
                    row = c.fetchone()
                    if not row:
                        api(
                            args.base,
                            "/api/org/team-schedule",
                            token,
                            "POST",
                            {
                                "teamId": line["teamId"],
                                "lineId": line["lineId"],
                                "shiftId": rule["shift_id"],
                                "workDate": date.isoformat(),
                                "plannedHeadcount": line["headcount"],
                                "remark": MARK + " 模拟排班",
                            },
                        )
                        added += 1
                        c.execute(
                            "SELECT schedule_id,status FROM org_team_schedule "
                            "WHERE team_id=%s AND line_id=%s AND shift_id=%s"
                            " AND work_date=%s AND deleted_flag=0",
                            (line["teamId"], line["lineId"], rule["shift_id"], date),
                        )
                        row = c.fetchone()
                    if row["status"] == "DRAFT":
                        api(args.base, f"/api/org/team-schedule/{row['schedule_id']}/release", token, "PUT")
                        released += 1
    report["schedules"] = {
        "created": added,
        "released": released,
        "days": args.days,
        "from": today.isoformat(),
        "to": (today + dt.timedelta(days=args.days - 1)).isoformat(),
    }


def describe_option_error(args):
    """把补量参数的业务约束集中在一处，返回错误文案，全部合法时返回 None。

    约束：补量必须是正数；排班天数限制在 1~90 天；--schedule 是在已有排班上继续排，
    必须先 --apply 把基础数据落库，否则排班会对着一张空表操作。
    """
    if args.stock <= 0:
        return "库存应为正数，排班 1~90 天，--schedule 必须配合 --apply"
    if not 1 <= args.days <= 90:
        return "库存应为正数，排班 1~90 天，--schedule 必须配合 --apply"
    if args.schedule and not args.apply:
        return "库存应为正数，排班 1~90 天，--schedule 必须配合 --apply"
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", required=True)
    parser.add_argument("--port", required=True, type=int)
    parser.add_argument("--user", default="root")
    parser.add_argument("--stock", type=int, default=1000)
    parser.add_argument("--days", type=int, default=30)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--schedule", action="store_true")
    parser.add_argument("--base", default="http://127.0.0.1:8089")
    parser.add_argument("--username", default="admin")
    args = parser.parse_args()
    if not re.fullmatch(r"mes_lamp_[A-Za-z0-9_]+", args.database) or args.port not in (3307, 3310, 3311):
        parser.error("只允许本机独立台灯演练库和验证端口")
    option_error = describe_option_error(args)
    if option_error:
        parser.error(option_error)
    if not re.fullmatch(r"http://(127\.0\.0\.1|localhost):[0-9]+", args.base):
        parser.error("排班接口只允许本机 HTTP 地址")
    token = None
    if args.schedule:
        password = os.environ.get("MES_DEMO_PASSWORD")
        if not password:
            parser.error("排班需要 MES_DEMO_PASSWORD 环境变量")
        login = api(args.base, "/api/auth/login", method="POST", body={"username": args.username, "password": password})
        token = login.get("token") or (login.get("data") or {}).get("token")
        if not token:
            raise RuntimeError("登录响应未返回 token")
    today = dt.datetime.now(ZoneInfo("Asia/Shanghai")).date()
    options = dict(
        host="127.0.0.1",
        port=args.port,
        user=args.user,
        password=os.environ.get("MYSQL_PWD", ""),
        database=args.database,
        charset="utf8mb4",
        cursorclass=pymysql.cursors.DictCursor,
    )
    report = {"database": args.database, "date": today.isoformat(), "mode": "apply" if args.apply else "preview"}
    if args.apply:
        folder = ROOT / "tmp/backups" / ("simulation-" + dt.datetime.now().strftime("%Y%m%d-%H%M%S-%f"))
        folder.mkdir(parents=True)
        backup = folder / (args.database + ".sql")
        binary = Path(os.environ.get("MYSQL_BIN", r"C:\Program Files\MySQL\MySQL Server 8.0\bin")) / "mysqldump.exe"
        subprocess.run(
            [
                str(binary),
                "--no-defaults",
                "--protocol=TCP",
                "--host=127.0.0.1",
                "--port=" + str(args.port),
                "--user=" + args.user,
                "--single-transaction",
                "--skip-lock-tables",
                "--no-tablespaces",
                "--set-gtid-purged=OFF",
                "--result-file=" + str(backup),
                args.database,
            ],
            check=True,
            capture_output=True,
        )
        if b"-- Dump completed on " not in backup.read_bytes()[-600:]:
            raise RuntimeError("备份不完整，停止修改")
        report["backup"] = {
            "file": str(backup.relative_to(ROOT)),
            "sha256": hashlib.sha256(backup.read_bytes()).hexdigest(),
        }
    db = pymysql.connect(**options)
    try:
        if not args.apply:
            with db.cursor() as c:
                c.execute("SELECT COUNT(*) n FROM factory_line WHERE line_code LIKE 'LAMP-LINE-%' AND deleted_flag=0")
                report["existingLines"] = c.fetchone()["n"]
            report.update(
                lineNames=[item[0] for item in LINES.values()],
                membersPerLine=3,
                minimumRawStock=args.stock,
                scheduleDays=args.days,
                note="只读预览；执行时备份并保留现有业务，排班需加 --schedule",
            )
            print(json.dumps(report, ensure_ascii=False, indent=2))
            return
        report.update(prepare(db, today, args))
        db.commit()
        if args.schedule:
            # API 的提交需在后续查询可见；不持有准备阶段的事务快照。
            db.autocommit(True)
            schedule(db, report, args, today, token)
    except BaseException:
        db.rollback()
        raise
    finally:
        db.close()
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
