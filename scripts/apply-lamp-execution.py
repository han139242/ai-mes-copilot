"""只在本机独立台灯库预览/应用执行结构；不启用业务、不执行演示种子。"""

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
from datetime import datetime, timezone
from pathlib import Path
from lamp_migrations import EXECUTION_MIGRATIONS


def local_now():
    """当前本地时间：显式取带时区的时间再去掉时区，不依赖进程默认时区配置。"""
    return datetime.now(tz=timezone.utc).astimezone().replace(tzinfo=None)


ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", default="mes_lamp_rehearsal")
    parser.add_argument("--port", type=int, default=3306)
    parser.add_argument("--user", default="mes_lamp_app")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--repeat", type=int, choices=[1, 2], default=1)
    args = parser.parse_args()
    if not re.fullmatch(r"mes_lamp_[A-Za-z0-9_]+", args.database):
        parser.error("只允许mes_lamp_前缀的独立数据库")
    mysql_bin = os.environ.get("MYSQL_BIN")
    binary = Path(mysql_bin) / "mysql.exe" if mysql_bin else Path(shutil.which("mysql") or "mysql")
    common = [
        "--no-defaults",
        "--protocol=TCP",
        "--host=127.0.0.1",
        "--port=" + str(args.port),
        "--user=" + args.user,
        "--default-character-set=utf8mb4",
    ]

    def sql(statement):
        result = subprocess.run(
            [str(binary)]
            + common
            + [
                "--database=" + args.database,
                "--batch",
                "--skip-column-names",
            ],
            input=statement,
            capture_output=True,
            text=True,
            encoding="utf-8",
            timeout=120,
        )
        if result.returncode:
            raise RuntimeError(result.stderr.strip())
        return result.stdout.strip()

    if sql("SELECT DATABASE();") != args.database:
        raise RuntimeError("数据库身份核对失败")
    report = {
        "database": args.database,
        "mode": "apply" if args.apply else "preview",
        "backup": None,
        "businessExecution": "blocked pending line-side, snapshot consumption and per-unit inspection",
    }
    if args.apply:
        folder = ROOT / "tmp/backups"
        folder.mkdir(parents=True, exist_ok=True)
        backup = folder / (args.database + "-before-execution-" + local_now().strftime("%Y%m%d-%H%M%S-%f") + ".sql")
        dump = binary.with_name("mysqldump.exe" if binary.suffix == ".exe" else "mysqldump")
        result = subprocess.run(
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
            timeout=120,
        )
        if result.returncode or not backup.exists() or b"-- Dump completed on " not in backup.read_bytes()[-500:]:
            raise RuntimeError("备份未完成，未执行结构迁移")
        report["backup"] = {
            "sha256": hashlib.sha256(backup.read_bytes()).hexdigest(),
            "file": str(backup.relative_to(ROOT)),
        }
        for _ in range(args.repeat):
            # lamp_rbac_v1.sql 必须先跑：它负责建齐 8 个角色，后面几个脚本按角色授权时才能 JOIN 到。
            # 实测放在最后会导致授权结果随执行顺序漂移（重跑一次就多出十几条授权）。
            for filename in EXECUTION_MIGRATIONS:
                sql((ROOT / "workspace/idea/ems/src/main/resources/db" / filename).read_text(encoding="utf-8"))
    report["tables"] = int(sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE();"))
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
