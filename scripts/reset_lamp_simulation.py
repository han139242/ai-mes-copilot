"""清空本机台灯演练交易，保留主数据/排班/审计，并重建原材料期初库存。

默认只读预览；--apply 须先停止后端，完整备份后在一个事务中重置。
不会修改其他数据库，不重置业务主键或已使用的单号流水。
"""

import argparse
import datetime as dt
from decimal import Decimal
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import pymysql
from cli_output import write_json

ROOT = Path(__file__).resolve().parents[1]
# 显式清单，绝不按前缀整批删除主数据或日期排班。
CLEAR_TABLES = (
    "sys_notification_recipient",
    "sys_notification",
    "sys_event_outbox",
    "sys_approval_record",
    "ai_alert_action",
    "ai_alert_event",
    "ai_alert_scan",
    "ai_inventory_risk",
    "ai_anomaly_scan",
    "ai_automation_step",
    "ai_automation_run",
    "ai_task_step",
    "ai_task_instance",
    "ai_command_step",
    "ai_command_execution",
    "ai_command_sequence",
    "trace_material_usage",
    "trace_barcode",
    "trace_product_lot",
    "qms_nonconforming_detail",
    "qms_nonconforming_order",
    "qms_inspection_result",
    "qms_inspection_order",
    "mfg_report_material",
    "mfg_work_report",
    "mfg_task_operator",
    "mfg_task",
    "mfg_dispatch_order",
    "mfg_supply_allocation",
    "mfg_supply_source",
    "mfg_work_order_material",
    "mfg_command_request",
    "mfg_generate_request",
    "mfg_exception",
    "mfg_andon_alert",
    "mfg_schedule_item",
    "mfg_schedule_scenario",
    "mfg_work_order",
    "mfg_production_plan",
    "mfg_production_order",
    "mfg_sales_order_detail",
    "mfg_sales_order",
    "mfg_purchase_order_detail",
    "mfg_purchase_order",
    "wms_return_detail",
    "wms_return_order",
    "wms_outbound_detail",
    "wms_outbound_order",
    "wms_inbound_detail",
    "wms_inbound_order",
    "wms_stocktake_detail",
    "wms_stocktake_order",
    "wms_replenishment_suggestion",
    "wms_stock_movement",
    "wms_inventory",
    "wms_material_batch",
    "sys_document_number_alias",
)
REBUILT = {"wms_material_batch", "wms_inventory", "wms_stock_movement"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", required=True)
    parser.add_argument("--port", type=int, choices=[3310], default=3310)
    parser.add_argument("--user", default="root")
    parser.add_argument("--stock", type=Decimal, default=Decimal("1000"))
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    if not re.fullmatch(r"mes_lamp_(?:clean|reset_verify)_[A-Za-z0-9_]+", args.database):
        parser.error("仅允许本机台灯业务库或专用重置验证库")
    if args.stock <= 0 or args.stock > Decimal("99999999999999.9999"):
        parser.error("期初数量必须为正数且符合数据库精度")
    runtime_path = ROOT / "tmp/local-runtime.json"
    if args.apply and runtime_path.is_file():
        runtime = json.loads(runtime_path.read_text(encoding="utf-8-sig"))
        if runtime.get("database", {}).get("name") == args.database:
            try:
                connection = socket.create_connection(("127.0.0.1", runtime["backend"]["port"]), timeout=1)
            except OSError:
                pass
            else:
                connection.close()
                parser.error("当前业务后端仍在监听，请先停止后端再重置，避免并发写入")
    db = pymysql.connect(
        host="127.0.0.1",
        port=args.port,
        user=args.user,
        password=os.environ.get("MYSQL_PWD", ""),
        database=args.database,
        charset="utf8mb4",
        cursorclass=pymysql.cursors.DictCursor,
        autocommit=True,
    )

    def rows(sql, params=()):
        with db.cursor() as c:
            c.execute(sql, params)
            return c.fetchall()

    def digest(table, maximum=None):
        primary = rows(
            "SELECT column_name FROM information_schema.key_column_usage WHERE table_schema=DATABASE() AND"
            " table_name=%s AND constraint_name='PRIMARY' ORDER BY ordinal_position",
            (table,),
        )
        if len(primary) != 1:
            raise ValueError("不支持非单主键表：" + table)
        pk = primary[0]["COLUMN_NAME"]
        data = rows(
            "SELECT * FROM `"
            + table
            + "`"
            + (" WHERE `" + pk + "`<=%s" if maximum is not None else "")
            + " ORDER BY `"
            + pk
            + "`",
            (maximum,) if maximum is not None else (),
        )
        return {
            "rows": len(data),
            "sha256": hashlib.sha256(
                json.dumps(data, sort_keys=True, default=str, ensure_ascii=False).encode("utf8")
            ).hexdigest(),
        }

    def insert(table, values):
        with db.cursor() as c:
            c.execute(
                "INSERT INTO `"
                + table
                + "` ("
                + ",".join("`" + key + "`" for key in values)
                + ") VALUES ("
                + ",".join(["%s"] * len(values))
                + ")",
                tuple(values.values()),
            )
            return c.lastrowid

    locked = False
    try:
        if args.apply:
            locked = rows("SELECT GET_LOCK(%s,0) acquired", (args.database + ":reset-simulation",))[0]["acquired"] == 1
            if not locked:
                raise RuntimeError("另一重置正在进行，未修改数据")
        tables = {next(iter(r.values())) for r in rows("SHOW TABLES")}
        if not set(CLEAR_TABLES).issubset(tables):
            raise ValueError("先补齐结构迁移，缺少表：" + ",".join(sorted(set(CLEAR_TABLES) - tables)))
        engines = rows("SELECT table_name,engine FROM information_schema.tables WHERE table_schema=DATABASE()")
        if any(t["ENGINE"] != "InnoDB" for t in engines):
            raise ValueError("所有表须支持事务，未执行重置")
        admins = rows("SELECT user_id FROM sys_user WHERE username='admin' AND status='ENABLE' AND deleted_flag=0")
        if len(admins) != 1:
            raise ValueError("需要唯一有效本地管理员")
        actor = admins[0]["user_id"]
        warehouses = rows(
            "SELECT warehouse_id,warehouse_name FROM wms_warehouse WHERE warehouse_type='RAW' AND"
            " status='ENABLE' AND deleted_flag=0"
        )
        if len(warehouses) != 1:
            raise ValueError("本脚本要求唯一启用原料仓，避免多仓时猜测期初位置")
        warehouse = warehouses[0]
        locations = rows(
            "SELECT location_id FROM wms_location WHERE warehouse_id=%s AND deleted_flag=0 AND"
            " location_type='NORMAL' AND status IN ('IDLE','OCCUPIED','ENABLE') ORDER BY location_id",
            (warehouse["warehouse_id"],),
        )
        if not locations:
            raise ValueError("原料仓缺少可用正常库位")
        material_rows = rows(
            "SELECT material_id,material_code,material_name,quantity_mode,issue_step FROM mdm_material WHERE"
            " deleted_flag=0 AND status='ENABLE' AND material_type IN ('RAW','AUXILIARY') ORDER BY"
            " material_code,material_id"
        )
        if not material_rows:
            raise ValueError("没有启用的原材料或辅料")
        for material in material_rows:
            step = material["issue_step"] or (
                Decimal(1) if material["quantity_mode"] == "DISCRETE" else Decimal("0.0001")
            )
            if step <= 0 or args.stock % step:
                raise ValueError(material["material_name"] + "的期初数量不符合发料粒度")
        counts = {table: rows("SELECT COUNT(*) n FROM `" + table + "`")[0]["n"] for table in CLEAR_TABLES}
        report = {
            "database": args.database,
            "mode": "apply" if args.apply else "preview",
            "tables": counts,
            "materialCount": len(material_rows),
            "openingQuantityEach": str(args.stock),
            "warehouse": warehouse,
            "preserves": "所有主数据、日期排班、登录权限、工艺确认、历史操作/状态审计、业务主键和单号流水",
            "clears": "所有生产、销售采购、仓储、质检、追溯交易及其预留、幂等、通知、AI执行与旧号映射；线边/半成品/成品库存清零",
        }
        if not args.apply:
            write_json(report, ensure_ascii=False, indent=2)
            return

        stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S-%f")
        run = ROOT / "tmp/backups" / ("simulation-reset-" + stamp)
        run.mkdir(parents=True)
        binary = Path(os.environ.get("MYSQL_BIN", r"C:\Program Files\MySQL\MySQL Server 8.0\bin")) / "mysqldump.exe"
        backup = run / (args.database + ".sql")
        subprocess.run(
            [
                str(binary),
                "--no-defaults",
                "--protocol=TCP",
                "--host=127.0.0.1",
                "--port=" + str(args.port),
                "--user=" + args.user,
                "--default-character-set=utf8mb4",
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
            raise RuntimeError("备份不完整，没有执行删除")
        report["backup"] = {
            "file": str(backup.relative_to(ROOT)),
            "sha256": hashlib.sha256(backup.read_bytes()).hexdigest(),
        }
        kept = tables - set(CLEAR_TABLES) - {"sys_operation_log"}
        before = {table: digest(table) for table in sorted(kept)}
        old_log_max = rows("SELECT COALESCE(MAX(log_id),0) maximum FROM sys_operation_log")[0]["maximum"]
        old_log_digest = digest("sys_operation_log", old_log_max)
        now = dt.datetime.now().replace(microsecond=0)
        audit = dict(version=0, created_by=actor, created_time=now, updated_by=actor, updated_time=now, deleted_flag=0)
        opening = []
        db.begin()
        try:
            with db.cursor() as c:
                for table in CLEAR_TABLES:
                    c.execute("DELETE FROM `" + table + "`")
            for index, material in enumerate(material_rows, 1):
                batch_no = "RM-" + now.strftime("%Y%m%d") + "-" + str(index).zfill(3)
                batch = insert(
                    "wms_material_batch",
                    dict(
                        audit,
                        batch_no=batch_no,
                        material_id=material["material_id"],
                        received_date=now.date(),
                        quality_status="PASS",
                        status="AVAILABLE",
                        source_order_type="DEMO_RESET",
                        source_order_id=None,
                        source_product_lot_id=None,
                        remark="演练重置期初批次；非真实采购到货或实测检验；" + stamp,
                    ),
                )
                inventory = insert(
                    "wms_inventory",
                    dict(
                        audit,
                        warehouse_id=warehouse["warehouse_id"],
                        location_id=locations[0]["location_id"],
                        material_id=material["material_id"],
                        batch_id=batch,
                        work_order_id=0,
                        available_quantity=args.stock,
                        locked_quantity=0,
                        inspection_quantity=0,
                        remark="用户授权演练重置期初备料；" + stamp,
                    ),
                )
                insert(
                    "wms_stock_movement",
                    dict(
                        audit,
                        movement_no="RESET-" + stamp + "-" + str(index),
                        movement_type="ADJUST",
                        source_order_type="DEMO_RESET",
                        source_order_id=inventory,
                        material_id=material["material_id"],
                        batch_id=batch,
                        warehouse_id=warehouse["warehouse_id"],
                        location_id=locations[0]["location_id"],
                        work_order_id=0,
                        quantity=args.stock,
                        direction="IN",
                        operation_time=now,
                        remark="演练重置期初余额；旧交易已完整备份",
                    ),
                )
                opening.append(
                    dict(
                        materialId=material["material_id"],
                        materialCode=material["material_code"],
                        materialName=material["material_name"],
                        batchId=batch,
                        inventoryId=inventory,
                        available=str(args.stock),
                    )
                )
            insert(
                "sys_operation_log",
                dict(
                    user_id=actor,
                    operation_type="RESET_SIMULATION",
                    module_code="SIMULATION",
                    biz_id=0,
                    remark="用户授权清空流程交易；每种原料期初="
                    + str(args.stock)
                    + "；备份="
                    + str(backup.relative_to(ROOT)),
                    operation_time=now,
                    version=0,
                    created_by=actor,
                    created_time=now,
                    updated_by=actor,
                    updated_time=now,
                    deleted_flag=0,
                ),
            )
            for table in CLEAR_TABLES:
                count = rows("SELECT COUNT(*) n FROM `" + table + "`")[0]["n"]
                expected = len(material_rows) if table in REBUILT else 0
                if count != expected:
                    raise AssertionError((table, count, expected))
            for table, original in before.items():
                if digest(table) != original:
                    raise AssertionError("保留资料发生意外变化：" + table)
            if digest("sys_operation_log", old_log_max) != old_log_digest:
                raise AssertionError("历史审计被改写")
            invalid = rows(
                "SELECT COUNT(*) n FROM wms_inventory WHERE available_quantity<>%s OR locked_quantity<>0 OR"
                " inspection_quantity<>0 OR work_order_id<>0 OR warehouse_id<>%s",
                (args.stock, warehouse["warehouse_id"]),
            )[0]["n"]
            if invalid:
                raise AssertionError("期初库存校验失败")
            mismatch = rows(
                "SELECT COUNT(*) n FROM wms_inventory i LEFT JOIN wms_stock_movement m ON"
                " m.source_order_type='DEMO_RESET' AND m.source_order_id=i.inventory_id AND"
                " m.material_id=i.material_id AND m.batch_id=i.batch_id AND m.location_id=i.location_id AND"
                " m.warehouse_id=i.warehouse_id AND m.direction='IN' WHERE m.movement_id IS NULL OR"
                " m.quantity<>i.available_quantity"
            )[0]["n"]
            if mismatch:
                raise AssertionError("期初余额与逐笔库存流水不一致")
            db.commit()
        except Exception:
            db.rollback()
            raise
        report.update(
            opening=opening,
            preservedTables=len(before),
            preservedDigests=before,
            clearedTablesVerified=len(CLEAR_TABLES) - len(REBUILT),
            historicalAuditPreserved=True,
            quantityLedgerMatches=True,
            primaryKeysAndNumberCountersNotReset=True,
        )
        (run / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2, default=str), encoding="utf8")
        write_json(report, ensure_ascii=False, indent=2, default=str)
    finally:
        if locked:
            rows("SELECT RELEASE_LOCK(%s)", (args.database + ":reset-simulation",))
        db.close()


if __name__ == "__main__":
    main()
