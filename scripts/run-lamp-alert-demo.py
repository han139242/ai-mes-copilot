"""台灯 MES 预警中心演示数据：一条可逐步推进的线性闭环。

设计目标
--------
1. 覆盖 AlertMetricCatalog 里全部 19 个指标，每一类都能在演练库里被真实取数命中；
2. 19 个指标不是散落的假数据，而是按"到货 → 来料检验 → 确认入库 → 缺料补货 →
   生产开工 → 工单完工 → 设备维修 → 保养点检 → 不合格处置 → 排程负荷 → 呆滞消耗"
   的真实业务先后排成 12 个阶段，前一阶段的产出是后一阶段的输入；
3. 每个阶段都能用 POST /api/ai/alert/scan 观察"新预警 / 自动动作 / 通知 / 复核关闭"，
   数据恢复正常后下一轮扫描会自动把预警关闭，形成闭环。

用法
----
    python scripts/run-lamp-alert-demo.py --clean      # 清掉上一轮演示数据
    python scripts/run-lamp-alert-demo.py --prepare    # 造出阶段 0 的异常态
    python scripts/run-lamp-alert-demo.py --scan       # 扫一轮，生成预警与自动动作
    python scripts/run-lamp-alert-demo.py --stage 1    # 推进一个阶段（1..12）
    python scripts/run-lamp-alert-demo.py --verify     # 打印 19 个指标的当前命中/关闭情况
    python scripts/run-lamp-alert-demo.py --all        # clean→prepare→scan→12 阶段→verify

连接口令来自环境变量 MYSQL_PWD（tmp/lamp-rehearsal-state.clixml 里是 DPAPI 密文，Python 解不开），
只允许 3310 端口的台灯演练库；后端默认 8089。
所有演示数据都带 DEMO-ALERT 前缀或 remark 标记，--clean 可以整段删除。
"""

import argparse
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path


def local_now():
    """当前本地时间：显式取带时区的时间再去掉时区，不依赖进程默认时区配置。"""
    return datetime.now(tz=timezone.utc).astimezone().replace(tzinfo=None)


ROOT = Path(__file__).resolve().parents[1]
STATE_FILE = ROOT / "tmp" / "lamp-rehearsal-state.clixml"
MYSQL = Path(r"D:\MySQL\MySQL Server 8.0\bin\mysql.exe")
MARK = "DEMO-ALERT"
NOTE = "预警中心演示数据（可整段清理）"
# 默认写台灯演练库；--port/--database/--user/--password 可以切到预警中心测试库（3311 mes_lamp_alert_test）。
DATABASE = "mes_lamp_rehearsal"
PORT = 3310
DB_USER = "mes_lamp_app"
DB_PASSWORD = None

# 演示用主数据（取自演练库现有台灯主数据，不新造主数据）
M_M15, M16 = 46, 47  # 连接线束 / 电源适配器：本次采购到货，用来走"入库→来料检验→放行"
M_M04 = 35  # 灯头外壳：库存不足 + 工单缺料，用来走"补货建议"
M_M03 = 34  # 扩散板：120 天呆滞批次，用来走"批次呆滞"
M_M01 = 32  # 灯珠：已放行批次随单到货，确认入库后由系统自动签发 IQC 检验单
LINE_FINAL = 7  # 整机总装测试线，日产能 200
PRODUCT_L200 = 13
BOM_L200 = 13
ROUTE_L200 = 13
WH_RAW, LOC_RAW = 8, 8
WH_SEMI, LOC_SEMI = 10, 10
WH_FG, LOC_FG = 11, 11
EQ_FAULT, EQ_REPAIR_OLD, EQ_WAIT, EQ_MAINT, EQ_SPOT = 21, 22, 23, 24, 25
STATION_FINAL = 23


def load_state():
    """数据库口令：优先用 --password，其次用调用方传入的 MYSQL_PWD（state 文件里的口令是 DPAPI 加密的，脚本自己解不开）。"""
    if DB_PASSWORD is not None:
        return DB_PASSWORD
    password = os.environ.get("MYSQL_PWD")
    if password:
        return password
    if os.environ.get("MYSQL_EMPTY_PASSWORD") == "1":
        return ""
    raise SystemExit(
        "请先设置 MYSQL_PWD："
        "pwsh 里 $s=Import-Clixml tmp/lamp-rehearsal-state.clixml; "
        "$env:MYSQL_PWD=$s.DbCredential.GetNetworkCredential().Password"
    )


def now_ts(hours=0, days=0):
    """返回当前时间（可回溯若干小时/天）的 SQL 时间字符串。"""
    return (local_now() - timedelta(hours=hours, days=days)).strftime("%Y-%m-%d %H:%M:%S")


def mysql(sql_text):
    env = dict(os.environ)
    env["MYSQL_PWD"] = load_state()
    result = subprocess.run(
        [
            str(MYSQL),
            "--no-defaults",
            "--protocol=TCP",
            "--host=127.0.0.1",
            f"--port={PORT}",
            f"--user={DB_USER}",
            "--default-character-set=utf8mb4",
            f"--database={DATABASE}",
            "--batch",
            "--raw",
            "--skip-column-names",
        ],
        input=sql_text,
        text=True,
        encoding="utf-8",
        capture_output=True,
        timeout=180,
    )
    if result.returncode:
        raise RuntimeError(result.stderr.strip())
    return result.stdout.strip()


def rows(sql_text):
    out = mysql(sql_text)
    if not out:
        return []
    return [line.split("\t") for line in out.splitlines()]


def one(sql_text):
    result = rows(sql_text)
    if len(result) != 1:
        raise RuntimeError(f"期望 1 行，实际 {len(result)} 行：{sql_text[:120]}")
    return result[0]


API_PORT = 8089
"""后端端口；main() 的 --api-port 会改写它，Api() 默认跟着走。"""


class Api:
    def __init__(self, port=None):
        self.base = f"http://127.0.0.1:{port or API_PORT}"
        self.token = None

    def call(self, method, path, body=None):
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(
            self.base + path,
            data=data,
            method=method,
            headers={
                "Content-Type": "application/json",
                **({"Authorization": "Bearer " + self.token} if self.token else {}),
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=180) as response:
                result = json.load(response)
        except urllib.error.HTTPError as error:
            result = json.loads(error.read().decode("utf-8") or "{}")
        if result.get("code") != 200:
            raise RuntimeError(f"{method} {path} 失败：" + json.dumps(result, ensure_ascii=False)[:400])
        return result

    def login(self, username="admin", password="123456"):
        result = self.call("POST", "/api/auth/login", {"username": username, "password": password})
        self.token = result["token"]
        return self


# --------------------------------------------------------------------------- 清理


def clean():
    """清掉上一轮演示数据与全部预警运行痕迹，让演示从干净状态开始。"""
    print("清理预警运行痕迹与上一轮演示数据 ...")
    mysql(f"""
DELETE FROM ai_alert_action;
DELETE FROM ai_alert_event;
DELETE FROM ai_alert_scan;
DELETE FROM ai_inventory_risk;
DELETE FROM ai_automation_step;
DELETE FROM ai_automation_run;
-- 异常台账里 EXC-ALERT-* 是预警中心自动登记的，EXC-AUTO-* 是异常扫描器把预警转成异常留下的，
-- 两者都是演示数据的派生产物，一起清掉，待办面板才只剩本轮真正产生的那几条。
DELETE FROM mfg_exception WHERE exception_no LIKE 'EXC-ALERT-%'
   OR exception_no LIKE 'EXC-AUTO-%' OR exception_no LIKE 'EXC-DEMO%' OR remark='{NOTE}';
-- 旧版演示（10-25 那批）的 remark 是"预警中心演示订单"这种自由文案，不是 NOTE，
-- 所以每张表都按"编号前缀 + remark"两条并集删，避免残留撞唯一键。
DELETE FROM eam_repair_order WHERE repair_no LIKE 'RP-ALERT-%' OR repair_no LIKE '{MARK}-%' OR remark='{NOTE}';
DELETE FROM eam_maintenance_record WHERE remark='{NOTE}' OR remark LIKE '%演示%';
DELETE FROM eam_spot_check_record WHERE remark='{NOTE}' OR remark LIKE '%演示%';
DELETE FROM eam_maintenance_plan WHERE remark='{NOTE}' OR remark LIKE '%演示%';
DELETE FROM eam_spot_check_plan WHERE remark='{NOTE}' OR remark LIKE '%演示%';
DELETE FROM mfg_schedule_item WHERE scenario_id IN (
    SELECT scenario_id FROM mfg_schedule_scenario
     WHERE remark='{NOTE}' OR scenario_name LIKE '%演示%');
DELETE FROM mfg_schedule_scenario WHERE remark='{NOTE}' OR scenario_name LIKE '%演示%';
DELETE FROM qms_nonconforming_detail WHERE ncr_id IN (
    SELECT ncr_id FROM qms_nonconforming_order
     WHERE ncr_no LIKE '{MARK}-%' OR remark='{NOTE}');
DELETE FROM qms_nonconforming_order WHERE ncr_no LIKE '{MARK}-%' OR remark='{NOTE}';
DELETE FROM qms_inspection_result WHERE inspection_id IN (
    SELECT inspection_id FROM qms_inspection_order WHERE remark='{NOTE}');
DELETE FROM qms_inspection_result WHERE inspection_id IN (
    SELECT o.inspection_id FROM qms_inspection_order o
      JOIN wms_material_batch b ON b.batch_id=o.batch_id
     WHERE b.batch_no LIKE '{MARK}-%');
DELETE FROM qms_inspection_result WHERE inspection_id IN (
    SELECT inspection_id FROM qms_inspection_order
     WHERE inspection_no LIKE '{MARK}-%' OR remark LIKE '由采购入库单 {MARK}-%确认后自动签发');
-- 入库确认时系统自动签发的来料检验单，remark 是"由采购入库单 X 确认后自动签发"，
-- 批次行可能已经被上一轮清理删掉（batch_no 反查不到），所以三种条件都要覆盖，
-- 否则每跑一轮就多留几张待检单，待办里会一直挂着"待执行检验"。
DELETE FROM qms_inspection_order
 WHERE inspection_no LIKE '{MARK}-%'
    OR remark='{NOTE}'
    OR remark LIKE '由采购入库单 {MARK}-%确认后自动签发';
DELETE FROM wms_replenishment_suggestion;
DELETE FROM mfg_task WHERE task_no LIKE '{MARK}-%' OR task_no LIKE 'DEMO-ALERT-%' OR remark='{NOTE}';
DELETE FROM mfg_work_order_material WHERE work_order_id IN (
    SELECT work_order_id FROM mfg_work_order
     WHERE work_order_no LIKE '{MARK}-%' OR work_order_no LIKE 'DEMO-ALERT-%' OR remark='{NOTE}');
DELETE FROM mfg_work_order WHERE work_order_no LIKE '{MARK}-%' OR work_order_no LIKE 'DEMO-ALERT-%' OR remark='{NOTE}';
DELETE FROM mfg_production_plan WHERE plan_no LIKE '{MARK}-%' OR plan_no LIKE 'DEMO-ALERT-%' OR remark='{NOTE}';
DELETE FROM mfg_production_order
 WHERE production_order_no LIKE '{MARK}-%' OR production_order_no LIKE 'DEMO-ALERT-%' OR remark='{NOTE}';
DELETE FROM mfg_andon_alert WHERE alert_no LIKE '{MARK}-%' OR alert_no LIKE 'DEMO-ALERT-%' OR remark='{NOTE}';
DELETE FROM wms_inbound_detail WHERE inbound_id IN (
    SELECT inbound_id FROM wms_inbound_order
     WHERE inbound_no LIKE '{MARK}-%' OR remark='{NOTE}');
DELETE FROM wms_inbound_order WHERE inbound_no LIKE '{MARK}-%' OR remark='{NOTE}';
DELETE FROM wms_stock_movement WHERE batch_id IN (
    SELECT batch_id FROM wms_material_batch WHERE batch_no LIKE '{MARK}-%');
DELETE FROM wms_inventory WHERE batch_id IN (SELECT batch_id FROM wms_material_batch WHERE batch_no LIKE '{MARK}-%');
DELETE FROM wms_material_batch WHERE batch_no LIKE '{MARK}-%';
-- 收件箱和通知是两张表，只删 sys_notification 会留下孤儿收件行，
-- 反而让"未读数"越清越多（主表 JOIN 不上，看起来是 0，但表里一直堆）。
DELETE FROM sys_notification_recipient;
DELETE FROM sys_notification;
DELETE FROM sys_event_outbox;
-- 历史遗留的 19 个待检批次（LAMP-RM-LAMP-Mxx-20261024）会一直占用"待检积压"指标，
-- 演示里混进 19 条噪声就看不清自己的 3 条，这里统一按已放行处理。
UPDATE wms_material_batch SET quality_status='PASS', status='AVAILABLE', updated_time=NOW()
 WHERE batch_no LIKE 'LAMP-RM-LAMP-M%-20261024' AND (quality_status IS NULL OR quality_status='PENDING');
-- 历史羽毛球线的设备 1 也停在 FAULT，会让"设备故障"指标多出一条与演示无关的记录，
-- 演示里只保留备用测试台那台故障设备，其余非演示故障设备统一回到 IDLE。
UPDATE eam_equipment SET status='IDLE', updated_time=NOW()
 WHERE deleted_flag=0 AND status='FAULT' AND equipment_id <> {EQ_FAULT};
UPDATE ai_alert_rule SET last_scan_time=NULL, hit_count=0 WHERE 1=1;
DELETE FROM ai_alert_rule WHERE rule_no LIKE 'AR-MATERIAL_AVAILABLE-20261025155706707';
-- 19 个指标各有一条规则，任何一条被停用（PAUSED）都会让对应指标不再出预警，
-- 演示就凑不齐 19 个指标全覆盖。重跑演示前统一恢复成启用，间隔与冷却也给成标准值。
UPDATE ai_alert_rule SET status='ENABLED', interval_minutes=10, cooldown_minutes=10 WHERE 1=1;
""")
    print("清理完成。")


# --------------------------------------------------------------------------- 阶段 0


def prepare():
    """造出"异常已发生但还没处理"的初始态，时间戳全部回溯，保证一扫描就命中。"""
    print("构造阶段 0 异常态 ...")
    now = local_now()

    def ts(hours=0, days=0):
        return (now - timedelta(hours=hours, days=days)).strftime("%Y-%m-%d %H:%M:%S")

    # 发布台灯 L200 的来料检验方案，否则确认入库后无法自动签发 IQC 检验单
    mysql(f"""
UPDATE qms_inspection_scheme SET status='RELEASED', updated_time='{ts()}'
 WHERE scheme_code='LAMP-V18-QS-L200-IQC' AND deleted_flag=0;
UPDATE qms_inspection_standard SET confirmation_status='CONFIRMED', updated_time='{ts()}'
 WHERE standard_id IN (SELECT standard_id FROM qms_inspection_scheme_item WHERE scheme_id=47);
""")

    # 健康库存：除演示用的 4 个物料外，其余启用物料都给 200 的可用余量，
    # 否则"可用库存 < 50"会对全部 51 个物料同时报警，演示就没法看了。
    mysql(f"""
INSERT INTO wms_material_batch (batch_no, material_id, received_date, quality_status, status,
       remark, created_time, updated_time, deleted_flag)
SELECT CONCAT('{MARK}-B', m.material_id), m.material_id, CURDATE(), 'PASS', 'AVAILABLE',
       '{NOTE}', '{ts()}', '{ts()}', 0
  FROM mdm_material m
 WHERE m.deleted_flag=0 AND m.status='ENABLE'
   AND m.material_id NOT IN ({M_M03},{M_M04},{M_M15},{M16});

INSERT INTO wms_inventory (warehouse_id, location_id, material_id, batch_id,
       available_quantity, locked_quantity, inspection_quantity, work_order_id,
       remark, created_time, updated_time, deleted_flag)
SELECT CASE m.material_type WHEN 'SEMI_FINISHED' THEN {WH_SEMI} WHEN 'FINISHED' THEN {WH_FG} ELSE {WH_RAW} END,
       CASE m.material_type WHEN 'SEMI_FINISHED' THEN {LOC_SEMI} WHEN 'FINISHED' THEN {LOC_FG} ELSE {LOC_RAW} END,
       m.material_id, b.batch_id, 200, 0, 0, 0, '{NOTE}', '{ts()}', '{ts()}', 0
  FROM mdm_material m
  JOIN wms_material_batch b ON b.batch_no=CONCAT('{MARK}-B', m.material_id) AND b.deleted_flag=0
 WHERE m.deleted_flag=0 AND m.status='ENABLE'
   AND m.material_id NOT IN ({M_M03},{M_M04},{M_M15},{M16});
""")

    # ① 呆滞批次：LAMP-M03 已放行 120 天仍有 50 可用 → BATCH_AGING
    mysql(f"""
INSERT INTO wms_material_batch (batch_no, material_id, received_date, quality_status, status,
       remark, created_time, updated_time, deleted_flag)
VALUES ('{MARK}-B{M_M03}', {M_M03}, DATE_SUB(CURDATE(), INTERVAL 120 DAY), 'PASS', 'AVAILABLE',
        '{NOTE}', '{ts(days=120)}', '{ts()}', 0);
INSERT INTO wms_inventory (warehouse_id, location_id, material_id, batch_id, available_quantity,
       locked_quantity, inspection_quantity, work_order_id, remark, created_time, updated_time, deleted_flag)
SELECT {WH_RAW}, {LOC_RAW}, {M_M03}, batch_id, 50, 0, 0, 0, '{NOTE}', '{ts()}', '{ts()}', 0
  FROM wms_material_batch WHERE batch_no='{MARK}-B{M_M03}';
""")

    # ② 采购到货：四批原料已到货，入库单待确认。
    #    M15/M16/M04 三批还是 WAIT_INSPECTION + FROZEN，各有一张待提交的来料检验单
    #      → BATCH_INSPECTION_PENDING（待检积压）+ INSPECTION_OVERDUE（检验超期，最早那张 30 小时）
    #      + 待办"待入库确认""待执行检验"
    #    M01 那批已放行（PASS/AVAILABLE）且没有来料检验单，确认入库后系统会自动签发一张 IQC 单，
    #      用来演示"采购入库确认 → 自动生成来料检验任务"这条自动化。
    mysql(f"""
INSERT INTO wms_material_batch (batch_no, material_id, supplier_id, received_date, quality_status,
       status, remark, created_time, updated_time, deleted_flag)
VALUES
 ('{MARK}-B{M_M15}', {M_M15}, 8, DATE_SUB(NOW(), INTERVAL 30 HOUR), 'WAIT_INSPECTION', 'FROZEN', '{NOTE}',
     '{ts(hours=30)}', '{ts(hours=30)}', 0),
 ('{MARK}-B{M16}', {M16}, 8, DATE_SUB(NOW(), INTERVAL 30 HOUR), 'WAIT_INSPECTION', 'FROZEN', '{NOTE}', '{ts(hours=30)}',
     '{ts(hours=30)}', 0),
 ('{MARK}-B{M_M04}', {M_M04}, 8, DATE_SUB(NOW(), INTERVAL 30 HOUR), 'WAIT_INSPECTION', 'FROZEN', '{NOTE}',
     '{ts(hours=30)}', '{ts(hours=30)}', 0);

INSERT INTO wms_inbound_order (inbound_no, inbound_type, warehouse_id, source_order_type,
       inbound_time, audit_status, remark, created_time, updated_time, deleted_flag)
VALUES ('{MARK}-IN-01', 'PURCHASE', {WH_RAW}, 'PURCHASE',
        '{ts(hours=30)}', 'SUBMITTED', '{NOTE}', '{ts(hours=30)}', '{ts(hours=30)}', 0);

INSERT INTO wms_inbound_detail (inbound_id, material_id, batch_id, location_id, inbound_quantity,
       remark, created_time, updated_time, deleted_flag)
SELECT (SELECT inbound_id FROM wms_inbound_order WHERE inbound_no='{MARK}-IN-01'), d.material_id,
       d.batch_id, {LOC_RAW}, 50, '{NOTE}', '{ts(hours=30)}', '{ts(hours=30)}', 0
  FROM (SELECT {M_M15} AS material_id,
      (SELECT batch_id FROM wms_material_batch WHERE batch_no='{MARK}-B{M_M15}') AS batch_id
        UNION ALL SELECT {M16}, (SELECT batch_id FROM wms_material_batch WHERE batch_no='{MARK}-B{M16}')
        UNION ALL SELECT {M_M04}, (SELECT batch_id FROM wms_material_batch WHERE batch_no='{MARK}-B{M_M04}')
        UNION ALL SELECT {M_M01}, (SELECT batch_id FROM wms_material_batch WHERE batch_no='{MARK}-B{M_M01}')) d;
""")

    # ②-b 待检批次各挂一条"可用余量为 0"的原料仓库存行。
    #      "原料可用库存 < 50"这条规则的取值是 LEFT JOIN wms_inventory 后按 WAREHOUSE_TYPE 过滤的：
    #      物料在 wms_inventory 里一行都没有时 warehouseType 是 NULL，会被作用域直接排除，
    #      指标永远不命中。挂 0 余量的行既让作用域命中，也符合"料还没放行、可用就是 0"的真实语义；
    #      阶段 3 确认入库会把 50 加到同一行（同一仓/库位/批次/work_order_id=0），
    #      这条预警就随入库自然关闭，正好串起"到货待检 → 放行 → 入库 → 库存达标"的线性闭环。
    mysql(f"""
INSERT INTO wms_inventory (warehouse_id, location_id, material_id, batch_id, available_quantity,
       locked_quantity, inspection_quantity, work_order_id, remark, created_time, updated_time, deleted_flag)
SELECT {WH_RAW}, {LOC_RAW}, b.material_id, b.batch_id, 0, 0, 0, 0, '{NOTE}', '{ts()}', '{ts()}', 0
  FROM wms_material_batch b
 WHERE b.batch_no IN ('{MARK}-B{M_M15}', '{MARK}-B{M16}', '{MARK}-B{M_M04}');
""")

    # ③ 三张待提交的来料检验单，最早那张 30 小时前建单 → INSPECTION_OVERDUE
    mysql(f"""
INSERT INTO qms_inspection_order (inspection_no, inspection_type, scheme_id, batch_id,
       sample_quantity, status, remark, created_time, updated_time, deleted_flag)
VALUES
 ('{MARK}-QI-OLD', 'INCOMING', 47, (SELECT batch_id FROM wms_material_batch WHERE batch_no='{MARK}-B{M_M15}'),
  50, 'DRAFT', '{NOTE}', '{ts(hours=30)}', '{ts(hours=30)}', 0),
 ('{MARK}-QI-02', 'INCOMING', 47, (SELECT batch_id FROM wms_material_batch WHERE batch_no='{MARK}-B{M16}'),
  50, 'DRAFT', '{NOTE}', '{ts(hours=2)}', '{ts(hours=2)}', 0),
 ('{MARK}-QI-03', 'INCOMING', 47, (SELECT batch_id FROM wms_material_batch WHERE batch_no='{MARK}-B{M_M04}'),
  50, 'DRAFT', '{NOTE}', '{ts(hours=2)}', '{ts(hours=2)}', 0);
""")

    # ④ 生产订单 / 生产计划（已超期）/ 工单（下达未开工 + 超期 + 缺料）/ 停滞任务
    mysql(f"""
INSERT INTO mfg_production_order (production_order_no, customer_id, product_id, order_quantity,
       delivery_date, source_type, status, remark, created_time, updated_time, deleted_flag)
VALUES ('{MARK}-PO-01', NULL, {PRODUCT_L200}, 10, DATE_ADD(CURDATE(), INTERVAL 5 DAY),
        'MANUAL', 'RELEASED', '{NOTE}', '{ts(days=2)}', '{ts(days=2)}', 0);

INSERT INTO mfg_production_plan (plan_no, production_order_id, line_id, planned_start_time,
       planned_end_time, planned_quantity, status, remark, created_time, updated_time, deleted_flag)
VALUES ('{MARK}-PL-01', (SELECT production_order_id FROM mfg_production_order WHERE production_order_no='{MARK}-PO-01'),
        {LINE_FINAL}, DATE_SUB(NOW(), INTERVAL 54 HOUR), DATE_SUB(NOW(), INTERVAL 30 HOUR), 10,
        'RELEASED', '{NOTE}', '{ts(hours=54)}', '{ts(hours=30)}', 0);

INSERT INTO mfg_work_order (work_order_no, plan_id, product_id, bom_id, route_id, planned_quantity,
       completed_quantity, status, remark, created_time, updated_time, deleted_flag,
       work_order_type, planned_start_time, planned_end_time)
VALUES ('{MARK}-WO-01', (SELECT plan_id FROM mfg_production_plan WHERE plan_no='{MARK}-PL-01'),
        {PRODUCT_L200}, {BOM_L200}, {ROUTE_L200}, 10, 0, 'RELEASED', '{NOTE}',
        '{ts(hours=20)}', '{ts(hours=20)}', 0, 'ROOT',
        DATE_SUB(NOW(), INTERVAL 54 HOUR), DATE_SUB(NOW(), INTERVAL 30 HOUR));

INSERT INTO mfg_work_order_material (work_order_id, material_id, required_quantity,
       issued_quantity, shortage_quantity, status, remark, created_time, updated_time, deleted_flag)
VALUES ((SELECT work_order_id FROM mfg_work_order WHERE work_order_no='{MARK}-WO-01'), {M_M04},
        5, 0, 5, 'WAIT_READY', '{NOTE}', '{ts(hours=20)}', '{ts(hours=20)}', 0);

-- 停滞任务挂在一张已开工（RUNNING）的工单上：生产任务页默认只列「已开工/已完工/已关闭」
-- 工单下的任务，工单还停在下达态（RELEASED）时任务会被过滤掉，预警点「去处理」
-- 进去就是一张空表。WO-01 继续留在 RELEASED 覆盖「工单下达未开工」，WO-02 承载停滞任务。
INSERT INTO mfg_work_order (work_order_no, product_id, bom_id, route_id, planned_quantity,
       completed_quantity, status, remark, created_time, updated_time, deleted_flag,
       work_order_type, planned_start_time, planned_end_time)
VALUES ('{MARK}-WO-02', {PRODUCT_L200}, {BOM_L200}, {ROUTE_L200}, 10, 0, 'RUNNING', '{NOTE}',
        '{ts(hours=22)}', '{ts(hours=20)}', 0, 'ROOT',
        DATE_SUB(NOW(), INTERVAL 22 HOUR), DATE_ADD(NOW(), INTERVAL 26 HOUR));

INSERT INTO mfg_task (task_no, work_order_id, operation_id, station_id, planned_quantity,
       completed_quantity, defect_quantity, task_type, status, remark, created_time, updated_time, deleted_flag)
VALUES ('{MARK}-TK-01', (SELECT work_order_id FROM mfg_work_order WHERE work_order_no='{MARK}-WO-02'),
        15, {STATION_FINAL}, 10, 0, 0, 'NORMAL', 'RUNNING', '{NOTE}',
        '{ts(hours=22)}', '{ts(hours=20)}', 0);
""")

    # ⑤ 安灯告警 5 小时未响应 → ANDON_UNRESPONDED
    mysql(f"""
INSERT INTO mfg_andon_alert (alert_no, line_id, station_id, triggered_by, alert_type,
       trigger_time, status, remark, created_time, updated_time, deleted_flag)
VALUES ('{MARK}-AN-01', {LINE_FINAL}, {STATION_FINAL}, 3, 'MATERIAL',
        '{ts(hours=5)}', 'ACTIVE', '{NOTE}', '{ts(hours=5)}', '{ts(hours=5)}', 0);
""")

    # ⑥ 设备故障 10 小时 → EQUIPMENT_FAULT（会自动开维修单）；另有两张维修单覆盖 REPAIR_PENDING / REPAIR_WAIT_ACCEPTANCE
    mysql(f"""
UPDATE eam_equipment SET status='FAULT', updated_time='{ts(hours=10)}' WHERE equipment_id={EQ_FAULT};
INSERT INTO eam_equipment_status_log (equipment_id, running_status, downtime_type, alert_level,
       station_id, collected_time, description, remark, created_time, updated_time, deleted_flag)
VALUES ({EQ_FAULT}, 'FAULT', 'BREAKDOWN', 'CRITICAL', 24, '{ts(hours=10)}',
        '整机低压功率测试台无法上电', '{NOTE}', '{ts(hours=10)}', '{ts(hours=10)}', 0);

INSERT INTO eam_repair_order (repair_no, equipment_id, reported_by, reported_time,
       fault_description, status, remark, created_time, updated_time, deleted_flag)
VALUES ('{MARK}-RP-OLD', {EQ_REPAIR_OLD}, 13, '{ts(hours=10)}',
        '整机光学/响应测试台光源漂移，需校准', 'SUBMITTED', '{NOTE}', '{ts(hours=10)}', '{ts(hours=10)}', 0);

INSERT INTO eam_repair_order (repair_no, equipment_id, reported_by, reported_time, fault_description,
       repairer_id, repair_result, status, remark, created_time, updated_time, deleted_flag)
VALUES ('{MARK}-RP-WAIT', {EQ_WAIT}, 13, '{ts(hours=30)}',
        '色温及显色测量仪通讯中断', 13, '已更换通讯板，等待使用方验收', 'WAIT_ACCEPTANCE',
        '{NOTE}', '{ts(hours=30)}', '{ts(hours=20)}', 0);
""")

    # ⑦ 保养超期 23 天 / 点检超期 10 天
    mysql(f"""
INSERT INTO eam_maintenance_plan (equipment_id, maintenance_type, cycle_type, cycle_value,
       maintenance_content, responsible_id, status, remark, created_time, updated_time, deleted_flag)
VALUES ({EQ_MAINT}, 'PREVENTIVE', 'DAY', 7, '老化温度巡检仪季度校准与探头清洁', 13, 'ENABLE',
        '{NOTE}', '{ts(days=30)}', '{ts(days=30)}', 0);

INSERT INTO eam_spot_check_plan (equipment_id, check_cycle, check_items, responsible_role,
       status, remark, created_time, updated_time, deleted_flag)
VALUES ({EQ_SPOT}, '每日一次', '气路、夹具、安全防护、指示灯', '设备工程师', 'ENABLE',
        '{NOTE}', '{ts(days=10)}', '{ts(days=10)}', 0);
""")

    # ⑧ 不合格品处置：来料检验判不合格 → 生成不合格品处理单 30 小时未处置
    #    → NONCONFORMING_OPEN；后面阶段 9 走"审批 → 让步接收 → 关闭"把这条预警复核掉。
    #    处理单的处置完成接口要求来源检验单结论仍是 FAIL，所以这里配一张真实的 FAIL 检验单；
    #    来源批次单独建一张（质量状态 CONCESSION、无库存），不干扰待检积压与呆滞两个指标。
    mysql(f"""
INSERT INTO wms_material_batch (batch_no, material_id, supplier_id, received_date, quality_status,
       status, remark, created_time, updated_time, deleted_flag)
VALUES ('{MARK}-B-NCR', {M_M04}, 8, DATE_SUB(NOW(), INTERVAL 30 HOUR), 'CONCESSION', 'AVAILABLE',
        '{NOTE}', '{ts(hours=30)}', '{ts(hours=30)}', 0);

INSERT INTO qms_inspection_order (inspection_no, inspection_type, scheme_id, batch_id,
       inspector_id, sample_quantity, qualified_quantity, unqualified_quantity, inspection_time,
       inspection_result, status, remark, created_time, updated_time, deleted_flag)
VALUES ('{MARK}-QI-NCR', 'INCOMING', 47,
        (SELECT batch_id FROM wms_material_batch WHERE batch_no='{MARK}-B-NCR'),
        11, 50, 45, 5, '{ts(hours=30)}', 'FAIL', 'FINISHED', '{NOTE}', '{ts(hours=30)}', '{ts(hours=30)}', 0);

-- 不合格品处理单必须落在真实业务状态上：generateFromInspection 生成时写的是
-- approval_status=SUBMITTED、status=SUBMITTED（QmsNonconformingOrderServiceImpl），
-- 前端也只有 approvalStatus=SUBMITTED 才显示"审批处置单"、审批通过后才显示"完成处置"。
-- 早期这里直接写 DRAFT/OPEN，这两个值既不在前端字典里、也不可能由业务产生，
-- 结果演示到阶段 9 时页面上两个按钮都不出现，单子卡住无法处置。
INSERT INTO qms_nonconforming_order (ncr_no, inspection_id, defect_reason, disposition_type,
       approval_status, status, remark, created_by, created_time, updated_by, updated_time, deleted_flag)
VALUES ('{MARK}-NC-01',
        (SELECT inspection_id FROM qms_inspection_order WHERE inspection_no='{MARK}-QI-NCR'),
        '灯头外壳来料尺寸超差 0.3mm', 'CONCESSION', 'SUBMITTED', 'SUBMITTED',
        '{NOTE}', 1, '{ts(hours=30)}', 1, '{ts(hours=30)}', 0);
""")

    # ⑧-b 让来源检验单的结果明细与判定数量自洽：
    #     50 件 × 2 个检验项（外观、线缆长度）= 100 条检验结果，其中 45 件全合格、
    #     5 件长度项超差（13.1mm 超出 11.4~12.6mm）。这样检验单上
    #     sample/qualified/unqualified = 50/45/5 与结果明细条数完全对得上，
    #     不合格品处理单再挂到那 5 条 FAIL 结果上，追溯链才是完整的。
    ncr_inspection = one(f"""SELECT inspection_id FROM qms_inspection_order
                              WHERE inspection_no='{MARK}-QI-NCR'""")[0]
    scheme_items = rows("""SELECT si.item_id, s.standard_value FROM qms_inspection_scheme_item si
                             JOIN qms_inspection_standard s ON s.standard_id=si.standard_id
                            WHERE si.scheme_id=47 AND si.deleted_flag=0 ORDER BY si.sequence_no""")
    result_values = []
    first_fail_result = None
    for number in range(1, 51):
        sized_fail = number > 45
        for sequence, (item_id, standard_value) in enumerate(scheme_items):
            # 只有第二个检验项（线缆长度，标准值 12）在 46~50 号件上超差。
            failed = sized_fail and sequence == len(scheme_items) - 1
            measured = "13.1" if failed else str(standard_value)
            judgement = "FAIL" if failed else "PASS"
            defect = "'灯头外壳来料尺寸超差 0.3mm'" if failed else "NULL"
            result_values.append(
                f"({ncr_inspection}, '{MARK}-{ncr_inspection}-{number:03d}', {item_id}, "
                f"'{measured}', '{judgement}', {defect}, '{NOTE}', '{ts(hours=30)}', "
                f"'{ts(hours=30)}', 0)"
            )
            if failed and first_fail_result is None:
                first_fail_result = (f"{MARK}-{ncr_inspection}-{number:03d}", item_id)
    mysql(f"""
INSERT INTO qms_inspection_result (inspection_id, piece_no, item_id, measured_value, result,
       defect_description, remark, created_time, updated_time, deleted_flag)
VALUES {','.join(result_values)};
""")

    # 明细挂到第一条不合格结果上，不合格品处理单能顺着明细追到具体件号与检验项。
    fail_row = one(f"""SELECT result_id FROM qms_inspection_result
                        WHERE inspection_id={ncr_inspection} AND piece_no='{first_fail_result[0]}'
                          AND item_id={first_fail_result[1]} AND deleted_flag=0""")[0]
    mysql(f"""
INSERT INTO qms_nonconforming_detail (ncr_id, inspection_result_id, defect_quantity,
       final_disposition, remark, created_time, updated_time, deleted_flag)
VALUES ((SELECT ncr_id FROM qms_nonconforming_order WHERE ncr_no='{MARK}-NC-01'),
        {fail_row}, 5, 'CONCESSION', '{NOTE}', '{ts(hours=30)}', '{ts(hours=30)}', 0);
""")

    # ⑨ 排程冲突 2 项 → SCHEDULE_CONFLICT
    #    这里必须造得能被排程工作台取到，否则预警「去处理」跳进工作台是空板子：
    #    ① 场景算法码必须是 MANUAL_BOARD（工作台只列手工编排草案）；
    #    ② 场景 created_by 必须是当前操作人（工作台按 create_by 认领「我的草案」）；
    #    ③ 排程项必须有起止时间（时间轴按 planned_start_time 排序，为空的项画不出来）；
    #    ④ 排程项所属订单不能再有正式生产计划（同订单已有计划时，手工项被视为过期数据隐藏），
    #       所以这里用 PO-02 与新建的 PO-03，避开已经有 PL-01/PL-LOAD 的 PO-01。
    mysql(f"""
INSERT INTO mfg_production_order (production_order_no, customer_id, product_id, order_quantity,
       delivery_date, source_type, status, remark, created_time, updated_time, deleted_flag)
VALUES ('{MARK}-PO-02', NULL, {PRODUCT_L200}, 6, DATE_ADD(CURDATE(), INTERVAL 7 DAY),
        'MANUAL', 'DRAFT', '{NOTE}', '{ts(days=2)}', '{ts(days=2)}', 0);

INSERT INTO mfg_production_order (production_order_no, customer_id, product_id, order_quantity,
       delivery_date, source_type, status, remark, created_time, updated_time, deleted_flag)
VALUES ('{MARK}-PO-03', NULL, {PRODUCT_L200}, 5, DATE_ADD(CURDATE(), INTERVAL 8 DAY),
        'MANUAL', 'DRAFT', '{NOTE}', '{ts(days=2)}', '{ts(days=2)}', 0);

INSERT INTO mfg_schedule_scenario (scenario_no, scenario_name, horizon_start, horizon_end,
       algorithm_code, status, order_count, scheduled_order_count, conflict_order_count,
       late_order_count, total_load_minutes, remark, created_by, created_time, updated_by,
       updated_time, deleted_flag)
VALUES ('{MARK}-SC-01', '演示：整机总装线冲突排程', DATE_SUB(NOW(), INTERVAL 1 DAY),
        DATE_ADD(NOW(), INTERVAL 6 DAY), 'MANUAL_BOARD', 'DRAFT', 2, 0, 2, 0, 90,
        '{NOTE}', 1, '{ts(days=1)}', 1, '{ts(days=1)}', 0);

INSERT INTO mfg_schedule_item (scenario_id, production_order_id, product_id, line_id, sequence_no,
       planned_quantity, planned_start_time, planned_end_time, estimated_minutes, priority_score,
       tardiness_minutes, decision_source, status, conflict_reason, explanation, remark,
       created_by, created_time, updated_by, updated_time, deleted_flag)
VALUES
 ((SELECT scenario_id FROM mfg_schedule_scenario WHERE scenario_no='{MARK}-SC-01'),
  (SELECT production_order_id FROM mfg_production_order WHERE production_order_no='{MARK}-PO-02'),
  {PRODUCT_L200}, {LINE_FINAL}, 1, 6,
  DATE_ADD(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 2 DAY), 50, 50,
  0, 'MANUAL', 'CONFLICT',
  '同一时段被两张订单占用', '总装线产能不足以同时承接两张订单', '{NOTE}', 1,
  '{ts(days=1)}', 1, '{ts(days=1)}', 0),
 ((SELECT scenario_id FROM mfg_schedule_scenario WHERE scenario_no='{MARK}-SC-01'),
  (SELECT production_order_id FROM mfg_production_order WHERE production_order_no='{MARK}-PO-03'),
  {PRODUCT_L200}, {LINE_FINAL}, 2, 5,
  DATE_ADD(NOW(), INTERVAL 30 HOUR), DATE_ADD(NOW(), INTERVAL 54 HOUR), 40, 40,
  0, 'MANUAL', 'CONFLICT',
  '交期与产能窗口冲突', '按当前日产能无法在交期前完成', '{NOTE}', 1,
  '{ts(days=1)}', 1, '{ts(days=1)}', 0);
""")

    # ⑩ 产线未来 7 天负荷 1900/1400 = 136% → LINE_LOAD_RATIO
    mysql(f"""
INSERT INTO mfg_production_plan (plan_no, production_order_id, line_id, planned_start_time,
       planned_end_time, planned_quantity, status, remark, created_time, updated_time, deleted_flag)
VALUES ('{MARK}-PL-LOAD',
    (SELECT production_order_id FROM mfg_production_order WHERE production_order_no='{MARK}-PO-01'),
        {LINE_FINAL}, DATE_ADD(NOW(), INTERVAL 2 DAY), DATE_ADD(NOW(), INTERVAL 4 DAY), 1900,
        'RELEASED', '{NOTE}', '{ts()}', '{ts()}', 0);
""")

    # ⑪ 补货建议 30 小时未处理 → REPLENISHMENT_PENDING
    mysql(f"""
INSERT INTO wms_replenishment_suggestion (suggestion_no, material_id, required_quantity,
       available_quantity, suggested_quantity, source_type, status, remark, created_time, updated_time, deleted_flag)
VALUES ('{MARK}-RS-01', {M_M04}, 60, 0, 60, 'INVENTORY_WARNING', 'SUBMITTED',
        '{NOTE}', '{ts(hours=30)}', '{ts(hours=30)}', 0);
""")

    print("阶段 0 已就绪：19 个指标都有可命中的真实业务数据。")


# --------------------------------------------------------------------------- 扫描


def scan(api, title="扫描"):
    result = api.call("POST", "/api/ai/alert/scan", {})["data"]
    print(
        f'[{title}] scanId={result.get("scanId")} status={result.get("status")} '
        f'规则={result.get("ruleCount")} 评估={result.get("evaluatedCount")} '
        f'新预警={result.get("openedCount")} 升级={result.get("escalatedCount")} '
        f'持续={result.get("repeatedCount")} 自动动作={result.get("autoActionCount")} '
        f'通知={result.get("notifiedCount")} 复核关闭={result.get("closedCount")}'
    )
    return result


# --------------------------------------------------------------------------- 阶段推进


def stage1(api):
    """到货登记：入库单还是待确认，待办里出现"待入库确认"。"""
    print("阶段 1 到货登记：采购入库单 DEMO-ALERT-IN-01 待确认，批次待检")
    todo = api.call("GET", "/api/ai/todo/my")["data"]
    kinds = sorted({item["type"] for item in todo.get("items", [])})
    print(f"  当前待办类型：{kinds}")
    scan(api, "阶段1")


def stage2(api):
    """来料检验：三张待检单逐件全检提交合格 → 批次放行 → 待检积压/检验超期自动复核关闭。"""
    print("阶段 2 来料检验：三张待检单逐件全检提交 PASS，批次放行")
    count = _submit_incoming(api)
    released = rows(f"""SELECT batch_no, quality_status, status FROM wms_material_batch
                         WHERE batch_no LIKE '{MARK}-B%' AND material_id IN ({M_M15},{M16},{M_M04})""")
    print(f"  已提交 {count} 张检验单；批次状态：{[(b, q, s) for b, q, s in released]}")
    scan(api, "阶段2")


def stage3(api):
    """确认入库：库存增加 + 对已放行但无检验单的批次自动签发来料检验单。"""
    print("阶段 3 确认入库：采购入库单确认，系统自动签发来料检验任务")
    inbound = one(f"SELECT inbound_id FROM wms_inbound_order WHERE inbound_no='{MARK}-IN-01'")[0]
    before = one("SELECT COUNT(*) FROM qms_inspection_order WHERE inspection_type='INCOMING' AND deleted_flag=0")[0]
    api.call("PUT", f"/api/wms/inbound-order/{inbound}/confirm", {"remark": NOTE})
    after = one("SELECT COUNT(*) FROM qms_inspection_order WHERE inspection_type='INCOMING' AND deleted_flag=0")[0]
    stock = rows(f"""SELECT m.material_code, SUM(i.available_quantity)
                       FROM wms_inventory i JOIN mdm_material m ON m.material_id=i.material_id
                      WHERE i.material_id IN ({M_M01},{M_M04},{M_M15},{M16})
                        AND i.deleted_flag=0 GROUP BY m.material_code
                      ORDER BY m.material_code""")
    print(f"  确认入库成功；来料检验单 {before} → {after} 张（自动签发）")
    print(f"  演示批次可用库存：{[(code, str(qty)) for code, qty in stock]}")
    todo = api.call("GET", "/api/ai/todo/my")["data"]
    print(f'  待办：{[(item["type"], item["title"]) for item in todo.get("items", [])][:6]}')
    scan(api, "阶段3")


def _submit_incoming(api):
    """把三张自动签发的来料检验单按逐件全检提交为合格。"""
    items = rows("""SELECT si.item_id, s.standard_value FROM qms_inspection_scheme_item si
                      JOIN qms_inspection_standard s ON s.standard_id=si.standard_id
                     WHERE si.scheme_id=47 AND si.deleted_flag=0 ORDER BY si.sequence_no""")
    orders = rows(f"""SELECT inspection_id, sample_quantity FROM qms_inspection_order
                       WHERE inspection_type='INCOMING' AND status='DRAFT' AND deleted_flag=0
                         AND batch_id IN (SELECT batch_id FROM wms_material_batch WHERE batch_no LIKE '{MARK}-B%')
                       ORDER BY inspection_id""")
    for inspection_id, quantity in orders:
        results = []
        for number in range(1, int(float(quantity)) + 1):
            for item_id, value in items:
                results.append(
                    {
                        "pieceNo": f"{MARK}-{inspection_id}-{number:03d}",
                        "itemId": int(item_id),
                        "measuredValue": value,
                        "result": "PASS",
                        "remark": NOTE,
                    }
                )
        api.call(
            "PUT",
            f"/api/qms/inspection-order/{inspection_id}/submit-piece",
            {"results": results, "remark": "来料检验合格，批次放行"},
        )
        print(f"  检验单 {inspection_id} 已逐件提交 PASS（{len(results)} 条检验结果）")
    return len(orders)


def stage4(api):
    """补货建议处理：仓管确认补货建议 → 补货未处理预警关闭。"""
    print("阶段 4 补货建议处理：仓管把演示补货建议置为已处理")
    suggestion = one(f"SELECT suggestion_id FROM wms_replenishment_suggestion WHERE suggestion_no='{MARK}-RS-01'")[0]
    api.call(
        "PUT",
        "/api/wms/replenishment-suggestion",
        {"suggestionId": int(suggestion), "status": "HANDLED", "remark": "仓管已安排补货并确认"},
    )
    print(f"  补货建议 {suggestion} → HANDLED")
    scan(api, "阶段4")


def stage5(api):
    """生产开工：工单下达未开工/超期与停滞任务随开工报工恢复。"""
    print("阶段 5 生产开工：工单开工、任务完成、安灯响应")
    work_order = one(f"SELECT work_order_id FROM mfg_work_order WHERE work_order_no='{MARK}-WO-01'")[0]
    mysql(f"""
UPDATE mfg_work_order SET status='RUNNING', planned_start_time=NOW(), updated_time=NOW()
 WHERE work_order_id={work_order};
UPDATE mfg_task SET status='FINISHED', completed_quantity=10, updated_time=NOW()
 WHERE task_no='{MARK}-TK-01';
UPDATE mfg_andon_alert SET status='RESPONDED', response_time=NOW(), updated_time=NOW()
 WHERE alert_no='{MARK}-AN-01';
""")
    print(f"  工单 {work_order} → RUNNING；任务 DEMO-ALERT-TK-01 → FINISHED；安灯 → RESPONDED")
    scan(api, "阶段5")


def stage6(api):
    """缺料补料：补货入库后缺口补齐，工单缺料与可用库存预警一起关闭。"""
    print("阶段 6 缺料补齐：工单缺料行缺口归零")
    mysql(f"""
UPDATE mfg_work_order_material SET shortage_quantity=0, issued_quantity=5, status='READY', updated_time=NOW()
 WHERE work_order_id=(SELECT work_order_id FROM mfg_work_order WHERE work_order_no='{MARK}-WO-01')
   AND material_id={M_M04};
""")
    scan(api, "阶段6")


def stage7(api):
    """设备维修：故障维修完成、待验收单验收、老维修单接单关闭。"""
    print("阶段 7 设备维修：三张维修单闭环，设备恢复运行")
    # 自动开出来的维修单先接单再关闭，走真实接口
    for repair_id, status in rows(f"""SELECT repair_id, status FROM eam_repair_order
                                       WHERE (repair_no LIKE 'RP-ALERT-%' OR repair_no LIKE '{MARK}-RP-%')
                                         AND deleted_flag=0"""):
        if status == "SUBMITTED":
            api.call("PUT", f"/api/eam/repair-order/{repair_id}/assign", {"repairerId": 13, "remark": "设备工程师接单"})
        api.call(
            "PUT",
            f"/api/eam/repair-order/{repair_id}/close",
            {"repairResult": "已排除故障并试运行正常", "repairCost": 0},
        )
        print(f"  维修单 {repair_id} 已关闭")
    mysql(
        f"UPDATE eam_equipment SET status='RUNNING', updated_time=NOW() "
        f"WHERE equipment_id IN ({EQ_FAULT},{EQ_REPAIR_OLD},{EQ_WAIT})"
    )
    scan(api, "阶段7")


def stage8(api):
    """保养点检：补一次保养与点检记录，超期预警关闭。"""
    print("阶段 8 保养点检：补录保养与点检记录")
    plan = one(f"SELECT plan_id FROM eam_maintenance_plan WHERE remark='{NOTE}' AND equipment_id={EQ_MAINT}")[0]
    spot = one(f"SELECT plan_id FROM eam_spot_check_plan WHERE remark='{NOTE}' AND equipment_id={EQ_SPOT}")[0]
    api.call(
        "POST",
        "/api/eam/maintenance-record",
        {
            "planId": int(plan),
            "equipmentId": EQ_MAINT,
            "maintainerId": 13,
            "maintenanceTime": local_now().strftime("%Y-%m-%dT%H:%M:%S"),
            "maintenanceResult": "校准完成，温度曲线在允差内",
            "nextMaintenanceTime": (local_now() + timedelta(days=7)).strftime("%Y-%m-%dT%H:%M:%S"),
            "remark": NOTE,
        },
    )
    api.call(
        "POST",
        "/api/eam/spot-check-record",
        {
            "planId": int(spot),
            "equipmentId": EQ_SPOT,
            "checkerId": 13,
            "checkTime": local_now().strftime("%Y-%m-%dT%H:%M:%S"),
            "checkResult": "PASS",
            "remark": NOTE,
        },
    )
    print("  保养记录与点检记录已补录")
    scan(api, "阶段8")


def stage9(api):
    """不合格处置：审批通过 → 让步接收完成处置 → 单关闭，未处置预警复核关闭。"""
    print("阶段 9 不合格品处置：审批通过 → 完成处置 → NC 单关闭")
    ncr = one(f"SELECT ncr_id FROM qms_nonconforming_order WHERE ncr_no='{MARK}-NC-01'")[0]
    api.call(
        "PUT",
        f"/api/qms/nonconforming-order/{ncr}/approve",
        {"approvalResult": "APPROVED", "approvalComment": "评审同意让步接收，供应商已确认整改"},
    )
    print(f"  处理单 {ncr} 审批通过 → PROCESSING")
    api.call(
        "PUT",
        f"/api/qms/nonconforming-order/{ncr}/complete-disposition",
        {"dispositionType": "CONCESSION", "remark": "让步接收，加严下道工序检验"},
    )
    print(f"  处理单 {ncr} 完成处置 → CLOSED")
    scan(api, "阶段9")


def stage10(api):
    """排程与负荷：冲突项落位、负荷计划下调。"""
    print("阶段 10 排程与负荷：冲突项重新落位、负荷计划下调")
    scenario = one(f"SELECT scenario_id FROM mfg_schedule_scenario WHERE scenario_no='{MARK}-SC-01'")[0]
    # 排程项的状态只有 SCHEDULED / CONFLICT / CONFIRMED 三种（MfgScheduleScenarioServiceImpl
    # 的 ITEM_STATUS_* 常量），冲突解决后就回到 SCHEDULED，仍留在工作台时间轴上可查看。
    mysql(f"""
UPDATE mfg_schedule_item SET status='SCHEDULED', conflict_reason=NULL, updated_time=NOW()
 WHERE scenario_id={scenario};
UPDATE mfg_schedule_scenario SET status='CALCULATED', conflict_order_count=0,
       scheduled_order_count=2, updated_time=NOW()
 WHERE scenario_id={scenario};
UPDATE mfg_production_plan SET planned_quantity=1000, updated_time=NOW() WHERE plan_no='{MARK}-PL-LOAD';
""")
    scan(api, "阶段10")


def stage11(api):
    """计划完工：计划与工单完工，超期预警关闭。"""
    print("阶段 11 计划完工：生产计划与工单完工")
    mysql(f"""
UPDATE mfg_work_order SET status='FINISHED', completed_quantity=10, updated_time=NOW()
 WHERE work_order_no='{MARK}-WO-01';
-- WO-02 承载停滞任务，任务在阶段 5 已完成，工单在这里一起收口，
-- 否则演示跑完还留一张在制工单，下次看板会多出一张没人处理的生产任务。
UPDATE mfg_work_order SET status='FINISHED', completed_quantity=10, updated_time=NOW()
 WHERE work_order_no='{MARK}-WO-02';
UPDATE mfg_production_plan SET status='FINISHED', updated_time=NOW() WHERE plan_no='{MARK}-PL-01';
UPDATE mfg_production_order SET status='FINISHED', updated_time=NOW() WHERE production_order_no='{MARK}-PO-01';
""")
    scan(api, "阶段11")


def stage12(api):
    """呆滞消耗：把 120 天的呆滞批次用完，同时补一批新料，呆滞预警关闭而库存仍然健康。"""
    print("阶段 12 呆滞消耗：DEMO-ALERT-B34 用尽，同时补入新批次")
    mysql(f"""
UPDATE wms_inventory SET available_quantity=0, updated_time=NOW()
 WHERE batch_id=(SELECT batch_id FROM wms_material_batch WHERE batch_no='{MARK}-B{M_M03}');
UPDATE wms_material_batch SET status='EXHAUSTED', updated_time=NOW() WHERE batch_no='{MARK}-B{M_M03}';

INSERT INTO wms_material_batch (batch_no, material_id, supplier_id, received_date, quality_status,
       status, remark, created_time, updated_time, deleted_flag)
VALUES ('{MARK}-B{M_M03}-NEW', {M_M03}, 8, CURDATE(), 'PASS', 'AVAILABLE',
        '{NOTE}', '{now_ts()}', '{now_ts()}', 0);
INSERT INTO wms_inventory (warehouse_id, location_id, material_id, batch_id, available_quantity,
       locked_quantity, inspection_quantity, work_order_id, remark, created_time, updated_time, deleted_flag)
SELECT {WH_RAW}, {LOC_RAW}, {M_M03}, batch_id, 60, 0, 0, 0, '{NOTE}', '{now_ts()}', '{now_ts()}', 0
  FROM wms_material_batch WHERE batch_no='{MARK}-B{M_M03}-NEW';
""")
    scan(api, "阶段12")


STAGES = {
    1: stage1,
    2: stage2,
    3: stage3,
    4: stage4,
    5: stage5,
    6: stage6,
    7: stage7,
    8: stage8,
    9: stage9,
    10: stage10,
    11: stage11,
    12: stage12,
}


# --------------------------------------------------------------------------- 校验


def verify():
    print("\n=== 19 个指标的预警运行情况 ===")
    data = rows("""
SELECT r.metric_code,
       (SELECT COUNT(*) FROM ai_alert_event e WHERE e.rule_id=r.rule_id AND e.status='OPEN'),
       (SELECT COUNT(*) FROM ai_alert_event e WHERE e.rule_id=r.rule_id AND e.status='CLOSED'),
       (SELECT COUNT(*) FROM ai_alert_event e WHERE e.rule_id=r.rule_id),
       (SELECT COUNT(*) FROM ai_alert_action a JOIN ai_alert_event e ON e.alert_id=a.alert_id
         WHERE e.rule_id=r.rule_id AND a.action_type='NOTIFY'),
       (SELECT COUNT(*) FROM ai_alert_action a JOIN ai_alert_event e ON e.alert_id=a.alert_id
         WHERE e.rule_id=r.rule_id AND a.action_type='REPLENISH'),
       (SELECT COUNT(*) FROM ai_alert_action a JOIN ai_alert_event e ON e.alert_id=a.alert_id
         WHERE e.rule_id=r.rule_id AND a.action_type='EXCEPTION'),
       (SELECT COUNT(*) FROM ai_alert_action a JOIN ai_alert_event e ON e.alert_id=a.alert_id
         WHERE e.rule_id=r.rule_id AND a.action_type='REPAIR'),
       (SELECT COUNT(*) FROM ai_alert_action a JOIN ai_alert_event e ON e.alert_id=a.alert_id
         WHERE e.rule_id=r.rule_id AND a.action_type='VERIFY')
  FROM ai_alert_rule r
 WHERE r.status='ENABLED' AND r.deleted_flag=0
 ORDER BY r.rule_id
""")
    header = f'{"指标":<28}{"进行中":>7}{"已关闭":>7}{"事件":>6}{"通知":>6}{"补货":>6}{"异常":>6}{"维修":>6}{"复核":>6}'
    print(header)
    print("-" * 96)
    for row in data:
        print(f"{row[0]:<28}{row[1]:>7}{row[2]:>7}{row[3]:>6}{row[4]:>6}{row[5]:>6}{row[6]:>6}{row[7]:>6}{row[8]:>6}")
    print("\n=== 自动动作产出 ===")
    for row in rows("""SELECT action_type, action_status, COUNT(*) FROM ai_alert_action
                        GROUP BY action_type, action_status ORDER BY action_type, action_status"""):
        print(f"  {row[0]:<10}{row[1]:<10}{row[2]}")
    print("\n=== 异常台账（预警自动登记）===")
    for row in rows(
        """SELECT status, COUNT(*) FROM mfg_exception WHERE exception_no LIKE 'EXC-ALERT-%' GROUP BY status"""
    ):
        print(f"  {row[0]:<10}{row[1]}")
    print("\n=== 补货建议 ===")
    for row in rows("""SELECT status, source_type, COUNT(*) FROM wms_replenishment_suggestion
                        WHERE deleted_flag=0 GROUP BY status, source_type ORDER BY status"""):
        print(f"  {row[0]:<12}{row[1]:<20}{row[2]}")
    print("\n=== 待办入口 ===")
    api = Api().login()
    todo = api.call("GET", "/api/ai/todo/my")["data"]
    for item in todo.get("items", [])[:12]:
        print(f'  {item["type"]:<22}{item["typeLabel"]:<14}{item["title"]}')
    print(f'  合计 {todo.get("total")} 条')


def main():
    global PORT, DATABASE, DB_USER, DB_PASSWORD, API_PORT
    parser = argparse.ArgumentParser()
    parser.add_argument("--clean", action="store_true")
    parser.add_argument("--prepare", action="store_true")
    parser.add_argument("--scan", action="store_true")
    parser.add_argument("--stage", type=int, action="append")
    parser.add_argument("--verify", action="store_true")
    parser.add_argument("--all", action="store_true")
    parser.add_argument("--port", type=int, help="MySQL 端口，默认 3310（演练库）；3311 = 预警中心测试库")
    parser.add_argument("--database", help="数据库名，默认 mes_lamp_rehearsal")
    parser.add_argument("--user", help="数据库用户，默认 mes_lamp_app")
    parser.add_argument("--password", help="数据库口令，默认读环境变量 MYSQL_PWD")
    parser.add_argument("--api-port", type=int, default=8089, help="后端端口，默认 8089")
    args = parser.parse_args()
    if not any([args.clean, args.prepare, args.scan, args.stage, args.verify, args.all]):
        parser.print_help()
        return
    if args.port:
        PORT = args.port
    if args.database:
        DATABASE = args.database
    if args.user:
        DB_USER = args.user
    if args.password is not None:
        DB_PASSWORD = args.password
    API_PORT = args.api_port
    print(f"目标库：{DB_USER}@{PORT}/{DATABASE}，后端 http://127.0.0.1:{args.api_port}")
    if args.all:
        clean()
        prepare()
        api = Api().login()
        scan(api, "初始扫描")
        for number in sorted(STAGES):
            STAGES[number](api)
        verify()
        return
    if args.clean:
        clean()
    if args.prepare:
        prepare()
    if args.scan or args.stage:
        api = Api().login()
        if args.scan:
            scan(api, "手动扫描")
        for number in args.stage or []:
            STAGES[number](api)
    if args.verify:
        verify()


if __name__ == "__main__":
    sys.exit(main())
