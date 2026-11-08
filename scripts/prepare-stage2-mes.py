"""Copy a local demo database into a new stage-2 database; never alter the source."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
from datetime import datetime, timezone


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True)
    parser.add_argument("--target", required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--mysql-bin", required=True)
    parser.add_argument("--runtime", required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"mes_lamp_[A-Za-z0-9_]+", args.source):
        raise SystemExit("Only a local lamp demonstration database can be a source")
    if not re.fullmatch(r"mes_lamp_(?:stage2_|lineside_stage2_)[0-9]{8}(?:_[a-z0-9]+)?", args.target) or args.source == args.target:
        raise SystemExit("The new database must have its own mes_lamp_stage2_YYYYMMDD name")
    if not os.environ.get("DB_USERNAME") or not os.environ.get("DB_PASSWORD"):
        raise SystemExit("Load the explicitly selected local demo database credentials first")
    environment = {**os.environ, "MYSQL_PWD": os.environ["DB_PASSWORD"]}
    binary = Path(args.mysql_bin)
    common = ["--host=127.0.0.1", f"--port={args.port}", f"--user={os.environ['DB_USERNAME']}",
              "--default-character-set=utf8mb4"]

    def mysql(statement=None, database=None, source=None):
        command = [str(binary / "mysql.exe"), *common, "--connect-timeout=10", "--batch", "--skip-column-names"]
        if database:
            command.append(f"--database={database}")
        result = subprocess.run(command, env=environment,
                                input=(statement.rstrip(";\n")+";\n").encode() if statement is not None else None,
                                stdin=source, capture_output=True)
        if result.returncode:
            raise RuntimeError("Local database preparation failed; no source mutations were requested")
        return result.stdout.decode("utf-8").strip()

    if mysql(f"SELECT count(*) FROM information_schema.schemata WHERE schema_name='{args.target}'") != "0":
        raise SystemExit("Target database already exists; refusing to replace it")
    tables = mysql("SHOW FULL TABLES WHERE Table_type='BASE TABLE'", args.source)
    names = [line.split("\t")[0] for line in tables.splitlines()]
    if not names or not {"sys_user", "wms_inventory", "mfg_work_order"} <= set(names):
        raise SystemExit("Source is not an initialized MES demo database")
    if any(not re.fullmatch(r"[A-Za-z0-9_]+", name) for name in names):
        raise SystemExit("Unexpected table identifier")

    def counts(database):
        statement = " UNION ALL ".join(f"SELECT '{name}',count(*) FROM `{name}`" for name in names)
        values = {parts[0]: int(parts[1]) for line in mysql(statement, database).splitlines()
                  if len(parts := line.split("\t")) == 2}
        if set(values) != set(names):
            raise RuntimeError("The database did not return every requested table count; target was not verified")
        return values

    before = counts(args.source)
    directory = Path(args.runtime).resolve() / "evidence"
    directory.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    dump = directory / f"mes-demo-source-{stamp}.sql"
    with dump.open("xb") as output:
        result = subprocess.run([str(binary / "mysqldump.exe"), *common, "--single-transaction",
                                 "--skip-lock-tables", "--set-gtid-purged=OFF", "--no-tablespaces", args.source],
                                env=environment, stdout=output, stderr=subprocess.PIPE)
    if result.returncode:
        detail = result.stderr.decode("utf-8", errors="replace").replace(os.environ["DB_PASSWORD"], "<redacted>")
        raise RuntimeError("Read-only demo export failed; target was not created: " + detail[:600])
    mysql(f"CREATE DATABASE `{args.target}` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci")
    with dump.open("rb") as source:
        mysql(database=args.target, source=source)
    copied = counts(args.target)
    after = counts(args.source)
    if before != copied or before != after:
        raise RuntimeError("Source changed during copying or row counts differ; preserve both databases for inspection")
    report = {"source": args.source, "target": args.target, "port": args.port, "tables": len(names),
              "sourceUnchanged": before == after, "rowCountsMatch": before == copied,
              "sourceRows": before, "copyRows": copied, "dumpSha256": hashlib.sha256(dump.read_bytes()).hexdigest(),
              "createdUtc": stamp}
    (directory / f"mes-copy-{args.target}-verification.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps({key: report[key] for key in ("source", "target", "port", "tables", "sourceUnchanged", "rowCountsMatch")}))


if __name__ == "__main__":
    main()
