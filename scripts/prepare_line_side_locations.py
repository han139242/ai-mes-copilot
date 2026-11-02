"""为本机台灯演练产线补齐专用线边库位；默认只读，--apply 先完整备份。不移动现有库存。"""

import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import pymysql
from cli_output import write_json

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", required=True)
    parser.add_argument("--port", type=int, default=3310)
    parser.add_argument("--user", default="root")
    parser.add_argument("--warehouse-code")
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    if not re.fullmatch(r"mes_lamp_[A-Za-z0-9_]+", args.database) or args.port not in (3310, 3307, 3311):
        parser.error("仅允许本机独立台灯库")
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
    report = {"database": args.database, "mode": "apply" if args.apply else "preview", "created": []}
    try:
        with db.cursor() as cursor:
            cursor.execute(
                "SELECT * FROM wms_warehouse WHERE deleted_flag=0 AND warehouse_type='LINE_SIDE' AND status='ENABLE'"
                " ORDER BY warehouse_id"
            )
            warehouses = [
                entry
                for entry in cursor.fetchall()
                if not args.warehouse_code or entry["warehouse_code"] == args.warehouse_code
            ]
            if len(warehouses) != 1:
                raise ValueError("请用 --warehouse-code 明确指定一个启用线边仓")
            warehouse = warehouses[0]
            cursor.execute(
                "SELECT * FROM factory_line WHERE deleted_flag=0 AND line_code LIKE 'LAMP-%%' AND status='RUNNING'"
                " ORDER BY line_id"
            )
            lines = cursor.fetchall()
            cursor.execute("SELECT * FROM wms_location WHERE deleted_flag=0")
            existing = cursor.fetchall()
        proposed = []
        for line in lines:
            if any(row.get("line_id") == line["line_id"] for row in existing):
                continue
            code = "LS-L" + str(line["line_id"])
            name = re.sub(r"(专线|产线|生产线|线)$", "", line["line_name"]) + "线边位"
            if any(row["location_code"] == code for row in existing):
                raise ValueError("库位编码冲突：" + code)
            proposed.append(
                {
                    "lineId": line["line_id"],
                    "lineName": line["line_name"],
                    "locationCode": code,
                    "locationName": name,
                    "warehouseId": warehouse["warehouse_id"],
                }
            )
        report["proposed"] = proposed
        if args.apply and proposed:
            folder = ROOT / "tmp/backups" / ("line-side-locations-" + dt.datetime.now().strftime("%Y%m%d-%H%M%S-%f"))
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
                raise RuntimeError("备份不完整，停止修改")
            report["backup"] = {
                "file": str(backup.relative_to(ROOT)),
                "sha256": hashlib.sha256(backup.read_bytes()).hexdigest(),
            }
            db.begin()
            try:
                with db.cursor() as cursor:
                    for item in proposed:
                        cursor.execute(
                            "INSERT INTO wms_location("
                            "location_code,location_name,warehouse_id,line_id,location_type,status"
                            ",remark,version,created_by,created_time,updated_by,updated_time,deleted_flag)"
                            " VALUES(%s,%s,%s,%s,'NORMAL','IDLE','台灯演练：按所属产线推荐，不搬移历史库存',0,1,NOW(),1,NOW(),0)",
                            (item["locationCode"], item["locationName"], item["warehouseId"], item["lineId"]),
                        )
                        report["created"].append({**item, "locationId": cursor.lastrowid})
                db.commit()
            except Exception:
                db.rollback()
                raise
        write_json(report, ensure_ascii=False, indent=2)
    finally:
        db.close()


if __name__ == "__main__":
    main()
