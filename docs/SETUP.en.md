# Environment and startup

[简体中文](SETUP.md) · English · [Project overview](../README.en.md)

This guide covers the base MES. For RAG, GraphRAG, review worksheets and face authentication, continue with the [private-service setup](../services/private-assistant/README.md). The [classroom walkthrough](TEACHER_DEMO.md) explains the demonstration flow (Chinese).

Commands below start from the repository root and use Windows PowerShell. The complete development and verification environment uses JDK 21, Node.js 24 (verified with 24.19.0), MySQL 8.0, and Python 3. Make `java`, `mysql`, `node`, `npm`, and `python` available in your terminal. Redis is optional.

Frontend build dependencies declare Node.js compatibility as `^20.19.0 || >=22.12.0`. Conversation tests also use `node:module.registerHooks`, introduced in Node.js 22.15.0 / 23.5.0; see the [official reference](https://nodejs.org/api/module.html#moduleregisterhooksoptions). Node.js 24 reproduces the complete frontend test run recorded here.

## 1. Create an isolated demo database

Create a new database with the `mes_lamp_` prefix, such as `mes_lamp_demo`. Apply `workspace/idea/ems/src/main/resources/db/schema.sql` first, followed by the migrations below in this exact order. The source of this order is `CLEAN_BASELINE_MIGRATIONS` in `scripts/lamp_migrations.py`.

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

All SQL files are under `workspace/idea/ems/src/main/resources/db/`. The `lamp_report_alignment_v18.sql` migration aligns product, process, and inspection master data. Apply it while master data is still in draft and before any production or inventory transactions exist; it does not require personal report files.

If you need rehearsal accounts and sample runtime data, explicitly apply `lamp_demo_seed_v1.sql` afterwards. Component capacity and workstation mappings are supplied by `lamp_single_line_v1.sql` and `lamp_station_operation_v1.sql`. Before applying scenario-dependent seeds, maintain the process routes, lines, workstations, and shifts required by your own demonstration. Use receipt, issue, and transfer documents to maintain inventory.

## 2. Configure the backend

Set your own connection parameters in the same terminal that will start the backend. Replace the angle-bracket placeholders; the URL below is for a local demonstration database.

```powershell
$env:DB_URL = "jdbc:mysql://127.0.0.1:3306/mes_lamp_demo?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
$env:DB_USERNAME = "<your-database-user>"
$env:DB_PASSWORD = "<your-database-password>"
$env:JWT_SECRET = "<your-own-random-secret-at-least-32-bytes>"
$env:SERVER_PORT = "8088"
$env:FILE_UPLOAD_ROOT = "<your-upload-directory>"
$env:FILE_UPLOAD_TEMP_ROOT = "<your-temporary-upload-directory>"
$env:AI_ENABLED = "false"
```

Create your upload directories before using file uploads. With AI disabled, ordinary business modules can run without a model-service key.

```powershell
Push-Location workspace/idea/ems
.\mvnw.cmd -B spring-boot:run
Pop-Location
```

On Linux/macOS, use `./mvnw -B spring-boot:run` from the backend directory and set the equivalent shell environment variables. Configuration definitions are in `workspace/idea/ems/src/main/resources/application.properties`.

## 3. Start the PC frontend

Open another terminal at the repository root:

```powershell
Push-Location workspace/vscode/ems-forword
npm ci
$env:VITE_API_PROXY_TARGET = "http://127.0.0.1:8088"
npm run dev
Pop-Location
```

Open `http://127.0.0.1:5173`. The proxy target must match the backend host and `SERVER_PORT`. Sign in with an account provisioned for your demo database; example-account definitions are in `lamp_demo_seed_v1.sql`. The PC interface uses Chinese.

## 4. Enable the AI workbench

In the backend terminal, configure your own model service before restarting the backend:

```powershell
$env:AI_ENABLED = "true"
$env:AI_API_KEY = "<your-model-api-key>"
$env:AI_API_URL = "<your-model-service-base-url>"
$env:AI_MODEL = "<your-model-name>"
```

The backend uses an OpenAI-compatible model-service request format. Use the base URL and model name required by your provider. Model-service requests use your configured account and its billing arrangement.

Keep `AI_API_KEY` and database credentials on the backend. Frontend variables prefixed with `VITE_` are browser-facing configuration and must not contain model-service keys. See [AI capabilities](FEATURES.en.md) for the catalog, confirmation steps, and business prerequisites.

## 5. Runtime supervision tools

If you already maintain a database service and an environment script, copy `scripts/local-runtime.example.json` to the ignored path `tmp/local-runtime.json`, then fill in your own paths, ports, and database name. Use `scripts/manage-local.ps1` to start, stop, and inspect the local runtime.

The supervisor example and manual-start defaults have separate port settings. Align them with the backend and frontend configuration you actually use.

## 6. Production frontend build

From `workspace/vscode/ems-forword`, run `npm ci` and `npm run build`. Build output is written to `dist`.

Serve the static output through your own web server and forward `/api` requests to the backend, or set `VITE_API_BASE_URL` when building for a different API origin. Configure your deployment's origins and network routing for that environment.

## Backend integration tests

Prepare a separate test database using the schema migrations, demo seeds, component capacity, and workstation mappings required by the test scenarios. Point `DB_URL` at that database and run `workspace/idea/ems/mvnw.cmd -B package`; on Linux/macOS use `./mvnw -B package` from the backend directory.

Tests inspect actual database state, inventory conservation, invalid permissions, prerequisites, and duplicate execution. Empty tables alone do not prepare the integration-test environment. Keep this database separate from normal demonstrations and business data. Execution records and their scopes are documented in [VERIFICATION.md](VERIFICATION.md), currently in Chinese.

## Common setup problems

- **The backend cannot connect to MySQL:** check the database service, host, port, database name, user privileges, and the environment variables in the terminal that launches Maven.
- **A table or column is missing:** compare the applied migration sequence with the list above. Read the failing migration and fix the initialization of your isolated database before restarting background tasks.
- **The frontend opens but requests fail:** verify that the backend is running and that `VITE_API_PROXY_TARGET` matches its port. Restart the frontend after changing its environment.
- **AI is unavailable:** ordinary modules can run with `AI_ENABLED=false`. For AI use, check the backend's enabled flag, model endpoint, model name, key, and provider response; redact logs before sharing them.
- **An AI action is blocked:** check the current account's permissions, document state, available inventory, and required inspections. Missing business prerequisites must be resolved through the corresponding workflows.

For further help, open a [reproducible issue](https://github.com/han139242/ai-mes-copilot/issues/new/choose) with your version, environment, steps, and redacted evidence. Contribution instructions are available in [English](../CONTRIBUTING.md#english).
