# GitHub Copilot Instructions

本仓库是 AI-MES Copilot，以智能调光台灯 L100/L200 为业务场景。后端使用 Spring Boot 与 MySQL，PC 前端使用 Vue 3；AI 工作台提供自然语言查询、预览确认、业务办理和结果回查。课程交付已完成，后续欢迎社区维护。`workspace/vx` 是历史微信小程序代码，仅在明确涉及该模块的任务中维护。

## Before Coding

- Read `README.md`, `COLLABORATION.md`, `CONTRIBUTING.md`, and the relevant guide in `docs/`.
- Locate current documents with `rg --files -g "*.md"` because documents may be moved during collaboration.
- Prefer existing code, `schema.sql`, and the ordered database migrations over guesses; the setup order is documented in `docs/SETUP.md`.
- Keep changes scoped to one module or one feature.

## Backend

- Backend root: `workspace/idea/ems`.
- Use `/api` prefix for formal APIs.
- Use `ResultData` for API responses.
- Keep layers: `controller`, `service`, `service.impl`, `mapper`, `entity`.
- Use MyBatis-Plus for simple CRUD.
- Filter `deleted_flag = 0` by default.
- Use soft delete for delete APIs.
- Keep `schema.sql`, entity fields, mapper SQL, and API fields aligned.

## Frontend

- PC frontend root: `workspace/vscode/ems-forword`.
- Keep pages organized by business module.
- Verify route imports and component imports.
- Match frontend requests to backend API contracts.
- Run `npm run build` after meaningful frontend changes.

## Database and MES Rules

- MySQL is the database baseline.
- Do not physically delete production, inventory, quality, or traceability data.
- Inventory changes must preserve balance and movement records.
- Status values should use stable English codes.
- Run database-writing checks only against your own isolated test database.
- Keep model understanding separate from business validation: writes still require permissions, valid states, and the documented confirmation flow. AI must not invent physical inspection measurements.
- Keep real credentials in environment variables. Public contributor credits use GitHub usernames; do not publish personal coursework or identities.

## Project Skills

For complex tasks, read the matching project skill under `.agents/skills/<skill-name>/SKILL.md` before suggesting or editing code.

Common skills: `mes-backend-resource`, `mes-backend-business-flow`, `mes-api-contract-sync`, `mes-mysql-schema-check`, `mes-pc-frontend-page`, `mes-wechat-miniprogram`, `mes-frontend-api-integration`, `mes-debug-fix`, `mes-test-verification`, `mes-code-review`, `mes-progress-update`.

## Output

When finishing a task, summarize changed files, validation commands, validation results, and remaining risks.
