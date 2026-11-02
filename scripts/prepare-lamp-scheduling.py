"""预览或启用纯台灯演练库的排产配置；不确认产品参数、不生成库存或生产实绩。"""

import argparse
import hashlib
import json
import os
from datetime import datetime, timezone
from pathlib import Path
import re
import subprocess


def local_now():
    """当前本地时间：显式取带时区的时间再去掉时区，不依赖进程默认时区配置。"""
    return datetime.now(tz=timezone.utc).astimezone().replace(tzinfo=None)


ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", required=True)
    parser.add_argument("--port", type=int, choices=[3310, 3307], default=3310)
    parser.add_argument("--user", default="root")
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    if not re.fullmatch(r"mes_lamp_clean_[A-Za-z0-9_]+", args.database):
        parser.error("仅允许独立mes_lamp_clean_演练库")
    binary = Path(os.environ.get("MYSQL_BIN", r"C:\Program Files\MySQL\MySQL Server 8.0\bin"))
    connection = [
        "--no-defaults",
        "--protocol=TCP",
        "--host=127.0.0.1",
        f"--port={args.port}",
        f"--user={args.user}",
        "--default-character-set=utf8mb4",
    ]

    def sql(statement):
        result = subprocess.run(
            [str(binary / "mysql.exe"), *connection, f"--database={args.database}", "--batch", "--skip-column-names"],
            input=statement,
            text=True,
            encoding="utf-8",
            capture_output=True,
            timeout=120,
        )
        if result.returncode:
            raise RuntimeError(result.stderr.strip())
        return result.stdout.strip()

    if sql("SELECT DATABASE()") != args.database:
        raise RuntimeError("连接库身份不一致")
    routes = sql("""SELECT r.route_id,r.route_code,r.status,COUNT(i.route_item_id),
        SUM(CASE WHEN i.resource_type='BATCH_EQUIPMENT'
            THEN i.batch_capacity>0 AND i.batch_duration_minutes>0
            ELSE i.standard_time>0 END)
        FROM process_route r JOIN process_route_item i ON i.route_id=r.route_id AND i.deleted_flag=0
        WHERE r.deleted_flag=0 AND r.route_code LIKE 'LAMP-%' GROUP BY r.route_id""")
    if len(routes.splitlines()) != 6:
        raise RuntimeError("预期6条台灯路线，拒绝不完整配置")
    for row in routes.splitlines():
        fields = row.split("\t")
        if fields[3] != fields[4]:
            raise RuntimeError("路线缺少有效单件/批次工时：" + fields[1])
    # 使用已配置的班次时长与路线人工节拍推导设计日产能上限；批次另由引擎逐批占时。
    capacities = sql(
        """SELECT l.line_id,l.line_code,FLOOR(MIN(t.day_minutes/t.labor_minutes))
        FROM factory_line l JOIN mfg_line_product_capability c ON c.line_id=l.line_id AND c.deleted_flag=0
        JOIN (SELECT r.route_id,
            SUM(CASE WHEN COALESCE(i.resource_type,'LABOR')<>'BATCH_EQUIPMENT'"""
        """ THEN i.standard_time ELSE 0 END) labor_minutes,
            (SELECT SUM(TIME_TO_SEC(TIMEDIFF(s.end_time,s.start_time)))/60 FROM org_shift s
             WHERE s.deleted_flag=0 AND s.shift_code IN ('LAMP-SHIFT-AM','LAMP-SHIFT-PM')) day_minutes
            FROM process_route r JOIN process_route_item i ON i.route_id=r.route_id AND i.deleted_flag=0
            WHERE r.deleted_flag=0 AND r.route_code LIKE 'LAMP-%' GROUP BY r.route_id) t ON t.route_id=c.route_id
        WHERE l.deleted_flag=0 AND l.line_code LIKE 'LAMP-%' GROUP BY l.line_id"""
    )
    line_values = [row.split("\t") for row in capacities.splitlines()]
    if len(line_values) != 4 or any(not row[2].isdigit() or int(row[2]) <= 0 for row in line_values):
        raise RuntimeError("无法由现有两班时长与人工节拍推导4条线的设计上限")
    before = {
        "routes": routes.splitlines(),
        "lines": sql(
            "SELECT line_code,status,daily_capacity FROM factory_line "
            "WHERE deleted_flag=0 AND line_code LIKE 'LAMP-%'"
        ).splitlines(),
        "capabilities": sql(
            "SELECT status,COUNT(*) FROM mfg_line_product_capability " "WHERE deleted_flag=0 GROUP BY status"
        ).splitlines(),
        "calendars": sql(
            "SELECT status,COUNT(*) FROM mfg_line_calendar_rule " "WHERE deleted_flag=0 GROUP BY status"
        ).splitlines(),
    }
    report = {
        "database": args.database,
        "port": args.port,
        "apply": args.apply,
        "before": before,
        "designCapacity": [{"line": row[1], "perDayUpperBound": int(row[2])} for row in line_values],
        "basis": "现有早/午班总时长÷路线人工工时；非实测产能，批次由引擎另外占用连续时段",
    }
    if not args.apply:
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return
    backup_dir = ROOT / "tmp/backups" / ("lamp-scheduling-" + local_now().strftime("%Y%m%d-%H%M%S-%f"))
    backup_dir.mkdir(parents=True, exist_ok=False)
    backup = backup_dir / "before.sql"
    result = subprocess.run(
        [
            str(binary / "mysqldump.exe"),
            *connection,
            "--single-transaction",
            "--no-tablespaces",
            "--set-gtid-purged=OFF",
            "--result-file=" + str(backup),
            args.database,
        ],
        capture_output=True,
        timeout=120,
    )
    if result.returncode or not backup.exists() or b"-- Dump completed" not in backup.read_bytes()[-500:]:
        raise RuntimeError("备份未完成，未修改任何配置")
    report["backup"] = str(backup.relative_to(ROOT))
    report["backupSha256"] = hashlib.sha256(backup.read_bytes()).hexdigest()
    statements = [
        "START TRANSACTION",
        "SET @operator=(SELECT user_id FROM sys_user WHERE username='admin' AND deleted_flag=0 LIMIT 1)",
    ]
    for line_id, _, capacity in line_values:
        statements.append(
            f"UPDATE factory_line SET status='RUNNING',daily_capacity={capacity},version=version+1,"
            f"updated_by=@operator,updated_time=NOW() WHERE line_id={int(line_id)} "
            f"AND (status<>'RUNNING' OR daily_capacity<>{capacity})"
        )
    statements.extend(
        [
            "UPDATE process_route SET status='RELEASED',version=version+1,updated_by=@operator,"
            "updated_time=NOW() WHERE deleted_flag=0 AND route_code LIKE 'LAMP-%' AND status='DRAFT'",
            "UPDATE mfg_line_product_capability c JOIN factory_line l ON l.line_id=c.line_id "
            "AND l.line_code LIKE 'LAMP-%' AND l.deleted_flag=0 SET c.status='ENABLE',"
            "c.version=c.version+1,c.updated_by=@operator,c.updated_time=NOW() "
            "WHERE c.deleted_flag=0 AND c.status='DISABLE'",
            "UPDATE mfg_line_calendar_rule c JOIN factory_line l ON l.line_id=c.line_id "
            "AND l.line_code LIKE 'LAMP-%' AND l.deleted_flag=0 SET c.status='ENABLE',"
            "c.version=c.version+1,c.updated_by=@operator,c.updated_time=NOW() "
            "WHERE c.deleted_flag=0 AND c.status='DISABLE'",
            "INSERT INTO sys_operation_log (user_id,module_code,biz_id,operation_type,remark,"
            "operation_time,version,created_by,created_time,updated_by,updated_time,deleted_flag) "
            "VALUES (@operator,'MFG_SCHEDULING',NULL,'PREPARE_LAMP_SCHEDULING',"
            "'按用户要求启用独立台灯演练排产配置；设计产能非实测；参数、质量确认与实绩保持原状',"
            "NOW(),0,@operator,NOW(),@operator,NOW(),0)",
            "COMMIT",
        ]
    )
    sql(";\n".join(statements) + ";")
    report["after"] = sql(
        "SELECT line_code,status,daily_capacity FROM factory_line " "WHERE deleted_flag=0 AND line_code LIKE 'LAMP-%'"
    ).splitlines()
    (backup_dir / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
