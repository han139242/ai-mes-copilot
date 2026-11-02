"""台灯结构迁移的共享顺序；演示库存、产能与工位种子不自动执行。"""

FRESH_SCHEMA_MIGRATIONS = (
    'lamp_foundation_v1.sql', 'lamp_master_data_v1.sql',
    'lamp_preparation_v1.sql', 'lamp_preparation_master_v1.sql',
    'lamp_rbac_v1.sql', 'lamp_execution_v1.sql', 'lamp_snapshot_v2.sql',
    'lamp_one_plan_many_orders_v1.sql',
    'lamp_supply_v3.sql', 'lamp_supply_v4.sql', 'lamp_supply_v5.sql',
    'lamp_alert_v1.sql', 'sales_purchase_order_v1.sql',
    'sales_purchase_order_permission_v1.sql',
    'ai_agent_v1.sql', 'ai_agent_v2.sql', 'ai_conversation_v1.sql',
    'ai_sequence_message_v1.sql', 'ai_message_client_id_v1.sql',
    'ai_anomaly_v1.sql', 'ai_automation_v1.sql',
    'ai_knowledge_v1.sql', 'ai_task_v1.sql', 'ai_task_guard_v1.sql',
    'lamp_final_scheme_binding_v1.sql', 'qms_piece_inspection_v1.sql',
    'business_number_v1.sql', 'lamp_line_side_location_v1.sql',
    'work_report_defect_description_v1.sql',
    'lamp_stock_component_inspection_v1.sql',
)

EXECUTION_MIGRATIONS = (
    'lamp_rbac_v1.sql', 'lamp_execution_v1.sql', 'lamp_snapshot_v2.sql',
    'lamp_one_plan_many_orders_v1.sql',
    'lamp_supply_v3.sql', 'lamp_supply_v4.sql', 'lamp_supply_v5.sql',
    'lamp_alert_v1.sql', 'sales_purchase_order_v1.sql',
    'sales_purchase_order_permission_v1.sql',
    'business_number_v1.sql', 'lamp_line_side_location_v1.sql',
    'work_report_defect_description_v1.sql',
    'lamp_stock_component_inspection_v1.sql',
    # AI 扩展的脚本也要在这条链上：AiAlertScanner / AiAnomalyScanner / AiAutomationScanner /
    # AiTaskScanner 默认全部启用（各自 scheduling-enabled 缺省 true），它们查询的表和列
    # 只要有一个没建，定时任务就会每轮抛 Unknown column 或 Unknown table。
    # ai_automation_v1 与 lamp_alert_v1 是两个并行的库存预警扩展，必须都在。
    'ai_agent_v1.sql', 'ai_agent_v2.sql', 'ai_conversation_v1.sql',
    'ai_sequence_message_v1.sql', 'ai_message_client_id_v1.sql',
    'ai_anomaly_v1.sql', 'ai_automation_v1.sql',
    'ai_knowledge_v1.sql', 'ai_task_v1.sql', 'ai_task_guard_v1.sql',
)

# 报告草稿必须在任何业务发生前对齐；不导入示例订单、库存和人员。
CLEAN_BASELINE_MIGRATIONS = (
    *FRESH_SCHEMA_MIGRATIONS[:4], 'lamp_report_alignment_v18.sql',
    *FRESH_SCHEMA_MIGRATIONS[4:],
)
