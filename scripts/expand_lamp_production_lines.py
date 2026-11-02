"""为独立台灯演练库增加专线/柔性线；默认预览，不改既有订单、库存或工艺版本。"""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
from cli_output import write_json
import logging

logging.basicConfig(level=logging.INFO, format="%(message)s")
LOGGER = logging.getLogger(__name__)


def local_now():
    """当前本地时间：显式取带时区的时间再去掉时区，不依赖进程默认时区配置。"""
    return datetime.now(tz=timezone.utc).astimezone().replace(tzinfo=None)


ROOT = Path(__file__).resolve().parents[1]
MARKER = "LAMP_MULTILINE_DEMO_V1"
LINES = [
    {
        "code": "LAMP-LINE-L100",
        "name": "L100专用总装终检线",
        "capacity": 80,
        "products": ["LAMP-L100"],
        "priority": 10,
        "days": range(1, 6),
        "shifts": ["LAMP-SHIFT-AM", "LAMP-SHIFT-PM"],
        "purpose": "只生产L100触控调光台灯；独立总装/触控测试和老化资源",
    },
    {
        "code": "LAMP-LINE-L200",
        "name": "L200专用总装终检线",
        "capacity": 60,
        "products": ["LAMP-L200"],
        "priority": 10,
        "days": range(1, 6),
        "shifts": ["LAMP-SHIFT-AM", "LAMP-SHIFT-PM"],
        "purpose": "只生产L200自动调光台灯；独立总装/自动调光测试和老化资源",
    },
    {
        "code": "LAMP-LINE-FLEX",
        "name": "L100/L200柔性补充线",
        "capacity": 40,
        "products": ["LAMP-L100", "LAMP-L200"],
        "priority": 200,
        "days": range(1, 7),
        "shifts": ["LAMP-SHIFT-PM"],
        "purpose": "兼容L100/L200；周一至周六仅下午班，用于补充负荷和周六排产",
    },
]


def literal(value):
    if value is None:
        return "NULL"
    if isinstance(value, (int, float)):
        return str(value)
    # UTF-8 hex literals avoid SQL mode dependent backslash/string escaping.
    return "(CONVERT(X'" + str(value).encode("utf-8").hex() + "' USING utf8mb4) COLLATE utf8mb4_general_ci)"


def describes_same_station(devices, station_rows):
    """判断已存在的设备记录是否仍然唯一且挂在预期的工位上。

    设备编码在台灯演练库里是唯一的，一旦出现重复、工位被换过或被软删除，
    说明库里的基础数据和本脚本的预期不一致，继续写下去会把设备挂到错误的工位。
    """
    if len(devices) != 1:
        return False
    if not station_rows:
        return False
    device = devices[0]
    return device["station_id"] == station_rows[0]["station_id"] and device["deleted_flag"] == 0


def first_line_id(db, line_code):
    """按产线编码取库里已存在的产线主键，取不到返回 None。

    产线可能在本脚本更早的语句里才插入（语句稍后统一执行），所以这里查的是当前库，
    与插入语句里的 @line 变量取值口径一致。
    """
    rows = db.rows("factory_line", f"line_code={literal(line_code)} AND deleted_flag=0")
    return rows[0]["line_id"] if rows else None


class Database:
    def __init__(self, args):
        self.args = args
        self.binary = Path(os.environ.get("MYSQL_BIN", r"C:\Program Files\MySQL\MySQL Server 8.0\bin"))
        self.connection = [
            "--no-defaults",
            "--protocol=TCP",
            "--host=127.0.0.1",
            f"--port={args.port}",
            f"--user={args.user}",
            "--default-character-set=utf8mb4",
        ]

    def sql(self, statement):
        result = subprocess.run(
            [
                str(self.binary / "mysql.exe"),
                *self.connection,
                "--database=" + self.args.database,
                "--batch",
                "--raw",
                "--skip-column-names",
            ],
            input=statement,
            text=True,
            encoding="utf-8",
            capture_output=True,
            timeout=120,
        )
        if result.returncode:
            raise RuntimeError(result.stderr.strip())
        return result.stdout.strip()

    def rows(self, table, where="deleted_flag=0"):
        if not re.fullmatch("[a-z_]+", table):
            raise ValueError(table)
        columns = self.sql(f"SHOW COLUMNS FROM `{table}`").splitlines()
        fields = [row.split("\t")[0] for row in columns]
        projection = ",".join(f"'{c}',`{c}`" for c in fields)
        result = self.sql(f"SELECT JSON_OBJECT({projection}) FROM `{table}` WHERE {where}")
        return [json.loads(row) for row in result.splitlines()]

    def one(self, table, where):
        rows = self.rows(table, where)
        if len(rows) != 1:
            raise RuntimeError(f"{table} 应唯一匹配，实际为 {len(rows)} 行：{where}")
        return rows[0]

    def backup(self, directory):
        backup_path = directory / "before.sql"
        result = subprocess.run(
            [
                str(self.binary / "mysqldump.exe"),
                *self.connection,
                "--single-transaction",
                "--no-tablespaces",
                "--set-gtid-purged=OFF",
                "--result-file=" + str(backup_path),
                self.args.database,
            ],
            capture_output=True,
            timeout=120,
        )
        if (
            result.returncode
            or not backup_path.is_file()
            or b"-- Dump completed" not in backup_path.read_bytes()[-500:]
        ):
            raise RuntimeError("备份未成功完成，未修改数据库")
        return {
            "path": str(backup_path.relative_to(ROOT)),
            "sha256": hashlib.sha256(backup_path.read_bytes()).hexdigest(),
        }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", required=True)
    parser.add_argument("--port", type=int, choices=[3310, 3307], default=3310)
    parser.add_argument("--user", default="root")
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    if not re.fullmatch(r"mes_lamp_clean_[A-Za-z0-9_]+", args.database):
        parser.error("仅允许本机独立 mes_lamp_clean_ 演练库")
    db = Database(args)
    if db.sql("SELECT DATABASE()") != args.database:
        raise RuntimeError("目标库身份不匹配")
    workshop = db.one("factory_workshop", "workshop_code='LAMP-WORKSHOP' AND deleted_flag=0 AND status='ENABLE'")
    operator = db.one("sys_user", "username='admin' AND deleted_flag=0")["user_id"]
    shifts = {
        code: db.one("org_shift", f"shift_code={literal(code)} AND deleted_flag=0")
        for code in ["LAMP-SHIFT-AM", "LAMP-SHIFT-PM"]
    }
    products = {
        code: db.one("mdm_product", f"product_code={literal(code)} AND deleted_flag=0 AND status='ENABLE'")
        for code in ["LAMP-L100", "LAMP-L200"]
    }
    routes = {
        code: db.one("process_route", f"product_id={p['product_id']} AND status='RELEASED' AND deleted_flag=0")
        for code, p in products.items()
    }
    operations = {
        code: db.one("process_operation", f"operation_code={literal(code)} AND deleted_flag=0")
        for code in ["LAMP-OP-F10", "LAMP-OP-F20"]
    }
    categories = {
        code: db.one("eam_equipment_category", f"category_code={literal(code)} AND deleted_flag=0")
        for code in ["LAMP-EQCAT-TEST", "LAMP-EQCAT-AGING"]
    }
    for route in routes.values():
        steps = db.rows("process_route_item", f"route_id={route['route_id']} AND deleted_flag=0")
        step_operation_ids = {step["operation_id"] for step in steps}
        expected_operation_ids = {operation["operation_id"] for operation in operations.values()}
        if step_operation_ids != expected_operation_ids:
            raise RuntimeError("实际路线工序已变化，需复核新增资源后再执行")
        if any(
            step["resource_type"] == "BATCH_EQUIPMENT"
            and (not step["batch_capacity"] or not step["batch_duration_minutes"])
            for step in steps
        ):
            raise RuntimeError("现有批次工时缺失，不自动猜测或改写")

    snapshot_tables = [
        "factory_line",
        "factory_station",
        "factory_station_operation",
        "eam_equipment",
        "mfg_line_product_capability",
        "mfg_line_calendar_rule",
    ]
    existing_snapshots = {table: db.rows(table) for table in snapshot_tables}
    statements = ["START TRANSACTION", f"SET @operator={operator}", "SET @now=NOW()"]
    added = []

    def ensure(table, key_column, key_value, fields, audit=True):
        existing = db.rows(table, f"{key_column}={literal(key_value)}")
        if existing:
            if len(existing) != 1 or existing[0].get("deleted_flag") != 0:
                raise RuntimeError(f"{table} 标识已存在或已删除，拒绝覆盖：{key_value}")
            for key, expected in fields.items():
                if str(existing[0].get(key)) != str(expected):
                    # MySQL decimal values may differ only in textual scale.
                    from decimal import Decimal, InvalidOperation

                    try:
                        equal = Decimal(str(existing[0].get(key))) == Decimal(str(expected))
                    except (InvalidOperation, TypeError):
                        equal = False
                    if not equal:
                        raise RuntimeError(f"{table}.{key} 已被修改，拒绝覆盖：{key_value}")
            return
        values = {
            key_column: literal(key_value),
            **{k: literal(v) for k, v in fields.items()},
            "created_by": "@operator",
            "created_time": "@now",
            "deleted_flag": "0",
        }
        if audit:
            values.update({"updated_by": "@operator", "updated_time": "@now", "version": "0"})
        statements.append(
            f"INSERT INTO `{table}` ({','.join('`' + k + '`' for k in values)}) VALUES ({','.join(values.values())})"
        )
        added.append({"table": table, "key": str(key_value)})

    # Resolve inserted IDs in the same transaction, never copying IDs or binding new devices to old stations.
    for line in LINES:
        remark = f"{MARKER}；{line['purpose']}；日产能为软件演练设计值，非现场实测；老化按原冻结路线配置"
        ensure(
            "factory_line",
            "line_code",
            line["code"],
            {
                "line_name": line["name"],
                "workshop_id": workshop["workshop_id"],
                "daily_capacity": line["capacity"],
                "status": "RUNNING",
                "remark": remark,
            },
        )
        statements.append(
            f"SET @line=(SELECT line_id FROM factory_line WHERE line_code={literal(line['code'])} AND deleted_flag=0)"
        )
        # Compound keys below are checked from the current database and expressed through @line for inserts.
        line_id = first_line_id(db, line["code"])

        def ensure_link(table, keys, fields, line_id, line_code, audit=True):
            existing = (
                []
                if line_id is None
                else db.rows(
                    table, " AND ".join(f'{k}={literal(line_id if v == "@line" else v)}' for k, v in keys.items())
                )
            )
            if existing:
                if len(existing) != 1 or existing[0]["deleted_flag"] != 0:
                    raise RuntimeError(f"{table} 关系异常，拒绝修改")
                for k, v in fields.items():
                    if str(existing[0].get(k)) != str(v):
                        from decimal import Decimal, InvalidOperation

                        try:
                            if Decimal(str(existing[0].get(k))) == Decimal(str(v)):
                                continue
                        except InvalidOperation:
                            pass
                        raise RuntimeError(f"{table}.{k} 已修改，拒绝覆盖")
                return
            values = {k: v if v == "@line" else literal(v) for k, v in keys.items()}
            values.update({k: literal(v) for k, v in fields.items()})
            values.update({"created_by": "@operator", "created_time": "@now", "deleted_flag": "0"})
            if audit:
                values.update({"updated_by": "@operator", "updated_time": "@now", "version": "0"})
            statements.append(
                f"INSERT INTO `{table}` ({','.join('`' + k + '`' for k in values)}) "
                f"VALUES ({','.join(values.values())})"
            )
            added.append({"table": table, "key": line_code})

        for code in line["products"]:
            ensure_link(
                "mfg_line_product_capability",
                {"line_id": "@line", "product_id": products[code]["product_id"], "route_id": routes[code]["route_id"]},
                {"priority_level": line["priority"], "capacity_factor": 1, "status": "ENABLE", "remark": MARKER},
                line_id,
                line["code"],
            )
        for day in line["days"]:
            for shift in line["shifts"]:
                ensure_link(
                    "mfg_line_calendar_rule",
                    {
                        "line_id": "@line",
                        "day_of_week": day,
                        "shift_id": shifts[shift]["shift_id"],
                        "effective_from": "2026-10-26",
                    },
                    {"capacity_factor": 1, "status": "ENABLE", "remark": MARKER},
                    line_id,
                    line["code"],
                )

        for step, operation_code, station_type, category_code in [
            ("F10", "LAMP-OP-F10", "PRODUCTION", "LAMP-EQCAT-TEST"),
            ("F20", "LAMP-OP-F20", "INSPECTION", "LAMP-EQCAT-AGING"),
        ]:
            station_code = line["code"].replace("LINE", "ST") + "-" + step
            existing_station = db.rows("factory_station", f"station_code={literal(station_code)}")
            if existing_station:
                if (
                    len(existing_station) != 1
                    or existing_station[0]["line_id"] != line_id
                    or existing_station[0]["deleted_flag"] != 0
                ):
                    raise RuntimeError("工位标识占用或所属产线错误：" + station_code)
                if existing_station[0]["status"] != "RUNNING":
                    raise RuntimeError("已有工位状态变化，不自动启用：" + station_code)
            else:
                station_name = line["name"] + ("总装与功能确认工位" if step == "F10" else "终检内老化工位")
                statements.append(
                    "INSERT INTO factory_station (station_code,station_name,line_id,station_type,status,"
                    "remark,version,created_by,created_time,updated_by,updated_time,deleted_flag) "
                    f"VALUES ({literal(station_code)},{literal(station_name)},@line,{literal(station_type)},"
                    f"'RUNNING',{literal(MARKER)},0,@operator,@now,@operator,@now,0)"
                )
                added.append({"table": "factory_station", "key": station_code})
            statements.append(
                "SET @station=(SELECT station_id FROM factory_station "
                f"WHERE station_code={literal(station_code)} AND deleted_flag=0)"
            )
            operation_id = operations[operation_code]["operation_id"]
            bindings = (
                []
                if not existing_station
                else db.rows(
                    "factory_station_operation",
                    f"station_id={existing_station[0]['station_id']} AND operation_id={operation_id}",
                )
            )
            if bindings:
                if len(bindings) != 1 or bindings[0]["deleted_flag"] != 0 or bindings[0]["status"] != "ENABLE":
                    raise RuntimeError("工位工序关联被修改，拒绝覆盖：" + station_code)
            else:
                statements.append(
                    "INSERT INTO factory_station_operation (station_id,operation_id,is_default,"
                    "status,created_by,created_time,deleted_flag) "
                    f"VALUES (@station,{operation_id},1,'ENABLE',@operator,@now,0)"
                )
                added.append({"table": "factory_station_operation", "key": station_code})
            equipment_code = line["code"].replace("LINE", "EQ") + ("-TEST" if step == "F10" else "-AGING")
            devices = db.rows("eam_equipment", f"equipment_code={literal(equipment_code)}")
            if devices:
                if not describes_same_station(devices, existing_station):
                    raise RuntimeError("设备关联异常：" + equipment_code)
            else:
                equipment_name = line["name"] + (
                    "功能功率测试台（演练）" if step == "F10" else "老化架（演练，按路线批次配置）"
                )
                statements.append(
                    "INSERT INTO eam_equipment (equipment_code,equipment_name,category_id,station_id,"
                    "status,remark,version,created_by,created_time,updated_by,updated_time,"
                    "deleted_flag) "
                    f"VALUES ({literal(equipment_code)},{literal(equipment_name)},"
                    f"{categories[category_code]['category_id']},@station,'IDLE',"
                    f"{literal(MARKER + '；软件资源记录，无设备采集接口')},"
                    "0,@operator,@now,@operator,@now,0)"
                )
                added.append({"table": "eam_equipment", "key": equipment_code})

    report = {
        "database": args.database,
        "port": args.port,
        "apply": args.apply,
        "lines": [{**line, "days": list(line["days"])} for line in LINES],
        "newRows": added,
        "newRowCount": len(added),
        "scope": "只补产线/能力/周历/工位/工序关联/设备；保留现有资源和生产实绩",
    }
    if args.apply and added:
        directory = ROOT / "tmp/backups" / ("lamp-multiline-" + local_now().strftime("%Y%m%d-%H%M%S-%f"))
        directory.mkdir(parents=True, exist_ok=False)
        report["backup"] = db.backup(directory)
        (directory / "before-master.json").write_text(
            json.dumps(existing_snapshots, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        statements.append(
            "INSERT INTO sys_operation_log (user_id,module_code,biz_id,operation_type,remark,"
            "operation_time,version,created_by,created_time,updated_by,updated_time,deleted_flag) "
            "VALUES (@operator,'FACTORY_LINE',NULL,'EXPAND_LAMP_LINES',"
            f"{literal(MARKER + '；用户要求增加L100/L200专线和兼容线，仅演练配置，原业务保留')},"
            "@now,0,@operator,@now,@operator,@now,0)"
        )
        statements.append("COMMIT")
        (directory / "applied.sql").write_text(";\n".join(statements) + ";\n", encoding="utf-8")
        db.sql(";\n".join(statements) + ";")
        # All original master records must retain their previous values.
        for table, rows in existing_snapshots.items():
            pk = (
                "id"
                if table == "factory_station_operation"
                else {
                    "factory_line": "line_id",
                    "factory_station": "station_id",
                    "eam_equipment": "equipment_id",
                    "mfg_line_product_capability": "capability_id",
                    "mfg_line_calendar_rule": "calendar_rule_id",
                }[table]
            )
            actual = {row[pk]: row for row in db.rows(table)}
            if any(actual.get(row[pk]) != row for row in rows):
                raise RuntimeError("发现既有配置并发变化，请查看备份核对：" + table)
        report["existingMastersUnchanged"] = True
        (directory / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        LOGGER.info("%s", "配置完成；备份与结果：" + str(directory.relative_to(ROOT)))
    write_json(report, ensure_ascii=False, indent=2)


if __name__ == "__main__":
    main()
