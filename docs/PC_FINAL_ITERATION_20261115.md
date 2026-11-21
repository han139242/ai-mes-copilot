# 台灯 MES 最终一轮优化

本轮从四条可上台演示的任务出发。选择一份资料核对型号，从答案转到业务草稿，按原料批找到生产记录，以及消息服务部分故障时继续工作。前一轮的[逐模块对照](PC_SYSTEM_ITERATION_20261115.md)覆盖所有 PC 模块。本轮用新验收重走页面和代表任务。

## 对标与取舍

| 任务 | 对照依据 | 实际采用 |
| --- | --- | --- |
| 核对一份版本化资料能证明什么 | Gemini Notebook 支持选择引用范围和跳回引文，Dify 提供单独的检索测试，Azure AI Search 区分候选前过滤和 Top-K 后过滤。 [Gemini Notebook](https://support.google.com/gemininotebook/answer/16179559?hl=en-MY) · [Dify](https://docs.dify.ai/en/cloud/use-dify/knowledge/test-retrieval) · [Azure](https://learn.microsoft.com/en-us/azure/search/vector-search-filters) | 文件行有“只问此资料”。后端从当前账号的已发布版本检索，向量候选和精确编号补查使用同一约束。答复显示本次范围，可打开原文。切回全部资料后恢复原检索方式。 |
| 核对检索质量和演示边界 | Ragas 将来源覆盖和生成效果分开，ID 可以作为可重复的核对依据。 [Ragas 来源召回](https://github.com/vibrantlabsai/ragas/blob/main/docs/concepts/metrics/available_metrics/context_recall.md) | 检查输入限定版本、响应范围和各条引用身份。用范围外的问题、相近编号、换版和不同账号做反例。现有图谱用于跨文件关系，单份问答采用文档路径。 |
| 从知识回到现场业务 | Rovo 的工具动作允许用户核对，Dynamics 365 车间界面以任务归属区分工作队列，Tulip 工序指导强调从实际工单和可核对步骤出发。 [Rovo](https://support.atlassian.com/rovo/docs/chat-to-a-rovo-agent/) · [Dynamics 365](https://learn.microsoft.com/en-us/dynamics365/supply-chain/production-control/production-floor-execution-use) · [Tulip](https://support.tulip.co/docs/work-instructions-apps-overview) | 从知识或工作单转交时只传给本次登录中的工作台草稿。地址只保留页面路径，回填可撤销。用户继续按现有权限、预览和确认流程办理。 |
| 从原料批追到对应单据 | ERPNext 提供双向批次追溯，Sepasoft 用生产事件建立追溯历史。Cloudscape 从列表展开选中对象的相关明细。 [ERPNext](https://docs.frappe.io/erpnext/serial-and-batch-traceability-report) · [Sepasoft](https://www.sepasoft.com/getting-started-series-track-and-trace/) · [Cloudscape](https://cloudscape.design/patterns/resource-management/view/split-view/) | 保留直接用料记录 ID、实际单位和批号。去向表格及窄屏卡片的工单号、报工单号可直接打开对应记录。仅对有页面权限的角色提供这些链接。 |
| 消息查看与页面切换 | Cloudscape 支持局部反馈，Linear 保留筛选与记录上下文。W3C 提醒验证随页面滚动的固定区域是否盖住键盘焦点。 [Cloudscape](https://cloudscape.design/patterns/resource-management/view/split-view/) · [Linear](https://linear.app/docs/custom-views) · [W3C](https://www.w3.org/WAI/WCAG22/Techniques/failures/F110) | 消息列表和未读计数分别读取，计数失败时保留列表、提示并独立重试。全列表筛选、权限和移动菜单做代表路径回归。 |

公开资料用于比较其做法。代码、真实界面与测试用于判断本项目是否达到了该任务目标。单资料选择以实际发布版本为准，图谱继续提供跨文件关系。若需要在图谱中应用文档范围，须先能逐边核对来源及有效版本，并有能够验证多级生产关系的数据。

## Codex 与 DSH

使用已配置的 WorkBuddy DeepSeek-V4.1-Flash 交换公开参考、合成案例及必要的源码片段。DSH 建议优先落实单份文档范围，过滤要早于候选截断，历史和图谱也不能悄悄改变范围。Codex 采纳这些建议，拒绝扩大到多层批次谱系。代码复核由 Codex 配合真实 PostgreSQL、浏览器反例和路由回归完成。

DSH 的方案讨论已返回。两次代码审查的命令行会话超时，本机桌面接续未能建立工具连接，未取得 DSH 的最终代码审查意见。

## 演示核验

| 项目 | 结果 |
| --- | --- |
| 单资料 | 阶段 2 主入口的质检账号 10 份资料可选。只查 `01-产品型号说明.md v1` 时，L100 的驱动板结果引用同一文件，能打开同版本上下文。独立合成例中，检验步骤在产品说明内没有足够证据，切回全部可查到检验文件 |
| 版本与边界 | 真实 PostgreSQL 测试包含 41 个干扰向量、精确编号补查、范围外编号、跨账号及更新前后的发布版本。前端测试覆盖拒绝范围不符或缺少范围回执的响应 |
| 图谱 | 主入口质检账号打开已有图谱，显示 37 个节点、80 条关系 |
| 交接与追溯 | 工单实时查询转到工作台只预填草稿，未触发业务查询请求。独立演练库的物料去向直接打开 `C3-WO-20261030-002` 和 `C3-WR-20261030-002`，各自聚焦对应记录 |
| 消息 | 独立演练库阻断未读数接口时仍能看到消息，恢复后从重试区域读回 20 条未读 |
| 构建与回归 | 前端 209 项通过，生产构建通过。Python 连接独立 PostgreSQL 的 119 项测试通过，80 条离线题通过。Java 私有网关和追溯专项合计 12 项通过，打包通过。应用内的模型生成新增请求为 0。阶段 2 六项连通预检通过 |

桌面检查 106 个 PC 入口（含登录），390 像素复查 28 个页面。画面及业务用例采用本机合成数据。批次示例由[独立追溯库](AI_MES_RESEARCH_20261113.md#第二轮追溯复现)提供。现场使用顺序见[教师讲稿](TEACHER_DEMO.md)，架构及数据边界见[AI 架构](AI_ARCHITECTURE.md)。
