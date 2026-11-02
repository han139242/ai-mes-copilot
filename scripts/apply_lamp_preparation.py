"""Apply the R1 draft design only to the local rehearsal DB. Credentials: MYSQL_PWD.

Creates an ignored backup before mutation. --verify-only performs read-only checks.
"""

import argparse
import hashlib
import json
import logging
import os
import subprocess
from datetime import datetime, timedelta, timezone
from pathlib import Path

logging.basicConfig(level=logging.INFO, format="%(message)s")
LOGGER = logging.getLogger(__name__)

root = Path(__file__).resolve().parents[1]
binary = Path(os.environ.get("MYSQL_BIN", r"C:\Program Files\MySQL\MySQL Server 8.0\bin"))
connection = [
    "--no-defaults",
    "--protocol=TCP",
    "--host=127.0.0.1",
    "--port=3306",
    "--user=mes_lamp_app",
    "--default-character-set=utf8mb4",
]



def sql(statement):
    completed = subprocess.run(
        [str(binary / "mysql.exe")] + connection + ["--database=mes_lamp_rehearsal", "--batch", "--skip-column-names"],
        input=statement,
        text=True,
        encoding="utf-8",
        capture_output=True,
        timeout=120,
    )
    if completed.returncode:
        raise RuntimeError(completed.stderr)
    return completed.stdout.strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--verify-only", action="store_true")
    parser.add_argument("--repeat", type=int, choices=[1, 2], default=1)
    args = parser.parse_args()

    if "MYSQL_PWD" not in os.environ:
        raise RuntimeError("Load the encrypted rehearsal credential into the current process")
    if sql("SELECT DATABASE();") != "mes_lamp_rehearsal":
        raise RuntimeError("Rehearsal database is not selected")
    product_count = sql(
        (
            "SELECT COUNT(*) FROM mdm_product WHERE product_code IN ('LAMP-HEAD','LAMP-ARM','LAMP-BASE-L100',"
            "'LAMP-BASE-L200','LAMP-L100','LAMP-L200') AND deleted_flag=0;"
        )
    )
    if product_count != "6":
        raise RuntimeError("Apply the lamp foundation first")
    backup_info = None
    if not args.verify_only:
        folder = root / "tmp/backups"
        folder.mkdir(parents=True, exist_ok=True)
        timestamp = datetime.now(timezone(timedelta(hours=8))).strftime("%Y%m%d-%H%M%S-%f")
        backup = folder / ("mes_lamp_rehearsal-r1-" + timestamp + ".sql")
        dump = subprocess.run(
            [str(binary / "mysqldump.exe")]
            + connection
            + [
                "--single-transaction",
                "--skip-lock-tables",
                "--no-tablespaces",
                "--set-gtid-purged=OFF",
                "--result-file=" + str(backup),
                "mes_lamp_rehearsal",
            ],
            capture_output=True,
            text=True,
        )
        if dump.returncode:
            raise RuntimeError("Backup failed: " + dump.stderr)
        data = backup.read_bytes()
        if b"-- Dump completed on " not in data[-500:]:
            raise RuntimeError("Incomplete backup")
        backup_info = {"file": str(backup.relative_to(root)), "sha256": hashlib.sha256(data).hexdigest()}
        for _ in range(args.repeat):
            for name in ["lamp_preparation_v1.sql", "lamp_preparation_master_v1.sql"]:
                sql((root / "workspace/idea/ems/src/main/resources/db" / name).read_text(encoding="utf-8"))

    counts = {}
    for label, query in {
        "controlledProducts": "SELECT COUNT(*) FROM mdm_product WHERE preparation_controlled=1 AND deleted_flag=0",
        "parameterDraftsAndConfirmed": "SELECT COUNT(*) FROM mdm_product_parameter WHERE deleted_flag=0",
        "lampRoutes": "SELECT COUNT(*) FROM process_route WHERE route_code LIKE 'LAMP-%' AND deleted_flag=0",
        "lampRouteSteps": (
            "SELECT COUNT(*) FROM process_route_item i JOIN process_route r ON r.route_id=i.route_id WHERE "
            "r.route_code LIKE 'LAMP-%' AND r.deleted_flag=0 AND i.deleted_flag=0"
        ),
        "lampSchemes": "SELECT COUNT(*) FROM qms_inspection_scheme WHERE scheme_code LIKE 'LAMP-%' AND deleted_flag=0",
        "orphanStandards": (
            "SELECT COUNT(*) FROM qms_inspection_scheme_item si JOIN qms_inspection_scheme s ON "
            "s.scheme_id=si.scheme_id LEFT JOIN qms_inspection_standard v ON v.standard_id=si.standard_id AND "
            "v.deleted_flag=0 WHERE s.scheme_code LIKE 'LAMP-%' AND s.deleted_flag=0 AND si.deleted_flag=0 AND "
            "v.standard_id IS NULL"
        ),
        "orphanOperations": (
            "SELECT COUNT(*) FROM process_route_item i JOIN process_route r ON r.route_id=i.route_id LEFT JOIN "
            "process_operation o ON o.operation_id=i.operation_id AND o.deleted_flag=0 WHERE r.route_code LIKE "
            "'LAMP-%' AND r.deleted_flag=0 AND i.deleted_flag=0 AND o.operation_id IS NULL"
        ),
        "invalidStandardUnits": (
            "SELECT COUNT(*) FROM qms_inspection_standard s JOIN qms_inspection_item i ON i.item_id=s.item_id LEFT "
            "JOIN mdm_unit u ON u.unit_id=i.unit_id AND u.deleted_flag=0 AND u.status='ENABLE' WHERE "
            "s.remark='LAMP_R1_DESIGN_V01' AND s.deleted_flag=0 AND (s.lower_limit IS NOT NULL OR s.upper_limit IS NOT "
            "NULL) AND u.unit_id IS NULL"
        ),
        "mismatchedStandards": (
            "SELECT COUNT(*) FROM qms_inspection_scheme_item i JOIN qms_inspection_scheme s ON s.scheme_id=i.scheme_id "
            "JOIN qms_inspection_standard v ON v.standard_id=i.standard_id WHERE s.scheme_code LIKE 'LAMP-%' AND "
            "s.deleted_flag=0 AND i.deleted_flag=0 AND (NOT(v.product_id <=> s.product_id) OR NOT(v.item_id <=> "
            "i.item_id))"
        ),
        "invalidSampling": (
            "SELECT COUNT(*) FROM qms_inspection_scheme_item i JOIN qms_inspection_scheme s ON s.scheme_id=i.scheme_id "
            "WHERE s.scheme_code LIKE 'LAMP-%' AND s.deleted_flag=0 AND i.deleted_flag=0 AND ((i.sampling_mode='ALL' "
            "AND i.sample_quantity<>0) OR (i.sampling_mode='FIXED' AND (i.sample_quantity<=0 OR "
            "i.sample_quantity<>FLOOR(i.sample_quantity))) OR i.sampling_mode NOT IN ('ALL','FIXED'))"
        ),
        "lampOrders": (
            "SELECT COUNT(*) FROM mfg_production_order o JOIN mdm_product p ON p.product_id=o.product_id WHERE "
            "p.preparation_controlled=1 AND o.deleted_flag=0"
        ),
        "lampInventoryRows": (
            "SELECT COUNT(*) FROM wms_inventory i JOIN mdm_material m ON m.material_id=i.material_id WHERE "
            "m.material_code LIKE 'LAMP-%' AND i.deleted_flag=0"
        ),
    }.items():
        counts[label] = int(sql(query + ";"))
    seed_ok = counts["parameterDraftsAndConfirmed"] >= 29 and counts["lampRoutes"] >= 6
    if not (counts["controlledProducts"] == 6 and seed_ok):
        raise RuntimeError(str(counts))
    aligned = int(sql("SELECT COUNT(*) FROM process_route_item WHERE remark='LAMP_REPORT_V18' AND deleted_flag=0;"))
    if not (counts["lampRouteSteps"] >= (14 if aligned else 20) and counts["orphanStandards"] == 0):
        raise RuntimeError(str(counts))
    if aligned:
        if aligned != 14:
            raise RuntimeError("Report V1.8 requires exactly fourteen active steps across six routes")
    if not all(
        counts[key] == 0 for key in ["orphanOperations", "invalidStandardUnits", "mismatchedStandards", "invalidSampling"]
    ):
        raise RuntimeError(str(counts))
    result = {
        "database": "mes_lamp_rehearsal",
        "verifyOnly": args.verify_only,
        "repeat": args.repeat,
        "backup": backup_info,
        "counts": counts,
    }
    if not args.verify_only:
        (root / "tmp/r1-application-result.json").write_text(
            json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8"
        )
    LOGGER.info(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except RuntimeError as error:
        LOGGER.error("%s", error)
        raise SystemExit(1)
