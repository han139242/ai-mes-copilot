"""预览/转换独立台灯演练库的旧业务单号，保留旧号映射与原条码。

默认只读；--apply 必须在应用停止写入后执行，先完整备份，再事务内转换。
客户前缀可通过 --customer-prefix 客户编码=前缀 指定，数据库口令读取 MYSQL_PWD。
"""

import argparse
from collections import defaultdict
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
from zoneinfo import ZoneInfo
import pymysql

ROOT = Path(__file__).resolve().parents[1]
CURRENT = re.compile(r"^(?P<prefix>[A-Z][A-Z0-9]{0,7})-(?P<kind>[A-Z]{2,3})-(?P<date>\d{8})-(?P<sequence>\d{3,})$")
DOCS = [
    ("mfg_sales_order", "sales_order_id", "sales_order_no", "SO"),
    ("mfg_production_order", "production_order_id", "production_order_no", "PO"),
    ("mfg_production_plan", "plan_id", "plan_no", "PL"),
    ("mfg_work_order", "work_order_id", "work_order_no", "WO"),
    ("mfg_dispatch_order", "dispatch_id", "dispatch_no", "DO"),
    ("mfg_task", "task_id", "task_no", "TK"),
    ("mfg_work_report", "report_id", "report_no", "WR"),
    ("mfg_purchase_order", "purchase_order_id", "purchase_order_no", "PU"),
    ("wms_inbound_order", "inbound_id", "inbound_no", "IN"),
    ("wms_outbound_order", "outbound_id", "outbound_no", "OUT"),
    ("wms_return_order", "return_id", "return_no", "RT"),
    ("wms_stocktake_order", "stocktake_id", "stocktake_no", "ST"),
    ("wms_replenishment_suggestion", "suggestion_id", "suggestion_no", "RP"),
    ("qms_inspection_order", "inspection_id", "inspection_no", "QI"),
    ("qms_nonconforming_order", "ncr_id", "ncr_no", "NC"),
    ("trace_product_lot", "product_lot_id", "lot_no", "LP"),
    ("mfg_schedule_scenario", "scenario_id", "scenario_no", "SC"),
]


def business_date(row, number):
    if row.get("created_time"):
        return row["created_time"].date()
    for candidate in re.findall(r"20\d{6}", number or ""):
        try:
            return dt.datetime.strptime(candidate, "%Y%m%d").date()
        except ValueError:
            pass
    return dt.datetime.now(ZoneInfo("Asia/Shanghai")).date()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", required=True)
    parser.add_argument("--port", required=True, type=int)
    parser.add_argument("--user", default="root")
    parser.add_argument("--customer-prefix", action="append", default=[])
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    if not re.fullmatch(r"mes_lamp_[A-Za-z0-9_]+", args.database) or args.port not in (3307, 3310, 3311):
        parser.error("只允许本机独立台灯演练库")
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

    def rows(sql, params=None):
        with db.cursor() as c:
            c.execute(sql, params)
            return list(c.fetchall())

    with db.cursor() as c:
        c.execute("SELECT GET_LOCK(%s,10)", (args.database + ":compact-business-numbers",))
        if list(c.fetchone().values())[0] != 1:
            raise RuntimeError("另一个编号转换正在运行")
    report = {"database": args.database, "mode": "apply" if args.apply else "preview", "customers": [], "mapping": []}
    try:
        if args.apply:
            folder = ROOT / "tmp/backups" / ("business-numbers-" + dt.datetime.now().strftime("%Y%m%d-%H%M%S-%f"))
            folder.mkdir(parents=True)
            backup = folder / (args.database + ".sql")
            binary = Path(os.environ.get("MYSQL_BIN", r"C:\Program Files\MySQL\MySQL Server 8.0\bin"))
            common = [
                "--no-defaults",
                "--protocol=TCP",
                "--host=127.0.0.1",
                "--port=" + str(args.port),
                "--user=" + args.user,
                "--default-character-set=utf8mb4",
            ]
            subprocess.run(
                [str(binary / "mysqldump.exe")]
                + common
                + [
                    "--single-transaction",
                    "--skip-lock-tables",
                    "--no-tablespaces",
                    "--set-gtid-purged=OFF",
                    "--result-file=" + str(backup),
                    args.database,
                ],
                capture_output=True,
                check=True,
            )
            if b"-- Dump completed on " not in backup.read_bytes()[-600:]:
                raise RuntimeError("备份不完整，停止修改")
            report["backup"] = {
                "file": str(backup.relative_to(ROOT)),
                "sha256": hashlib.sha256(backup.read_bytes()).hexdigest(),
            }
            migration = (ROOT / "workspace/idea/ems/src/main/resources/db/business_number_v1.sql").read_text(
                encoding="utf8"
            )
            result = subprocess.run(
                [str(binary / "mysql.exe")] + common + ["--database=" + args.database],
                input=migration,
                capture_output=True,
                text=True,
                encoding="utf8",
                timeout=120,
            )
            if result.returncode:
                raise RuntimeError(result.stderr)
            db.begin()
        customers = rows("SELECT * FROM mdm_customer")
        by_code = {r["customer_code"]: r for r in customers}
        overrides = {}
        for item in args.customer_prefix:
            code, prefix = item.split("=", 1)
            prefix = prefix.strip().upper()
            if code not in by_code or not re.fullmatch(r"[A-Z][A-Z0-9]{0,7}", prefix) or prefix == "SYS":
                raise ValueError("客户或前缀不正确：" + item)
            overrides[code] = prefix
        prefixes = {}
        for customer in customers:
            cid = customer["customer_id"]
            # 与 Java 默认前缀一致：客户主键的 36 进制短码。
            value = cid
            digits = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
            encoded = ""
            while value:
                encoded = digits[value % 36] + encoded
                value //= 36
            prefix = overrides.get(customer["customer_code"]) or customer.get("number_prefix") or "C" + (encoded or "0")
            prefix = prefix.strip().upper()
            if not re.fullmatch(r"[A-Z][A-Z0-9]{0,7}", prefix) or prefix == "SYS":
                raise ValueError("客户前缀格式不正确：" + customer["customer_code"])
            if prefix in prefixes.values():
                raise ValueError("客户前缀重复：" + prefix)
            prefixes[cid] = prefix
            report["customers"].append(
                {"id": cid, "code": customer["customer_code"], "name": customer["customer_name"], "prefix": prefix}
            )
            if args.apply:
                with db.cursor() as c:
                    c.execute("UPDATE mdm_customer SET number_prefix=%s WHERE customer_id=%s", (prefix, cid))
        tables = {}
        for table, pk, _, _ in DOCS:
            tables[table] = {r[pk]: r for r in rows("SELECT * FROM `" + table + "`")}

        def customer_of(table, row, seen=None):
            if not row:
                return None
            seen = set() if seen is None else set(seen)
            marker = (table, row.get(next(pk for name, pk, _, _ in DOCS if name == table)))
            if marker in seen:
                return None
            seen.add(marker)
            if row.get("customer_id"):
                return row["customer_id"]
            links = {
                "mfg_production_plan": [("mfg_production_order", "production_order_id")],
                "mfg_work_order": [("mfg_production_plan", "plan_id")],
                "mfg_dispatch_order": [("mfg_work_order", "work_order_id")],
                "mfg_task": [("mfg_work_order", "work_order_id")],
                "mfg_work_report": [("mfg_task", "task_id")],
                "wms_return_order": [("mfg_work_order", "work_order_id")],
                "wms_outbound_order": [
                    ("mfg_production_order", "source_production_order_id"),
                    ("mfg_work_order", "work_order_id"),
                ],
                "qms_inspection_order": [
                    ("mfg_work_order", "work_order_id"),
                    ("trace_product_lot", "product_lot_id"),
                    ("mfg_work_report", "report_id"),
                    ("mfg_task", "task_id"),
                ],
                "qms_nonconforming_order": [
                    ("qms_inspection_order", "inspection_id"),
                    ("trace_product_lot", "product_lot_id"),
                ],
                "trace_product_lot": [("mfg_work_order", "work_order_id")],
            }
            if table == "wms_inbound_order" and row.get("source_order_type") == "PRODUCT_LOT":
                inbound_lot = tables.get("trace_product_lot", {}).get(row.get("source_order_id"))
                return customer_of("trace_product_lot", inbound_lot, seen)
            for other, key in links.get(table, []):
                linked = tables.get(other, {}).get(row.get(key))
                customer = customer_of(other, linked, seen)
                if customer:
                    return customer
            if table == "qms_inspection_order":
                for outbound in tables.get("wms_outbound_order", {}).values():
                    if outbound.get("inspection_id") == row.get("inspection_id"):
                        customer = customer_of("wms_outbound_order", outbound, seen)
                        if customer:
                            return customer
            return None

        counters = defaultdict(int)
        exists = rows(
            "SELECT COUNT(*) n FROM information_schema.tables WHERE table_schema=DATABASE() AND"
            " table_name='sys_document_sequence'"
        )[0]["n"]
        if exists:
            for r in rows("SELECT * FROM sys_document_sequence"):
                counters[(r["scope_prefix"], r["document_type"], r["business_date"])] = r["current_value"]
        for table, pk, col, kind in DOCS:
            for row in tables[table].values():
                match = CURRENT.fullmatch(row[col] or "")
                if match:
                    key = (match["prefix"], match["kind"], dt.datetime.strptime(match["date"], "%Y%m%d").date())
                    counters[key] = max(counters[key], int(match["sequence"]))
        for table, pk, col, kind in DOCS:
            reserved = {r[col] for r in tables[table].values() if r[col]}
            for row in sorted(tables[table].values(), key=lambda r: (business_date(r, r[col]), r[pk])):
                old = row[col]
                actual = kind
                if table == "wms_outbound_order":
                    actual = (
                        "SI"
                        if row.get("source_production_order_id") or row.get("outbound_type") == "SALES"
                        else ("MI" if row.get("work_order_id") else "OUT")
                    )
                match = CURRENT.fullmatch(old or "")
                if match and match["kind"] == actual:
                    continue
                # AI 批量方案号兼具重放标识，保持原值；手工方案采用短号。
                if table == "mfg_schedule_scenario" and old and old.startswith("AI-BATCH-"):
                    continue
                prefix = prefixes.get(customer_of(table, row), "SYS")
                date = business_date(row, old)
                key = (prefix, actual, date)
                while True:
                    counters[key] += 1
                    new = f"{prefix}-{actual}-{date:%Y%m%d}-{counters[key]:03d}"
                    if new not in reserved:
                        break
                reserved.add(new)
                change = {"table": table, "id": row[pk], "type": actual, "old": old, "new": new}
                report["mapping"].append(change)
                if args.apply:
                    with db.cursor() as c:
                        c.execute(
                            "UPDATE `"
                            + table
                            + "` SET `"
                            + col
                            + "`=%s WHERE `"
                            + pk
                            + "`=%s AND `"
                            + col
                            + "` <=> %s",
                            (new, row[pk], old),
                        )
                        if c.rowcount != 1:
                            raise RuntimeError("单据已被并发修改，停止转换")
                        if old:
                            c.execute(
                                "INSERT INTO sys_document_number_alias("
                                "document_type,biz_id,original_number,current_number,changed_time"
                                ") VALUES(%s,%s,%s,%s,NOW())",
                                (actual, row[pk], old, new),
                            )
        if args.apply:
            with db.cursor() as c:
                for (prefix, kind, date), value in counters.items():
                    c.execute(
                        "INSERT INTO sys_document_sequence(scope_prefix,document_type,business_date,current_value"
                        ",updated_time) VALUES(%s,%s,%s,%s,NOW()) ON DUPLICATE KEY UPDATE"
                        " current_value=GREATEST(current_value,VALUES(current_value)),updated_time=NOW()",
                        (prefix, kind, date, value),
                    )
            db.commit()
        report["converted"] = len(report["mapping"])
        print(json.dumps(report, ensure_ascii=False, default=str, indent=2))
    except BaseException:
        db.rollback()
        raise
    finally:
        with db.cursor() as c:
            c.execute("SELECT RELEASE_LOCK(%s)", (args.database + ":compact-business-numbers",))
        db.close()


if __name__ == "__main__":
    main()
