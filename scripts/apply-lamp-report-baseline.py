"""预览或应用V1.8草稿对齐。默认只读预览；--apply先备份，只允许独立台灯库。"""

import argparse
import hashlib
import json
import logging
import os
import re
import shutil
import subprocess
from datetime import datetime, timedelta, timezone
from pathlib import Path

from lamp_report_baseline import ROOT, SQL_PATH, generate_sql, MARKER

logging.basicConfig(level=logging.INFO, format="%(message)s")
LOGGER = logging.getLogger(__name__)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", default="mes_lamp_rehearsal")
    parser.add_argument("--port", type=int, default=3306)
    parser.add_argument("--user", default="mes_lamp_app")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--verify-only", action="store_true")
    parser.add_argument("--repeat", type=int, choices=[1, 2], default=1)
    args = parser.parse_args()
    if not re.fullmatch(r"mes_lamp_[A-Za-z0-9_]+", args.database):
        parser.error("只允许mes_lamp_前缀的独立数据库")
    if args.apply and args.verify_only:
        parser.error("--apply和--verify-only不可同时使用")
    if SQL_PATH.read_text(encoding="utf-8") != generate_sql():
        raise RuntimeError("SQL与报告数据定义不同步，请先重新生成并审查")
    configured = os.environ.get("MYSQL_BIN")
    mysql = Path(configured) / "mysql.exe" if configured else Path(shutil.which("mysql") or "mysql")
    dump = mysql.with_name("mysqldump.exe" if mysql.suffix == ".exe" else "mysqldump")
    common = [
        "--no-defaults",
        "--protocol=TCP",
        "--host=127.0.0.1",
        "--port=" + str(args.port),
        "--user=" + args.user,
        "--default-character-set=utf8mb4",
    ]

    def sql(statement):
        r = subprocess.run(
            [str(mysql)] + common + ["--database=" + args.database, "--batch", "--skip-column-names"],
            input=statement,
            text=True,
            encoding="utf-8",
            capture_output=True,
            timeout=120,
        )
        if r.returncode:
            # 不输出凭据，也不将SQL错误误报为迁移成功。
            raise RuntimeError(r.stderr.strip())
        return r.stdout.strip()

    if sql("SELECT DATABASE();") != args.database:
        raise RuntimeError("当前连接的数据库不是 " + args.database)
    # 先用真实元数据核对生成SQL涉及的列，避免字段漂移到事务执行时才暴露。
    schema = {}
    for line in sql(
        "SELECT table_name,column_name FROM information_schema.columns WHERE table_schema=DATABASE();"
    ).splitlines():
        table, column = line.split("\t")
        schema.setdefault(table, set()).add(column)
    for match in re.finditer(r"INSERT INTO (\w+)\(([^)]+)\)", generate_sql()):
        missing = set(match[2].split(",")) - schema.get(match[1], set())
        if missing:
            raise RuntimeError("结构与SQL不一致：" + match[1] + " 缺少 " + ",".join(sorted(missing)))
    preview = {}
    for table in [
        "mdm_product_parameter",
        "process_route",
        "process_route_item",
        "qms_inspection_scheme",
        "qms_inspection_standard",
    ]:
        preview[table] = int(sql("SELECT COUNT(*) FROM " + table + " WHERE deleted_flag=0;"))
    result = {
        "database": args.database,
        "port": args.port,
        "mode": "apply" if args.apply else "verify" if args.verify_only else "preview",
        "baseline": "LAMP-MES-BL-20261019-01 / V1.8",
        "before": preview,
        "backup": None,
        "target": {
            "adapter": "DC12V/1A; input 11.4-12.6V",
            "autoLux": "150/350; high/middle/low",
            "aging": "continuous 60min; capacity 50",
            "routes": 6,
            "logicalOperations": 9,
            "activeSteps": 14,
            "qualitySchemes": 14,
            "execution": "blocked; no generated inventory",
        },
    }
    if args.apply:
        folder = ROOT / "tmp/backups"
        folder.mkdir(parents=True, exist_ok=True)
        timestamp = datetime.now(timezone(timedelta(hours=8))).strftime("%Y%m%d-%H%M%S-%f")
        backup = folder / (args.database + "-before-v18-" + timestamp + ".sql")
        r = subprocess.run(
            [str(dump)]
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
            text=True,
        )
        if r.returncode or not backup.exists() or b"-- Dump completed on " not in backup.read_bytes()[-500:]:
            raise RuntimeError("备份失败，未执行迁移")
        result["backup"] = {
            "file": str(backup.relative_to(ROOT)),
            "sha256": hashlib.sha256(backup.read_bytes()).hexdigest(),
        }
        for _ in range(args.repeat):
            sql(SQL_PATH.read_text(encoding="utf-8"))
    if args.apply or args.verify_only:
        checks = {
            "routes": "SELECT COUNT(*) FROM process_route WHERE remark='LAMP_REPORT_V18' AND deleted_flag=0",
            "steps": "SELECT COUNT(*) FROM process_route_item WHERE remark='LAMP_REPORT_V18' AND deleted_flag=0",
            "operations": "SELECT COUNT(*) FROM process_operation WHERE remark='LAMP_REPORT_V18' AND deleted_flag=0",
            "schemes": "SELECT COUNT(*) FROM qms_inspection_scheme WHERE remark='LAMP_REPORT_V18' AND deleted_flag=0",
            "invalidPower": (
                "SELECT COUNT(*) FROM mdm_product_parameter x JOIN mdm_product p ON p.product_id=x.product_id "
                "WHERE p.product_code IN ('LAMP-L100','LAMP-L200') AND x.parameter_code='ADAPTER_CURRENT' AND "
                "(x.parameter_value<>'1' OR x.required_flag<>1)"
            ),
            "invalidAging": (
                "SELECT COUNT(*) FROM process_route_item WHERE remark='LAMP_REPORT_V18' AND "
                "resource_type='BATCH_EQUIPMENT' AND (batch_capacity<>50 OR batch_duration_minutes<>60)"
            ),
            "liveOldSchemes": (
                "SELECT COUNT(*) FROM qms_inspection_scheme WHERE remark='LAMP_R1_DESIGN_V01' AND deleted_flag=0 "
                "AND status<>'DISABLE'"
            ),
            "nonDraftNewSchemes": (
                "SELECT COUNT(*) FROM qms_inspection_scheme WHERE remark='LAMP_REPORT_V18' AND deleted_flag=0 AND "
                "status<>'DRAFT'"
            ),
            "incorrectSource": (
                "SELECT COUNT(*) FROM qms_inspection_standard WHERE remark='LAMP_REPORT_V18' AND deleted_flag=0 "
                "AND source_reference NOT LIKE 'QST-LAMP-SPEC-01 V1.0%'"
            ),
        }
        verified = {k: int(sql(v + ";")) for k, v in checks.items()}
        expected = {"routes": 6, "steps": 14, "operations": 9, "schemes": 14}
        for key, count in verified.items():
            if count != expected.get(key, 0):
                raise RuntimeError("基线核验失败：" + key + "=" + str(count))
        result["verified"] = verified
    LOGGER.info(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
