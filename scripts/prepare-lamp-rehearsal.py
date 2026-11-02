"""Backup and restore mes_local into a NEW loopback-only rehearsal database.

Supply a local administrator via MYSQL_PWD, never via command line or source code.
The output credentials must be captured and protected by the invoking local shell.
"""

import hashlib
import json
import logging
import os
from pathlib import Path
import secrets
import subprocess
import sys
from datetime import datetime, timedelta, timezone

logging.basicConfig(level=logging.INFO, format="%(message)s", stream=sys.stdout)
LOGGER = logging.getLogger(__name__)

ROOT = Path(__file__).resolve().parents[1]
BIN = Path(os.environ.get("MYSQL_BIN", r"C:\Program Files\MySQL\MySQL Server 8.0\bin"))
TARGET = "mes_lamp_rehearsal"
USER = "mes_lamp_app"
ADMIN = os.environ.get("MYSQL_ADMIN_USER", "root")


def execute(statement, database=None):
    args = [
        str(BIN / "mysql.exe"),
        "--no-defaults",
        "--protocol=TCP",
        "--host=127.0.0.1",
        "--port=3306",
        "--user=" + ADMIN,
        "--default-character-set=utf8mb4",
        "--batch",
        "--skip-column-names",
    ]
    if database:
        if database != TARGET:
            raise SystemExit("Only the rehearsal database is allowed")
        args.append("--database=" + database)
    r = subprocess.run(args, input=statement, text=True, encoding="utf-8", capture_output=True, timeout=180)
    if r.returncode:
        raise RuntimeError(r.stderr.splitlines()[0])
    return r.stdout.strip()


if "MYSQL_PWD" not in os.environ:
    raise SystemExit("Set MYSQL_PWD in the current process only")
if execute("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='" + TARGET + "';") != "0":
    raise SystemExit("Target already exists; refusing to overwrite")
if execute("SELECT COUNT(*) FROM mysql.user WHERE user='" + USER + "';") != "0":
    raise SystemExit("Application account already exists; refusing to change it")
folder = ROOT / "tmp/backups"
folder.mkdir(parents=True, exist_ok=True)
timestamp = datetime.now(timezone(timedelta(hours=8))).strftime("%Y%m%d-%H%M%S")
backup = folder / ("mes_local-before-lamp-" + timestamp + ".sql")
dump = subprocess.run(
    [
        str(BIN / "mysqldump.exe"),
        "--no-defaults",
        "--protocol=TCP",
        "--host=127.0.0.1",
        "--port=3306",
        "--user=" + ADMIN,
        "--single-transaction",
        "--skip-lock-tables",
        "--routines",
        "--events",
        "--triggers",
        "--no-tablespaces",
        "--set-gtid-purged=OFF",
        "--default-character-set=utf8mb4",
        "--result-file=" + str(backup),
        "mes_local",
    ],
    capture_output=True,
    text=True,
)
if dump.returncode != 0:
    raise SystemExit("Backup failed; database was not created")
text = backup.read_text(encoding="utf-8")
if text.count("CREATE TABLE `") != 85 or "-- Dump completed on " not in text[-300:]:
    raise SystemExit("Backup content is incomplete; database was not created")
execute("CREATE DATABASE `" + TARGET + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;")
execute(text, TARGET)
tables = execute("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE();", TARGET)
if tables != "85":
    raise SystemExit("Restored table count mismatch: " + tables)
baseline = {}
for table in execute("SHOW TABLES;", TARGET).splitlines():
    baseline[table] = int(execute("SELECT COUNT(*) FROM `" + table + "`;", TARGET))
password = secrets.token_urlsafe(30)
execute(
    "CREATE USER '" + USER + "'@'127.0.0.1' IDENTIFIED BY '" + password + "';"
    "GRANT ALL PRIVILEGES ON `" + TARGET + "`.* TO '" + USER + "'@'127.0.0.1';"
)
manifest = {
    "source": "127.0.0.1:3306/mes_local",
    "target": "127.0.0.1:3306/" + TARGET,
    "backup": str(backup),
    "sha256": hashlib.sha256(backup.read_bytes()).hexdigest(),
    "restore_tables": int(tables),
    "restored_row_counts": baseline,
}
(ROOT / "tmp/lamp-baseline-restore.json").write_text(
    json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
)
LOGGER.info(
    json.dumps(
        {
            "database": TARGET,
            "username": USER,
            "password": password,
            "jwt_secret": secrets.token_urlsafe(48),
            "backup": str(backup),
            "sha256": manifest["sha256"],
            "tables": 85,
        }
    )
)
