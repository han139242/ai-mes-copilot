#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""把台灯 L200 演示数据补齐到「可执行」状态。

背景：`lamp_snapshot_v2.sql` 在 BOM 建好之后才给 `mdm_bom_item` 加了
`consumption_operation_id` 列，所以历史已发布 BOM 的这一列全是 NULL，
制造准备会把 BOM 判成「尚未绑定投料工序」。本脚本做三件事：

1. 回填 `mdm_bom_item.consumption_operation_id`（数据修复，不改 BOM 版本、
   不改用量和层级；每套 BOM 取该产品已发布路线里序号最小的工序作为投料工序）。
2. 通过正式接口确认 L200 的 20 条检验标准（DRAFT -> CONFIRMED）。
3. 通过正式接口发布 L200 终检方案 `LAMP-V18-QS-L200-FINAL`（DRAFT -> RELEASED）。

标准与方案的确认/发布必须走接口，因为服务端有受控校验（标准要被方案引用、
方案明细要与标准的产品/项目/工序一致），直接改库会绕过这些校验。

用法：
    python scripts/apply_lamp_l200_execution_ready.py [--base http://127.0.0.1:8089]
                                                       [--db-host 127.0.0.1] [--db-port 3307]
"""

import argparse
import os
import io
import json
import logging
import sys
import urllib.error
import urllib.request

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")

logging.basicConfig(level=logging.INFO, format="%(message)s", stream=sys.stdout)
LOGGER = logging.getLogger(__name__)

PRODUCT_CODE = "LAMP-L200"
FINAL_SCHEME_CODE = "LAMP-V18-QS-L200-FINAL"


def api(base, method, path, body=None, token=None):
    payload = json.dumps(body).encode("utf-8") if body is not None else None
    request = urllib.request.Request(base + path, data=payload, method=method)
    request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        return json.loads(error.read().decode("utf-8"))


def backfill_consumption_operation(host, port, user, password, database):
    import pymysql

    connection = pymysql.connect(
        host=host, port=port, user=user, password=password, database=database, charset="utf8mb4"
    )
    try:
        cursor = connection.cursor()
        cursor.execute("""
            SELECT b.bom_id, b.product_id,
                   (SELECT ri.operation_id
                      FROM process_route r
                      JOIN process_route_item ri ON ri.route_id = r.route_id AND ri.deleted_flag = 0
                     WHERE r.product_id = b.product_id AND r.deleted_flag = 0 AND r.status = 'RELEASED'
                     ORDER BY r.route_id, ri.sequence_no
                     LIMIT 1) AS first_operation
              FROM mdm_bom b
             WHERE b.deleted_flag = 0
            """)
        filled = 0
        for bom_id, product_id, operation_id in cursor.fetchall():
            if operation_id is None:
                LOGGER.info("跳过 BOM {}（产品 {} 没有已发布路线）".format(bom_id, product_id))
                continue
            cursor.execute(
                "UPDATE mdm_bom_item SET consumption_operation_id = %s"
                " WHERE bom_id = %s AND deleted_flag = 0 AND consumption_operation_id IS NULL",
                (operation_id, bom_id),
            )
            filled += cursor.rowcount
        connection.commit()
        cursor.execute("SELECT COUNT(*) FROM mdm_bom_item WHERE deleted_flag = 0 AND consumption_operation_id IS NULL")
        remaining = cursor.fetchone()[0]
        LOGGER.info("回填投料工序 {} 条，剩余未绑定 {}".format(filled, remaining))
    finally:
        connection.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:8089")
    parser.add_argument("--db-host", default="127.0.0.1")
    parser.add_argument("--db-port", type=int, default=3307)
    parser.add_argument("--db-user", default="mes_lamp_app")
    parser.add_argument(
        "--db-password", default=os.environ.get("DB_PASSWORD"),
        required="DB_PASSWORD" not in os.environ,
    )
    parser.add_argument("--db-name", default="mes_lamp_rehearsal")
    parser.add_argument("--username", default="admin")
    parser.add_argument("--password", default="Admin@12345")
    args = parser.parse_args()

    login = api(args.base, "POST", "/api/auth/login", {"username": args.username, "password": args.password})
    token = login.get("token")
    if not token:
        raise SystemExit("登录失败：" + json.dumps(login, ensure_ascii=False))

    backfill_consumption_operation(args.db_host, args.db_port, args.db_user, args.db_password, args.db_name)

    products = api(args.base, "GET", "/api/mdm/product/page?current=1&size=100", token=token)["data"]["records"]
    product = next((row for row in products if row["productCode"] == PRODUCT_CODE), None)
    if product is None:
        raise SystemExit("找不到产品 " + PRODUCT_CODE)
    product_id = product["productId"]

    standards = api(
        args.base,
        "GET",
        "/api/qms/inspection-standard/page?current=1&size=100&productId={}".format(product_id),
        token=token,
    )["data"]["records"]
    confirmed = 0
    for standard in standards:
        if standard["confirmationStatus"] != "DRAFT":
            continue
        result = api(
            args.base,
            "POST",
            "/api/qms/inspection-standard/{}/confirm".format(standard["standardId"]),
            {"version": standard["version"]},
            token,
        )
        if result.get("code") != 200:
            raise SystemExit("确认标准 {} 失败：{}".format(standard["standardId"], result.get("msg")))
        confirmed += 1
    LOGGER.info("确认检验标准 {} 条".format(confirmed))

    schemes = api(
        args.base,
        "GET",
        "/api/qms/inspection-scheme/page?current=1&size=100&productId={}".format(product_id),
        token=token,
    )["data"]["records"]
    released = 0
    for scheme in schemes:
        if scheme["status"] != "DRAFT":
            continue
        detail = api(args.base, "GET", "/api/qms/inspection-scheme/{}".format(scheme["schemeId"]), token=token)["data"]
        payload = dict(detail)
        payload["status"] = "RELEASED"
        result = api(args.base, "PUT", "/api/qms/inspection-scheme", payload, token)
        if result.get("code") != 200:
            raise SystemExit("发布方案 {} 失败：{}".format(scheme["schemeCode"], result.get("msg")))
        released += 1
    LOGGER.info("发布检验方案 {} 套".format(released))

    report = api(args.base, "GET", "/api/mdm/manufacturing-preparation/{}".format(product_id), token=token)["data"]
    LOGGER.info("制造准备 executionReady = {}".format(report.get("executionReady")))
    for check in report["checks"]:
        LOGGER.info("  {:12s} {:8s} {}".format(check["code"], check["status"], check["message"][:80]))
    if not report.get("executionReady"):
        raise SystemExit("制造准备仍未通过")


if __name__ == "__main__":
    main()
