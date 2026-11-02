"""核对纯台灯隔离库：实体/主键、旧业务文字、基础关系及空白业务基线。"""

import argparse
import importlib.util
import json
import os
import re
import shutil
import subprocess
from pathlib import Path
from cli_output import write_json

ROOT = Path(__file__).resolve().parents[1]


LAMP_CODED_COLUMNS = [
    ("mdm_product", "product_code"),
    ("mdm_material", "material_code"),
    ("mdm_bom", "bom_code"),
    ("process_route", "route_code"),
    ("factory_line", "line_code"),
    ("eam_equipment", "equipment_code"),
]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", required=True)
    parser.add_argument("--port", type=int, choices=[3307, 3310, 3311], default=3310)
    parser.add_argument("--user", default="root")
    parser.add_argument("--expect-empty-business", action="store_true")
    args = parser.parse_args()
    if not re.fullmatch(r"mes_lamp_clean_[A-Za-z0-9_]+", args.database):
        parser.error("仅核对mes_lamp_clean_前缀的独立库")
    mysql_bin = os.environ.get("MYSQL_BIN")
    executable = Path(mysql_bin) / "mysql.exe" if mysql_bin else Path(shutil.which("mysql") or "")
    if not executable.is_file():
        parser.error("请配置MYSQL_BIN")
    spec = importlib.util.spec_from_file_location("lamp_clean_initializer", ROOT / "scripts/initialize_lamp_clean.py")
    initializer = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(initializer)

    def sql(statement):
        result = subprocess.run(
            [
                str(executable),
                "--no-defaults",
                "--protocol=TCP",
                "--host=127.0.0.1",
                "--port=" + str(args.port),
                "--user=" + args.user,
                "--database=" + args.database,
                "--default-character-set=utf8mb4",
                "--batch",
                "--skip-column-names",
            ],
            input=statement,
            text=True,
            encoding="utf-8",
            capture_output=True,
            timeout=120,
        )
        if result.returncode:
            raise RuntimeError(result.stderr.strip())
        return result.stdout.strip()

    columns = {}
    for row in sql(
        "SELECT table_name,column_name FROM information_schema.columns " "WHERE table_schema=DATABASE()"
    ).splitlines():
        table, column = row.split("\t")
        columns.setdefault(table, set()).add(column)
    primary = {}
    for row in sql(
        "SELECT table_name,column_name FROM information_schema.key_column_usage "
        "WHERE table_schema=DATABASE() AND constraint_name='PRIMARY'"
    ).splitlines():
        table, column = row.split("	")
        primary[table] = column
    mismatches = {}
    entities = set()
    for path in (ROOT / "workspace/idea/ems/src/main/java/com/neuedu/ems/entity").rglob("*.java"):
        content = path.read_text(encoding="utf-8-sig")
        match = re.search(r'@TableName\("([^"]+)"\)', content)
        if not match:
            continue
        table = match[1]
        entities.add(table)
        content = re.sub(r"@TableField\(\s*exist\s*=\s*false\s*\)\s*private[^;]+;", "", content)
        names = re.findall(r"private\s+[\w.<>]+\s+(\w+)\s*;", content)
        fields = {re.sub(r"(?<!^)(?=[A-Z])", "_", name).lower() for name in names}
        if fields != columns.get(table, set()):
            mismatches[table] = {
                "missing": sorted(fields - columns.get(table, set())),
                "extra": sorted(columns.get(table, set()) - fields),
            }
        pk = re.search(r'@TableId\(\s*(?:value\s*=\s*)?"([^"]+)"', content)
        if pk and primary.get(table) != pk[1]:
            mismatches.setdefault(table, {})["primaryKeyMismatch"] = True
    legacy = {}
    for table in columns:
        names = sql(
            "SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='"
            + table
            + "' AND data_type IN ('char','varchar','text','mediumtext','longtext')"
        ).splitlines()
        if not names:
            continue
        expression = "CONCAT_WS(' '," + ",".join("`" + name + "`" for name in names) + ")"
        count = int(
            sql("SELECT COUNT(*) FROM `" + table + "` WHERE " + expression + " REGEXP '" + initializer.LEGACY + "'")
        )
        if count:
            legacy[table] = count
    links = [
        ("mdm_product", "material_id", "mdm_material", "material_id"),
        ("mdm_material", "category_id", "mdm_material_category", "category_id"),
        ("mdm_material", "unit_id", "mdm_unit", "unit_id"),
        ("mdm_bom", "product_id", "mdm_product", "product_id"),
        ("mdm_bom_item", "bom_id", "mdm_bom", "bom_id"),
        ("mdm_bom_item", "material_id", "mdm_material", "material_id"),
        ("mdm_bom_item", "child_bom_id", "mdm_bom", "bom_id"),
        ("mdm_bom_item", "consumption_operation_id", "process_operation", "operation_id"),
        ("mdm_product_parameter", "product_id", "mdm_product", "product_id"),
        ("factory_line", "workshop_id", "factory_workshop", "workshop_id"),
        ("factory_station", "line_id", "factory_line", "line_id"),
        ("factory_station_operation", "station_id", "factory_station", "station_id"),
        ("factory_station_operation", "operation_id", "process_operation", "operation_id"),
        ("process_route", "product_id", "mdm_product", "product_id"),
        ("process_route_item", "route_id", "process_route", "route_id"),
        ("process_route_item", "operation_id", "process_operation", "operation_id"),
        ("mfg_line_product_capability", "line_id", "factory_line", "line_id"),
        ("mfg_line_product_capability", "route_id", "process_route", "route_id"),
        ("mfg_line_product_capability", "product_id", "mdm_product", "product_id"),
        ("mfg_line_calendar_rule", "line_id", "factory_line", "line_id"),
        ("mfg_line_calendar_rule", "shift_id", "org_shift", "shift_id"),
        ("eam_equipment", "category_id", "eam_equipment_category", "category_id"),
        ("eam_equipment", "station_id", "factory_station", "station_id"),
        ("qms_inspection_scheme", "product_id", "mdm_product", "product_id"),
        ("qms_inspection_scheme_item", "scheme_id", "qms_inspection_scheme", "scheme_id"),
        ("qms_inspection_scheme_item", "standard_id", "qms_inspection_standard", "standard_id"),
        ("qms_inspection_standard", "item_id", "qms_inspection_item", "item_id"),
        ("wms_location", "warehouse_id", "wms_warehouse", "warehouse_id"),
        ("org_team", "department_id", "org_department", "department_id"),
        ("sys_user_role", "user_id", "sys_user", "user_id"),
        ("sys_user_role", "role_id", "sys_role", "role_id"),
        ("sys_role_menu", "role_id", "sys_role", "role_id"),
        ("sys_role_menu", "menu_id", "sys_menu", "menu_id"),
        ("sys_menu", "parent_id", "sys_menu", "menu_id"),
    ]
    orphans = {}
    for table, column, parent, parent_column in links:
        if column not in columns[table]:
            continue
        count = int(
            sql(
                f"SELECT COUNT(*) FROM `{table}` c LEFT JOIN `{parent}` p "
                f"ON c.`{column}`=p.`{parent_column}` WHERE c.`{column}` IS NOT NULL "
                f"AND c.`{column}`<>0 AND p.`{parent_column}` IS NULL"
            )
        )
        if count:
            orphans[table + "." + column] = count
    business = {
        table: int(sql("SELECT COUNT(*) FROM `" + table + "`"))
        for table in initializer.BUSINESS_TABLES
        if table in columns
    }
    non_lamp = {}
    for table, column in LAMP_CODED_COLUMNS:
        non_lamp[table] = int(sql(f"SELECT COUNT(*) FROM `{table}` WHERE `{column}` NOT LIKE 'LAMP-%'"))
    result = {
        "database": args.database,
        "tables": len(columns),
        "entityTables": len(entities),
        "schemaMismatches": mismatches,
        "extraTables": sorted(set(columns) - entities),
        "legacyTextRows": legacy,
        "nonLampMasterRows": non_lamp,
        "referenceChecks": len(links),
        "orphanReferences": orphans,
        "businessRecordCounts": business,
        "allProductsControlled": sql("SELECT COUNT(*) FROM mdm_product WHERE preparation_controlled<>1") == "0",
    }
    result["passed"] = not (
        mismatches
        or legacy
        or orphans
        or result["extraTables"]
        or any(non_lamp.values())
        or (args.expect_empty_business and any(business.values()))
        or not result["allProductsControlled"]
    )
    output = ROOT / "tmp" / (args.database + "-audit.json")
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    write_json(result, ensure_ascii=False, indent=2)
    if not result["passed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
