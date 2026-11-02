"""以源码结构新建纯台灯基线；默认预览，不删除或改写来源库。"""

import argparse
import hashlib
import json
import logging
import os
import re
import shutil
import subprocess
from datetime import datetime, timezone
from pathlib import Path
from lamp_migrations import CLEAN_BASELINE_MIGRATIONS


def local_now():
    """当前本地时间：显式取带时区的时间再去掉时区，不依赖进程默认时区配置。"""
    return datetime.now(tz=timezone.utc).astimezone().replace(tzinfo=None)


logging.basicConfig(level=logging.INFO, format="%(message)s")
LOGGER = logging.getLogger(__name__)

ROOT = Path(__file__).resolve().parents[1]
LEGACY = "羽毛球|羽球|badminton|雷尼克斯|RENIX|训练球|比赛球|鹅毛|鸭毛|植毛|PROD-LNX|MAT-LNX|OEM-XY|T300|M700|C500"
SYSTEM_TABLES = ["sys_role", "sys_menu", "sys_role_menu", "sys_dict_type", "sys_dict_data"]
BUSINESS_TABLES = [
    "mfg_production_order",
    "mfg_production_plan",
    "mfg_work_order",
    "mfg_work_order_material",
    "mfg_dispatch_order",
    "mfg_task",
    "mfg_task_operator",
    "mfg_work_report",
    "mfg_report_material",
    "mfg_supply_allocation",
    "mfg_supply_source",
    "mfg_generate_request",
    "mfg_command_request",
    "mfg_exception",
    "mfg_andon_alert",
    "wms_material_batch",
    "wms_inventory",
    "wms_stock_movement",
    "wms_inbound_order",
    "wms_inbound_detail",
    "wms_outbound_order",
    "wms_outbound_detail",
    "wms_return_order",
    "wms_return_detail",
    "wms_stocktake_order",
    "wms_stocktake_detail",
    "wms_replenishment_suggestion",
    "qms_inspection_order",
    "qms_inspection_result",
    "qms_nonconforming_order",
    "qms_nonconforming_detail",
    "trace_barcode",
    "trace_product_lot",
    "trace_material_usage",
    "ai_command_execution",
    "ai_command_step",
    "ai_task_instance",
    "ai_task_step",
    "ai_automation_run",
    "ai_automation_step",
    "ai_inventory_risk",
    "ai_anomaly_scan",
    "sys_notification",
    "sys_notification_recipient",
    "sys_event_outbox",
    "org_team_schedule",
]


MASTER_COUNT_TABLES = [
    "mdm_product",
    "mdm_material",
    "mdm_bom",
    "process_route",
    "factory_line",
    "eam_equipment",
    "wms_warehouse",
]


def count_rows(sql, target, wanted, existing):
    """统计目标库里若干张表的有效记录数。

    只统计目标库真实存在的表：切换前的目标库是从零建的，某些表可能还没建出来，
    直接查会报错。用显式循环而不是推导式，是为了让「查哪些表、跳过哪些表」一眼可读。
    """
    totals = {}
    for table in wanted:
        if table in existing:
            totals[table] = int(sql("SELECT COUNT(*) FROM `" + table + "`", target))
    return totals


def count_alive_rows(sql, target, wanted):
    """统计目标库里若干张表未软删除的记录数。"""
    totals = {}
    for table in wanted:
        totals[table] = int(sql("SELECT COUNT(*) FROM `" + table + "` WHERE deleted_flag=0", target))
    return totals


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-database", required=True)
    parser.add_argument("--database", required=True)
    parser.add_argument("--port", type=int, choices=[3307, 3310], default=3310)
    parser.add_argument("--user", default="root")
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    for name in [args.source_database, args.database]:
        if not re.fullmatch(r"mes_lamp_[A-Za-z0-9_]+", name):
            parser.error("仅允许本机独立mes_lamp_库名")
    if args.source_database == args.database or not args.database.startswith("mes_lamp_clean_"):
        parser.error("目标须为新的mes_lamp_clean_库，且不能与来源相同")
    executable = (
        (Path(os.environ["MYSQL_BIN"]) / "mysql.exe")
        if os.environ.get("MYSQL_BIN")
        else Path(shutil.which("mysql") or "")
    )
    if not executable.is_file():
        parser.error("请通过MYSQL_BIN指定已有MySQL工具目录")
    common = [
        "--no-defaults",
        "--protocol=TCP",
        "--host=127.0.0.1",
        "--port=" + str(args.port),
        "--user=" + args.user,
        "--default-character-set=utf8mb4",
    ]

    def sql(statement, database):
        result = subprocess.run(
            [str(executable), *common, "--database=" + database, "--batch", "--skip-column-names"],
            input=statement,
            text=True,
            encoding="utf-8",
            capture_output=True,
            timeout=120,
        )
        if result.returncode:
            raise RuntimeError(result.stderr.strip())
        return result.stdout.strip()

    source = args.source_database
    target = args.database
    if sql("SELECT DATABASE()", source) != source:
        raise RuntimeError("来源身份不匹配")
    if sql("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='" + target + "'", source) != "0":
        raise RuntimeError("目标库已存在，拒绝覆盖；请选择新的目标名称")
    administrator = sql(
        "SELECT user_id FROM sys_user WHERE username='admin' AND deleted_flag=0 AND status='ENABLE'", source
    )
    if not administrator.isdigit():
        raise RuntimeError("来源必须包含唯一有效admin账号")
    report = {
        "source": source,
        "database": target,
        "port": args.port,
        "mode": "apply" if args.apply else "preview",
        "copyPolicy": "管理员登录和通用角色/菜单/字典；不复制业务数据、旧组织和人员",
        "resetHistoricalDatabase": False,
        "productionRecordsGenerated": False,
    }
    if not args.apply:
        LOGGER.info(json.dumps(report, ensure_ascii=False, indent=2))
        return
    run = ROOT / "tmp/backups" / ("lamp-clean-" + local_now().strftime("%Y%m%d-%H%M%S-%f"))
    run.mkdir(parents=True)
    dump = executable.with_name("mysqldump.exe" if executable.suffix == ".exe" else "mysqldump")

    def export(filename, extra):
        path = run / filename
        result = subprocess.run(
            [
                str(dump),
                *common,
                "--single-transaction",
                "--skip-lock-tables",
                "--no-tablespaces",
                "--set-gtid-purged=OFF",
                "--result-file=" + str(path),
                *extra,
                source,
            ],
            capture_output=True,
            timeout=120,
        )
        if result.returncode or not path.exists() or b"-- Dump completed on " not in path.read_bytes()[-500:]:
            raise RuntimeError("备份不完整，未继续创建目标库")
        return path

    backup = export("source-before-clean.sql", [])
    export("source-structure.sql", ["--no-data", "--skip-add-drop-table"])
    schema = (ROOT / "workspace/idea/ems/src/main/resources/db/schema.sql").read_text(encoding="utf-8-sig")
    schema, database_headers = re.subn(r"^CREATE DATABASE[^\n]*\n", "", schema, flags=re.M)
    if database_headers != 1 or re.search(r"^\s*USE\s|\bDROP TABLE\b", schema, re.M | re.I):
        raise RuntimeError("结构基线包含未预期的切库或删表语句，目标未创建")
    sql("CREATE DATABASE `" + target + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci", source)
    sql(schema, target)

    def columns(table):
        return sql(
            "SELECT column_name FROM information_schema.columns "
            "WHERE table_schema=DATABASE() AND table_name='" + table + "' "
            "ORDER BY ordinal_position",
            source,
        ).splitlines()

    def copy_configuration(table, condition="1=1"):
        names = columns(table)
        values = []
        for name in names:
            if name == "remark":
                values.append("'LAMP_CLEAN_BASELINE'")
            elif name == "employee_id":
                values.append("NULL")
            elif name in ["created_by", "updated_by"]:
                values.append(administrator)
            elif name in ["created_time", "updated_time"]:
                values.append("NOW()")
            elif name in ["login_fail_count"]:
                values.append("0")
            elif name in ["locked_time", "last_login_time"]:
                values.append("NULL")
            else:
                values.append("`" + name + "`")
        sql(
            "INSERT INTO `"
            + table
            + "` ("
            + ",".join("`" + name + "`" for name in names)
            + ") SELECT "
            + ",".join(values)
            + " FROM `"
            + source
            + "`.`"
            + table
            + "` WHERE "
            + condition,
            target,
        )

    copy_configuration("sys_user", "user_id=" + administrator)
    for table in SYSTEM_TABLES:
        copy_configuration(table)
    copy_configuration("sys_user_role", "user_id=" + administrator + " AND deleted_flag=0")
    folder = ROOT / "workspace/idea/ems/src/main/resources/db"
    seeds = list(CLEAN_BASELINE_MIGRATIONS)
    for name in seeds:
        sql((folder / name).read_text(encoding="utf-8-sig"), target)

    tables = sql("SHOW TABLES", target).splitlines()
    legacy = {}
    for table in tables:
        text_columns = sql(
            "SELECT column_name FROM information_schema.columns "
            "WHERE table_schema=DATABASE() AND table_name='" + table + "' "
            "AND data_type IN ('char','varchar','text','mediumtext','longtext')",
            target,
        ).splitlines()
        if not text_columns:
            continue
        expression = "CONCAT_WS(' '," + ",".join("`" + name + "`" for name in text_columns) + ")"
        count = int(
            sql("SELECT COUNT(*) FROM `" + table + "` WHERE " + expression + " REGEXP '" + LEGACY + "'", target)
        )
        if count:
            legacy[table] = count
    report["legacyTextRows"] = legacy
    report["businessRecordCounts"] = count_rows(sql, target, BUSINESS_TABLES, tables)
    counts = count_alive_rows(sql, target, MASTER_COUNT_TABLES)
    report.update(
        {
            "tables": len(tables),
            "masterCounts": counts,
            "seedFiles": seeds,
            "schemaSource": "schema.sql",
            "backup": str(backup.relative_to(ROOT)),
            "backupSha256": hashlib.sha256(backup.read_bytes()).hexdigest(),
        }
    )
    report_file = run / "result.json"
    report_file.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    if legacy or any(report["businessRecordCounts"].values()):
        raise RuntimeError("纯台灯基线核对失败，目标未切换；查看" + str(report_file))
    if counts["mdm_product"] != 6 or counts["mdm_material"] != 24 or counts["mdm_bom"] != 6:
        raise RuntimeError("产品、物料或BOM基线数量不符合预期，目标未切换")
    if int(sql("SELECT COUNT(*) FROM mdm_product WHERE product_code NOT LIKE 'LAMP-%'", target)):
        raise RuntimeError("存在非台灯产品，目标未切换")
    LOGGER.info(json.dumps(report, ensure_ascii=False, indent=2))
    LOGGER.info("核对记录：" + str(report_file.relative_to(ROOT)))


if __name__ == "__main__":
    main()
