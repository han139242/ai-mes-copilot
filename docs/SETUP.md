# 环境配置与启动

简体中文 · [English](SETUP.en.md) · [项目首页](../README.md)

以下命令从仓库根目录开始，适用于 Windows PowerShell。完整开发与验证使用 JDK 21、Node.js 24（本轮为 24.19.0）、MySQL 8.0 和 Python 3；让 `java`、`mysql`、`node`、`npm`、`python` 可以在终端调用。

前端构建依赖声明兼容 Node.js `^20.19.0 || >=22.12.0`；会话测试还使用 `node:module.registerHooks`，该接口从 Node.js 22.15.0 / 23.5.0 开始提供，见[官方说明](https://nodejs.org/api/module.html#moduleregisterhooksoptions)。使用 Node.js 24 可复现本轮完整前端测试。

## 1. 创建独立演示库

创建一个 `mes_lamp_` 前缀的全新数据库，例如 `mes_lamp_demo`。先执行 `workspace/idea/ems/src/main/resources/db/schema.sql`，再依次执行下面的迁移；顺序来自 `scripts/lamp_migrations.py` 的 `CLEAN_BASELINE_MIGRATIONS`：

```text
lamp_foundation_v1.sql
lamp_master_data_v1.sql
lamp_preparation_v1.sql
lamp_preparation_master_v1.sql
lamp_report_alignment_v18.sql
lamp_rbac_v1.sql
lamp_execution_v1.sql
lamp_snapshot_v2.sql
lamp_one_plan_many_orders_v1.sql
lamp_supply_v3.sql
lamp_supply_v4.sql
lamp_supply_v5.sql
lamp_alert_v1.sql
sales_purchase_order_v1.sql
sales_purchase_order_permission_v1.sql
ai_agent_v1.sql
ai_agent_v2.sql
ai_conversation_v1.sql
ai_sequence_message_v1.sql
ai_message_client_id_v1.sql
ai_anomaly_v1.sql
ai_automation_v1.sql
ai_knowledge_v1.sql
ai_task_v1.sql
ai_task_guard_v1.sql
lamp_final_scheme_binding_v1.sql
qms_piece_inspection_v1.sql
business_number_v1.sql
lamp_line_side_location_v1.sql
work_report_defect_description_v1.sql
lamp_stock_component_inspection_v1.sql
```

所有 SQL 均在 `workspace/idea/ems/src/main/resources/db/`。其中 `lamp_report_alignment_v18.sql` 是产品、工艺与检验主数据对齐脚本，必须在主数据仍为草稿、没有生产和库存交易时执行；它不依赖个人报告文件。

需要演练账号及运行示例时，再显式执行 `lamp_demo_seed_v1.sql`。组件产能和工位映射由 `lamp_single_line_v1.sql`、`lamp_station_operation_v1.sql` 补齐；执行前先按自身演练场景维护工艺路线、产线、工位和排班。库存使用入库、领料、移转等正式单据维护。

## 2. 配置后端

在启动后端的同一个终端设置自己的连接参数；示例中的占位值需要替换：

```powershell
$env:DB_URL = "jdbc:mysql://127.0.0.1:3306/mes_lamp_demo?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
$env:DB_USERNAME = "<自己的数据库账号>"
$env:DB_PASSWORD = "<自己的数据库密码>"
$env:JWT_SECRET = "<自己生成的至少32字节随机密钥>"
$env:SERVER_PORT = "8088"
$env:FILE_UPLOAD_ROOT = "<自己的上传目录>"
$env:FILE_UPLOAD_TEMP_ROOT = "<自己的上传临时目录>"
```

使用 AI 时，设置 `AI_ENABLED=true`、`AI_API_KEY`、`AI_API_URL` 和 `AI_MODEL`，值以自己的模型服务配置为准。后端支持 OpenAI 兼容的模型服务请求格式。暂不配置模型服务时设置 `AI_ENABLED=false`，先运行普通业务模块。

```powershell
Push-Location workspace/idea/ems
.\mvnw.cmd -B spring-boot:run
Pop-Location
```

Linux / macOS 使用 `./mvnw`。配置定义位于 `src/main/resources/application.properties`，真实密码和密钥通过环境变量提供。

## 3. 启动 PC 前端

另开终端，从仓库根目录运行：

```powershell
Push-Location workspace/vscode/ems-forword
npm ci
$env:VITE_API_PROXY_TARGET = "http://127.0.0.1:8088"
npm run dev
Pop-Location
```

打开 `http://127.0.0.1:5173`。前端代理目标需要与 `SERVER_PORT` 一致。生产构建输出到 `dist`，由静态服务器将 `/api` 转发至后端，或构建时配置 `VITE_API_BASE_URL`。

## 4. 运行监管工具

已有自己维护的数据库服务与环境脚本时，可将 `scripts/local-runtime.example.json` 复制为忽略目录内的 `tmp/local-runtime.json`，填写自己的路径、端口和库名，再使用 `scripts/manage-local.ps1` 起停和查看健康状态。示例配置中的端口与手动启动的默认值分别维护，填写时保持一致。

## 5. 后端集成测试

在独立测试库完成结构迁移、演练种子、组件产能与工位映射配置后，将 `DB_URL` 指向该库，运行 `workspace/idea/ems/mvnw.cmd -B package`。部分测试通过数据库状态与权限反例检查库存守恒、任务前置条件和重复执行，不能只建空表就认定已经准备好测试环境。
