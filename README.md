# AI-MES Copilot

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

用自然语言驱动制造执行：查数据、分析问题、办理任务、跟踪结果。

An AI-assisted manufacturing execution system built with Spring Boot, Vue 3 and MySQL. Use natural language to query business data and carry out supported tasks through preview, confirmation, execution and result verification.

[运行说明](docs/SETUP.md) · [AI 能力](docs/FEATURES.md) · [参与贡献](CONTRIBUTING.md) · [版本发布](https://github.com/han139242/ai-mes-copilot/releases)

[English README](README.en.md)

AI-MES Copilot 是一个以智能调光台灯 L100 / L200 为业务场景的 MES（制造执行系统）。基于 Spring Boot、Vue 3 和 MySQL，将采购、库存、生产、质量、设备与发货连接起来，让用户通过 AI 工作台用日常语言推进业务。

## 核心特色

- **自然语言贯穿业务主链**：从创建生产订单、排产和生成父子工单，到领料转线边、派工、开工、报工、成品入库及销售发货。
- **39 项业务能力**：20 项查询与分析能力、19 项直接办理能力；通用查询通过受权限控制的资源目录覆盖更多业务数据。
- **多轮对话与结构化结果**：理解上下文和追问，返回业务结论、表格、图表、来源及下一步建议。
- **预览、确认、执行、回查**：写操作生成确认卡，执行时复核权限、单据状态、前置条件和重复请求，保留执行记录。
- **自动化与主动预警**：规则扫描、事件触发、多步任务、通知和异常处理；任务执行状态可追踪。
- **面向真实业务的一致性约束**：多层 BOM、组件与整机父子工单、线边库存成对移转、逐件检验和八阶段订单进度。

![AI 工作台：自然语言低库存查询、业务表格与图表](docs/images/ai-workbench.png)

*项目演练截图，展示问答结果与来源结构；截图中的数量为当时的演示数据。*

### 生产大屏与统一界面

PC 界面采用米白底色与天蓝点缀，统一导航、AI 工作台、业务表格和生产大屏。左侧快捷办理按生产链组织，正式会话仅呈现服务端目录；开发预览保留不可执行的入口样式，并明确标记状态。

![米白主题生产大屏：产线排期、风险关注、质量与设备状态](docs/images/production-screen.png)

*界面验收截图，使用临时演示数据，不代表实际经营结果。运行与主题约定见[界面说明](docs/UI_DESIGN.md)。*

### 阶段 2：有依据的私有知识助手

新增每用户独立 PostgreSQL 数据库、五格式资料管理、带版本引用的向量 RAG、LightRAG / AGE 图谱和融合问答、岗位偏好，以及人脸录入/管理/原会话认证入口。知识依据可转到原业务助手草稿，业务执行继续使用原权限与预览确认。

人脸登录支持直接识别已录入账号，自动提示、采集和最终核验；歧义拒绝，保留密码入口。原模板保留，真实摄像头验收状态和参考见[采集与识别说明](docs/FACE_AUTO_CAPTURE.md)。

[实施与核心代码](docs/STAGE2_IMPLEMENTATION.md) · [实际验收和剩余事项](docs/STAGE2_ACCEPTANCE.md) · [开源参考](docs/STAGE2_REFERENCES.md) · [独立启动](services/private-assistant/README.md)

本轮实际截图反馈后的界面整理、教师逐项要求及待本人验收内容，见[阶段 2 复核与界面优化](docs/STAGE2_REVIEW_20261108.md)。

本轮整合了私有知识、自动人脸登录、精简交互和统一台灯标志。上传与重试直接唤醒索引，取消后未发布的版本停止上线；来源与图谱切换丢弃过期响应。[教师要求逐项对照与发布检查](docs/STAGE2_FINAL_RELEASE.md)是当前状态入口，早期验收记录按各自日期保留。

维护者已反馈免账号人脸可以登录；最新真人总耗时、第二位参与者和错误脸仍需现场验收。Dify未部署，属于可选加分方向。本地调用默认无次数上限，处理同意、调用记录和平台额度仍有效。维护验证没有新增收费请求，离线通过率不等于模型生成准确率。

人脸采集已改为[自动动作引导](docs/FACE_AUTO_CAPTURE.md)：连续合格后自动采集正面和随机左右转头，三步完成自动核验，支持关闭语音提示；已有模板继续可用。本轮未实现眨眼判定。

体验深化增加了**关联路径探索**：选择两个实体，查看当前图中六段以内的最短关联，逐步打开有效来源，再把线索带入提问草稿。手机输入区适应短窗口，工作台保留全部能力类型和快捷办理；新增图谱镜头、实际图元、快捷键与待办分页回归。参考来源、实际检查与采用边界见[本轮优化记录](docs/EXPERIENCE_REFINEMENT.md)。

## 可以怎样问

- “今天生产了多少？合格数和不良数分别是多少？”
- “哪些物料低于安全库存？缺口是多少？”
- “这张工单现在能开工吗？还缺什么条件？”
- “安排这张生产订单，然后按订单生成工单。”
- “生成这张工单的领料单，再转到线边仓。”
- “这张订单已经做到哪一步？接下来应该做什么？”

办理前需要当前账号有对应权限、对象存在且前置业务已完成；缺少单号、库位、数量等信息时，助手会继续询问。逐件检验的实测值由质检人员录入，AI 协助查询、衔接流程和提交已有结果。

## 业务范围

主数据与产品工艺、仓储库存、生产执行、质量管理、设备维护、班组排班、采购与销售、系统权限，以及 AI 问答、智能办理、预警自动化和可视化看板。

完整生产链为：采购来料 → 来料检验 → 组件生产 / 库存组件领用 → 领料转线边 → 整机生产 → 逐件检验 → 成品入库 → 出货检验 → 销售发货。

## 代码结构

```text
workspace/idea/ems/            Spring Boot 后端、领域服务、数据库结构与测试
workspace/vscode/ems-forword/  Vue 3 PC 前端、AI 工作台与业务页面
services/private-assistant/   阶段 2 私有知识、图谱、人脸服务与合成样例
workspace/vx/                 历史微信小程序代码
scripts/                      启动监管、迁移、演练与核验工具
docs/                         公开版运行说明与验证口径
```

本次交付重点是后端与 PC 端。历史微信小程序独立保留，其运行配置需另行维护。旧场景资料及脚本的边界见[历史资料说明](docs/LEGACY.md)。

## 快速开始

完整开发与验证环境使用 JDK 21、Node.js 24（本轮为 24.19.0）、MySQL 8.0；Python 3 用于部分迁移和核验工具。Redis 为可选组件。前端构建依赖支持较早的 Node 版本，完整会话测试的要求见[运行说明](docs/SETUP.md)。

```powershell
git clone https://github.com/han139242/ai-mes-copilot.git
Set-Location ai-mes-copilot
```

先按[运行说明](docs/SETUP.md)准备独立演示库和环境变量，再启动后端与前端。开发服务器默认使用后端 8088、前端 5173。

## 测试与构建

前端：

```powershell
Push-Location workspace/vscode/ems-forword
npm ci
npm test
npm run build
Pop-Location
```

运行监管与推演工具：

```powershell
node --test scripts/local-runtime.test.mjs scripts/ai-decision-preflight.test.mjs
```

后端使用仓库内 Maven Wrapper；先配置独立测试数据库和环境变量，再从 `workspace/idea/ems` 运行 `./mvnw.cmd -B package`。接库测试会写入测试数据，不与日常演示共用数据库。跳过的测试单独统计。

当前公开源码包含 2026-11-02 的 CodeArts 整改。对应源码在独立验证环境的后端完整回归记录为 **1118 项，0 失败、0 错误、0 跳过**；各轮检查范围分别记录在[验证说明](docs/VERIFICATION.md)。

2026-11-05 的 AI 使用便利性修订完成后，后端完整回归 **1134 项**、前端 **74 项**、运行监管与推演 **20 项**通过，后端打包与前端生产构建通过；本轮真实模型评估按授权共调用 **20 次**。具体场景与能力路由差异见[对话理解记录](docs/AI_USABILITY.md)。

## 项目文档

- [环境配置、数据库初始化与启动](docs/SETUP.md)
- [AI 能力、任务办理与业务边界](docs/FEATURES.md)
- [测试范围与验证记录](docs/VERIFICATION.md)
- [CodeArts 整改记录](docs/CODE_QUALITY.md)
- [对话理解与使用便利性改进](docs/AI_USABILITY.md)
- English: [Overview](README.en.md) · [Setup](docs/SETUP.en.md) · [AI capabilities](docs/FEATURES.en.md)

公开仓库保留源码、数据库结构迁移和示例数据，不包含个人报告、学号、答辩材料、共享数据库凭据、本机运行状态或私有历史记录。

## 贡献者

感谢共同完成项目的成员：

- [@han139242](https://github.com/han139242)
- [@AnHC-00](https://github.com/AnHC-00)
- [@zengjingye](https://github.com/zengjingye)

成员署名与后续贡献方式见[贡献者说明](CONTRIBUTORS.md)。

## 项目状态与后续维护

本项目已完成课程交付，并以开源形式保留。后续欢迎社区通过 Issue 反馈问题、通过 Pull Request 提交改进；参与方式见[贡献指南](CONTRIBUTING.md)，版本记录见[CHANGELOG](CHANGELOG.md)。

## 许可证

项目原创代码采用 [MIT License](LICENSE)，允许使用、修改和商用，需保留版权及许可声明。第三方依赖保留各自的许可证和版权信息。
