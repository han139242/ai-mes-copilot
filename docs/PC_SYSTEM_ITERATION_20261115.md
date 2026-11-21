# 台灯 MES PC 端对标与迭代

本轮从四类现场任务出发。操作员要找到办理入口，计划员要持续查看同一组工单，质检员要从异常查回批次和证据，管理人员要知道看板数据是否可用。以 [11 月 14 日改版](PC_INTERFACE_REVIEW_20261114.md) 为基线，先对照成熟产品的实际交互和官方说明，再用本机台灯演示复现并记录改动前后的结果。覆盖 PC 路由，历史微信小程序沿用独立验收。

## 四轮落地

1. **查找与办理**。在工单页搜索「领料转线边」，原先找不到动作。现在在顶栏按 `Ctrl+Shift+K` 可一起查页面和授权办理事项，按 Enter 打开原表单。质检账号候选列表只有领用复验、生产检验和出货检验，搜索「领料转线边」没有结果。
2. **重复查询与制造数据**。在生产工单原有 1,632 条结果里填入未提交的关键词。原先翻到第 2 页会将结果误筛到 0 条。新版换页仍使用已生效的条件，保留输入框草稿。已生效的条件可按本机账号保存，刷新后可重用。列表中的物料、工单、设备和组织关联从对应记录展开，尽量少丢失当前行和筛选。
3. **查回业务依据和 AI 原文**。物料批次查去向，生产批次查投入；终检按本批次匹配。知识问答引用高亮实际片段，展开时读取同一个版本的上下文，关闭后回到原引用按钮。
4. **局部失败和操作边界**。单独中断首页质量统计接口，趋势和设备继续显示，质量区域标明上次结果并可重试。排产打开或刷新时只读，需要排入时再由操作者进入手动办理。按受限账号和窄屏重新走核心入口。

## 按模块选型

| 系统与任务 | 参考机制 | 本项目的落地与取舍 |
| --- | --- | --- |
| **AI 工作台与顶栏** · 从任意业务页找高频办理 | SAP 的全局搜索提供统一入口。Jira 的命令面板可键盘跳转和执行常用动作。Linear 用品类区分搜索结果。 [SAP Fiori 全局搜索](https://help.sap.com/docs/SAP_S4HANA_CLOUD/4fc8d03390c342da8a60f8ee387bca1a/03000c73b2474a318bb2e9882d62676a.html) · [Jira 命令面板](https://support.atlassian.com/jira-software-cloud/docs/what-is-the-command-palette/) · [Linear 搜索](https://linear.app/docs/search) | 首屏保留可置顶的高频事项，全部目录按生产环节分组。顶栏区分页面与办理，复用后端能力目录和页面守卫。选中办理后打开表单，提交仍按领域权限和原有确认流程。 |
| **文档与 RAG** · 核实型号、版本和适用范围 | Gemini Notebook 让引用跳到原文。Azure AI Search 提供可解析的依据引用。 [Gemini Notebook](https://support.google.com/gemininotebook/answer/16179559?hl=en-MY) · [Azure 引用结构](https://learn.microsoft.com/en-us/azure/search/agentic-retrieval-how-to-retrieve) | 高亮定位到同版本正文中的实际片段。需要排除条件或限定范围时展开上下文，保留引用高亮，匹配不唯一时不猜测位置。文档排序和模型配置沿用已验收能力。 |
| **知识图谱、研判工作单与偏好** · 查关联并交接下一步 | Neo4j Bloom 支持按实体检查关系。Purview 将从节点查上游与查下游放在同一视图。Cloudscape 用分栏保持原任务可见。 [Bloom 场景交互](https://neo4j.com/docs/bloom-user-guide/current/bloom-visual-tour/bloom-scene-interactions/) · [Purview 血缘](https://learn.microsoft.com/en-us/purview/data-governance-classic-lineage-user-guide) · [Cloudscape 分栏](https://cloudscape.design/patterns/resource-management/view/split-view/) | 图谱保留搜索、关系筛选、还原和依据入口。沿用从答复到研判工作单的交接，检查关联视图和偏好入口。图上的关系用于定位证据，具体值及适用条件回到原文。 |
| **首页、大屏、告警、AI 监控** · 看异常和下一步 | Grafana 建议按用户要回答的问题组织看板，标签和注释可补告警语境。Power Platform 强调明确反馈和恢复。 [Grafana 看板](https://grafana.com/docs/grafana/latest/visualizations/dashboards/build-dashboards/best-practices/) · [Grafana 告警标注](https://grafana.com/docs/grafana/latest/alerting/fundamentals/alert-rules/annotation-label/) · [Power Platform 体验原则](https://learn.microsoft.com/en-us/power-platform/well-architected/experience-optimization/principles) | 首页分区请求，单项失败保留其余区块，旧结果带提示，重试不清空所有数据。没有已判定检验时显示说明。待补货和故障设备指向相应状态的业务列表。大屏、预警、异常、执行监控、自动化和任务按各自状态核对布局。 |
| **生产制造与排产** · 找工单、看任务、进入排入 | Dynamics 车间执行把工序与本工序操作放在一起，生产甘特图支持按资源和时间排程。Oracle 派工按工序查看状态和下一动作。 [Dynamics 车间执行](https://learn.microsoft.com/en-us/dynamics365/supply-chain/production-control/production-floor-execution-use) · [Dynamics 排产](https://learn.microsoft.com/en-us/dynamics365/supply-chain/production-control/visual-scheduling-production) · [Oracle 派工单](https://docs.oracle.com/en/cloud/saas/supply-chain-and-manufacturing/25d/faumf/how-you-review-a-dispatch-list.html) | 跨页筛选使用同一组已提交条件，工单相关项目在行内查看。排产查看不自动开手动草案，用户选择将单据加入产线时再进入流程。 |
| **通用业务列表** · 保存经常查询的组合 | Power Apps 将条件与保存的视图关联；Odoo 将常用过滤存为收藏；Carbon 将表格级操作放在结果范围内。 [Power Apps 视图](https://learn.microsoft.com/en-us/power-apps/user/grid-filters-advanced) · [Odoo 查询收藏](https://www.odoo.com/documentation/19.0/applications/essentials/search.html) · [IBM Carbon 表格](https://www.carbondesignsystem.com/building-blocks/core/components/data-table/guidelines) | 模板应用到 86 个配置化列表。个人筛选视图保存已应用条件，复用和删除均为显式选择。局部排序标明当前页；从关联记录跳入时，额外定位的行单独说明。 |
| **仓储与库存** · 从物料批次和待补货单继续处理 | Dynamics 库存列表允许按库存维度筛选。ERPNext 从批次查看相应事务。 [Dynamics 现有库存](https://learn.microsoft.com/en-us/dynamics365/supply-chain/inventory/inventory-on-hand-list) · [ERPNext 批次追溯](https://docs.frappe.io/erpnext/serial-and-batch-traceability-report) | 首页链接到待处理补货，物料批次行直接追查去向，保留生产批、工单、报工单和用量。名称取不到时以 ID 标识，关联页面的打开权限独立检查。 |
| **质量管理** · 打开逐件检验或已有处置 | Odoo 把检验和告警接到对应业务对象。Oracle 质量工作区区分检验执行与质量处置。 [Odoo 质量检验](https://www.odoo.com/documentation/19.0/applications/inventory_and_mrp/quality/quality_management/quality_checks.html) · [Oracle 质量管理](https://docs.oracle.com/en/cloud/saas/supply-chain-and-manufacturing/26b/fauqm/using-quality-management.pdf) | 待检行优先显示逐件检验，有已有处置单时显示直达入口。追溯摘要按生产批的有效终检显示，未完成时显示检验状态，已完成才显示结论。 |
| **条码与批次追溯** · 对上同一用料记录 | ERPNext 追溯支持双向关联。Oracle Redwood 产品谱系按起始对象查看关系。 [ERPNext 批次追溯](https://docs.frappe.io/erpnext/serial-and-batch-traceability-report) · [Oracle Redwood 产品谱系](https://docs.oracle.com/en/cloud/saas/readiness/scm/26a/mfg26a/26A-mfg-wn-f42315.htm) | 原料到生产批、生产批到原料对同一层的用料事实，保留记录 ID 和实际单位。生产批侧可回到本批终检。桌面用表格，窄屏逐条展示批次号、用量和关联。 |
| **主数据与制造准备** · 看产品对应的需求和 BOM 版本 | Odoo 的 BOM 以产品和组件为中心；Dynamics 生产计划关联生产需求与物料。 [Odoo BOM](https://www.odoo.com/documentation/19.0/applications/inventory_and_mrp/manufacturing/basic_setup/bill_configuration.html) · [Dynamics 生产计划](https://learn.microsoft.com/en-us/dynamics365/supply-chain/master-planning/planning-optimization/production-planning) | 产品直达制造准备并选中该产品。BOM 行打开需求分析时带当前 BOM ID，保留版本，避免重新从相似产品中猜选。 |
| **工艺管理与工厂建模** · 从车间查到工位能做什么 | Odoo 工序依赖和工作中心将工序、顺序和作业资源关联。Oracle 工位管理以工作中心、资源与作业为入口。 [Odoo 工序依赖](https://www.odoo.com/documentation/19.0/applications/inventory_and_mrp/manufacturing/advanced_configuration/work_order_dependencies.html) · [Oracle 工位管理](https://docs.oracle.com/en/cloud/saas/supply-chain-and-manufacturing/26d/faumf/how-you-manage-workstations.html) | 在车间展开产线、在产线展开工位、在工位展开可执行工序。工艺参数按当前工序读取。共享关联面板保留原行，空结果如实显示。 |
| **设备运维** · 由故障设备找维修信息 | Dynamics 资产视图从资产看相关记录；Odoo 按设备和时间整理维护请求。 [Dynamics 资产视图](https://learn.microsoft.com/en-us/dynamics365/supply-chain/asset-management/objects/object-view) · [Odoo 维保日历](https://www.odoo.com/documentation/19.0/applications/inventory_and_mrp/maintenance/maintenance_calendar.html) | 首页故障卡定位状态；设备行展开该设备的维修记录，另可跳转到授权的维修列表。实测包含没有维修记录的设备。 |
| **组织人事与系统管理** · 找班组成员和准确的权限 | Dynamics 区分组织、岗位和角色授权。Odoo 权限按用户和分组表达。 [Dynamics 组织岗位](https://learn.microsoft.com/en-us/dynamics365/human-resources/hr-personnel-departments-jobs-positions) · [Dynamics 角色权限](https://learn.microsoft.com/en-us/dynamics365/fin-ops-core/dev-itpro/sysadmin/role-based-security) · [Odoo 权限](https://www.odoo.com/documentation/19.0/applications/general/users/access_rights.html) | 班组成员在当前班组展开。角色编辑只在需要时加载完整菜单树，可搜索权限码，能找到列表首批以外的权限。审批、授权和后端数据范围保持各自约束。 |
| **消息、账号、错误与窄屏** · 从消息回到业务并恢复操作 | GitHub 通知收件箱区分打开、完成和稍后查阅；W3C 规定弹窗应管理初始焦点、键盘关闭和返回焦点。 [GitHub 通知](https://docs.github.com/en/subscriptions-and-notifications/how-tos/viewing-and-triaging-notifications/managing-notifications-from-your-inbox) · [W3C 模态对话框](https://www.w3.org/WAI/ARIA/apg/patterns/dialog-modal/) | 通知打开与确认收到独立，关联路由按权限开放。检查回车登录、键盘搜索、原文关闭后焦点回归和常见错误页恢复。窄屏保留关键数据和下一步操作。 |

## 实测与复现

| 检查 | 本轮覆盖 |
| --- | --- |
| 可达性与布局 | 本机浏览器打开 106 个 PC 页面，包含登录、错误回退、专用页面和 86 个配置化列表。28 个代表页面在 390 像素宽度复查。路由巡检覆盖页面可达和布局，业务流程以以下用例为准 |
| 业务操作 | 管理员从顶栏打开领料转线边表单。质检员只见授权办理事项，待检单可直接打开逐件检验。工单翻页、筛选保存和刷新后应用通过。排产打开或刷新没有发起手动草案请求 |
| 关联与追溯 | L100 制造准备读到 6 项待处理需求。车间、产线、工位分别读到 4 条产线、8 个工位、1 条可执行工序。台灯演练库中 `LAMP-REH-20261025-M3` 与生产批 `C3-LP-20261030-001` 双向命中记录 #1、10 片、工单 `C3-WO-20261030-002`、报工单 `C3-WR-20261030-002` 和本批终检 `C3-QI-20261030-001`。另用带一条合格明细的待检单确认摘要显示「待检」 |
| AI 证据 | 本地检索命中 L100 的 DRV-07 原文，引用高亮、同版本上下文和键盘焦点通过。质检账号复查 10 份合成资料、37 节点和 80 条关系。云工具实测沿用 [11 月 14 日验证记录](VERIFICATION.md#2026-11-14-deepseek-真实接口演示) |
| 局部失败与恢复 | 仅阻断首页质量统计时，生产趋势和设备状态继续显示。质量区域标明上次结果，恢复网络后重试成功 |
| 构建 | 前端 205 项测试通过，生产构建通过。Java 追溯专项 7 项测试通过，打包通过。本机阶段 2 六项连通预检通过 |

验证命令在仓库的对应目录运行。

```powershell
# 仓库根目录
pwsh -NoProfile -File .\scripts\test-stage2-demo.ps1 -Json

# workspace/vscode/ems-forword
npm.cmd test
npm.cmd run build

# workspace/idea/ems
.\mvnw.cmd -B '-Dtest=TraceStatusServiceTest' package
```

主入口用于工作台和知识演示。批次双向使用独立演练库，搭建见[数据复现](AI_MES_RESEARCH_20261113.md#第二轮追溯复现)，讲解和试题见[教师演示](TEACHER_DEMO.md)。这批用例确认一跳投入与产出关系。多级影响范围要有组件链样例才能验收。列表目前按当前页排序，全数据排序要有后台查询契约。现场要求云生成时需检查配置和授权。
