"""Verify schema/entity agreement and fresh installation on an isolated scratch DB.
Requires MYSQL_PWD for a local administrator. Never changes mes_local.
"""

import os
import re
import subprocess
import json
from pathlib import Path
from lamp_migrations import FRESH_SCHEMA_MIGRATIONS
from cli_output import write_json

root = Path(__file__).resolve().parents[1]
mysql = Path(os.environ.get("MYSQL_BIN", r"C:\Program Files\MySQL\MySQL Server 8.0\bin")) / "mysql.exe"
TARGET = "mes_lamp_schema_check_r1"


def require(condition, message):
    """校验脚本的断言不能用 assert：python -O 会把它整条去掉，检查会静默失效。"""
    if not condition:
        raise RuntimeError(message)


def sql(statement, database=None):
    args = [
        str(mysql),
        "--no-defaults",
        "--protocol=TCP",
        "--host=127.0.0.1",
        "--port=3306",
        "--user=" + os.environ.get("MYSQL_ADMIN_USER", "root"),
        "--default-character-set=utf8mb4",
        "--batch",
        "--skip-column-names",
    ]
    if database:
        require(database == TARGET, "校验库名必须是 " + TARGET)
        args.append("--database=" + database)
    res = subprocess.run(args, input=statement, text=True, encoding="utf-8", capture_output=True, timeout=120)
    if res.returncode:
        raise RuntimeError(res.stderr)
    return res.stdout.strip()


exists = sql("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='" + TARGET + "';")
if exists == "0":
    sql("CREATE DATABASE `" + TARGET + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;")
    schema = (root / "workspace/idea/ems/src/main/resources/db/schema.sql").read_text(encoding="utf-8-sig")
    schema, n = re.subn(r"^CREATE DATABASE[^\n]*\n", "", schema, flags=re.M)
    require(n == 1, "schema.sql 应恰好包含一条 CREATE DATABASE 语句")
    sql(schema, TARGET)
for filename in FRESH_SCHEMA_MIGRATIONS * 2:
    sql((root / "workspace/idea/ems/src/main/resources/db" / filename).read_text(encoding="utf-8"), TARGET)
entities = set()
for file in (root / "workspace/idea/ems/src/main/java/com/neuedu/ems/entity").rglob("*.java"):
    entities.update(re.findall(r'@TableName\("([^"]+)"\)', file.read_text(encoding="utf-8-sig")))
tables = set(sql("SHOW TABLES;", TARGET).splitlines())
require(entities == tables, "实体与表不一致：%s" % ((entities - tables, tables - entities),))
for table, package, entity in [
    ("mdm_material", "mdm", "MdmMaterial"),
    ("mdm_bom", "mdm", "MdmBom"),
    ("mdm_bom_item", "mdm", "MdmBomItem"),
    ("mdm_product", "mdm", "MdmProduct"),
    ("mdm_product_parameter", "mdm", "MdmProductParameter"),
    ("process_route_item", "process", "ProcessRouteItem"),
    ("qms_inspection_standard", "qms", "QmsInspectionStandard"),
    ("qms_inspection_scheme_item", "qms", "QmsInspectionSchemeItem"),
]:
    source = (root / f"workspace/idea/ems/src/main/java/com/neuedu/ems/entity/{package}/{entity}.java").read_text(
        encoding="utf-8"
    )
    fields = set(
        re.sub(r"(?<!^)(?=[A-Z])", "_", name).lower() for name in re.findall(r"private\s+\w+\s+(\w+);", source)
    )
    actual = set(
        sql(
            "SELECT column_name FROM information_schema.columns WHERE table_schema='"
            + TARGET
            + "' AND table_name='"
            + table
            + "';"
        ).splitlines()
    )
    require(fields == actual, "%s 字段不一致：%s" % (table, (fields - actual, actual - fields)))
    definition = (
        "SELECT column_name,column_type,is_nullable,COALESCE(column_default,'<NULL>') "
        "FROM information_schema.columns WHERE table_schema='{}' AND table_name='" + table + "' ORDER BY column_name;"
    )
    require(
        sql(definition.format(TARGET)) == sql(definition.format("mes_lamp_rehearsal")),
        table + " 与 mes_lamp_rehearsal 的列定义不一致",
    )
require(
    sql("SELECT COUNT(*) FROM mdm_material WHERE material_code LIKE 'LAMP-%' AND deleted_flag=0;", TARGET) == "24",
    "LAMP 物料应为 24 条",
)
require(
    sql(
        "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='mes_local' AND "
        "((table_name='mdm_material' AND column_name IN ('quantity_mode','issue_step')) OR "
        "(table_name='mdm_bom_item' AND column_name='child_bom_id') OR "
        "(table_name='mdm_bom' AND column_name='effective_to'));"
    )
    == "0",
    "历史 mes_local 库不应被写入台灯字段",
)
require(
    sql("SELECT COUNT(*) FROM mes_local.mdm_material WHERE material_code LIKE 'LAMP-%';") == "0",
    "历史 mes_local 库不应出现台灯物料",
)
write_json(
        {
            "tables": len(tables),
            "entityTables": len(entities),
            "freshInstall": True,
            "repeatedMigration": True,
            "changedEntityFieldsMatch": True,
            "legacySchemaAndLampRecordsUntouched": True,
        }
    )
