# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

台灯制造场景中的操作工、质检员、仓管员、班组长和系统管理员。各岗位使用同一 MES，按账号权限查看业务和办理任务；私有资料及问答归本人所有。

## Product Purpose

AI-MES Copilot 以智能调光台灯 L100/L200 为教学业务场景，在已有制造执行系统中用自然语言查询、办理和追踪任务。第二轮增加私有知识、可追溯问答、知识图谱及本人认证。

## Operating Context

主要为桌面浏览器中的业务操作与课程演示，同时支持窄屏基本操作。现有 Vue 3 / Element Plus 前端、Spring Boot / MySQL 后端保留，私有知识服务与数据库独立部署。课程要求和当前完成状态分别见 docs/STAGE2_PLAN.md。

## Capabilities and Constraints

- 既有生产、库存、质量、设备、追溯、角色权限、AI 预览确认及快捷入口须保留。
- 第二轮要求：每用户独立私有数据库、多格式文件管理、真实 RAG/GraphRAG、图谱可视化、人脸管理/登录登出、个性化及真实交付证据。未通过运行验收的内容不能说成已完成。
- 知识问答提供依据与建议，业务写入通过原 MES 权限、预览、人工确认与回查。
- 模型密钥、个人身份、人脸和私人文件不进入公开仓库；云端处理范围须明确可控。

## Brand Commitments

沿用用户已确认的台灯业务名称、米白为主、天蓝点缀和现有导航。文案用简体中文，清楚、直接，优先帮助完成当前任务。

## Evidence on Hand

既有源码、workspace/vscode/ems-forword/src/styles/tokens.css、现有业务页面及用户提供的界面截图为产品与视觉证据。services/private-assistant/examples/lamp-demo 中的资料是合成教学样例，不能包装成正式生产标准或真实客户数据。

## Product Principles

1. 减少操作、等待、理解成本与误操作。
2. 状态、来源、权限和实际结果要能看懂、能核对。
3. 失败时保留可用能力，提供明确恢复路径。
4. 不把用户偏好、资料内容或页面上下文当成授权。

## Open Decisions

教师展示的确切日历日期、实际真人识别表现及未完成验收的模型质量不预填。本文整理自本轮已确认需求和已有项目事实，无新增产品定位或视觉选择。
