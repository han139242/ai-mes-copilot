"""Idempotent rehearsal migration/seed. Requires the isolated account in MYSQL_PWD."""

import logging
import os
from pathlib import Path
import subprocess

logging.basicConfig(level=logging.INFO, format="%(message)s")
LOGGER = logging.getLogger(__name__)

root = Path(__file__).resolve().parents[1]
mysql = Path(os.environ.get("MYSQL_BIN", r"C:\Program Files\MySQL\MySQL Server 8.0\bin")) / "mysql.exe"
args = [
    str(mysql),
    "--no-defaults",
    "--protocol=TCP",
    "--host=127.0.0.1",
    "--port=3306",
    "--user=mes_lamp_app",
    "--database=mes_lamp_rehearsal",
    "--default-character-set=utf8mb4",
    "--batch",
]
for name in ["lamp_foundation_v1.sql", "lamp_master_data_v1.sql"]:
    statement = (root / "workspace/idea/ems/src/main/resources/db" / name).read_text(encoding="utf-8")
    result = subprocess.run(args, input=statement, text=True, encoding="utf-8", capture_output=True, timeout=120)
    if result.returncode:
        raise RuntimeError(name + ": " + result.stderr)
    LOGGER.info(name + ": OK")
