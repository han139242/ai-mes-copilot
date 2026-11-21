# 台灯 MES PC 界面调研与改版

## 选型

从本机当前路由表盘点出 86 个配置化业务列表和 20 个常规或回退页面。模块路径会重定向到当前账号可访问的业务页。业务操作、问答取证、监控和浏览器窄屏有不同的任务，采用各自适合的页面结构。历史微信小程序是独立客户端。

| 页面和主要任务 | 对照结果 | 采用的做法 |
| --- | --- | --- |
| AI 工作台和办理目录。现场人员重复打开少数表单 | SAP My Home 用收藏缩短路径。Oracle 制造工作区按作业组织入口和检索 | 首屏显示最多四个可办理事项。浏览器按账号保存置顶，默认从创建、排产、报工、检验中选择可用项。全部办理按业务环节筛选，还可搜索动作及说明。打开表单后沿用预览确认。 [SAP My Home](https://help.sap.com/docs/SAP_S4HANA_CLOUD/4fc8d03390c342da8a60f8ee387bca1a/f3fb03713b4748c69f7bc3ca708da97c.html?locale=en-US) · [Oracle 制造工作区](https://docs.oracle.com/en/cloud/saas/supply-chain-and-manufacturing/26d/faumf/how-you-review-a-dispatch-list.html) |
| 私有文档、问答、证据核查 | Gemini Notebook 把来源定位放在回答流程里。Cloudscape 的分栏保留主任务与局部详情 | 输入框和提问示例对齐。四种检索方式、公开资料和语音进入输入区。桌面打开原文时保留问答，窄屏集中显示原文。关闭预览后还原焦点。 [Gemini Notebook 引用](https://support.google.com/gemininotebook/answer/16179559?hl=en-MY) · [Cloudscape 分栏详情](https://cloudscape.design/patterns/resource-management/view/split-view/) |
| 知识图谱、研判工作单、偏好设置 | Neo4j Bloom 集中安排搜索、场景操作和关系检查。Atlassian 的空状态把下一步直接告诉用户 | 搜索、实体类型、数量和视图还原收在图谱控制区。工作单空态提示从资料答复创建。偏好设置采用原有独立表单，问答下方保留可点开的本地检索或 AI 回答状态。 [Neo4j Bloom](https://neo4j.com/docs/bloom-user-guide/current/bloom-visual-tour/bloom-scene-interactions/) · [Atlassian 空状态](https://atlassian.design/content/designing-messages/empty-state/) |
| 生产、仓储、质量、主数据、工艺、工厂、设备、追溯、组织与系统管理列表 | Fiori 过滤栏支持常用条件优先。Carbon 把导出、列设置和批量操作放在对应的表格范围。Cloudscape 在选择后再展开关联详情 | 共用模板将业务操作、查询、结果和表格设置分开。按内容宽度折叠筛选，键盘可直达被打开的关联明细。菜单管理采用现有的分页搜索接口，名称、权限码和路由均可查找。 [SAP Fiori Filter Bar](https://www.sap.com/design-system/fiori-design-web/v1-139/ui-elements/filter-bar) · [IBM Carbon 数据表](https://www.carbondesignsystem.com/building-blocks/core/components/data-table/guidelines) · [Cloudscape](https://cloudscape.design/patterns/resource-management/view/split-view/) |
| 首页、生产大屏、告警与自动化监控 | Grafana 按问题组织指标和下钻。Oracle 制造工作区把需要留意的状态和待做工序放到可点击入口 | 首页待办排在分析图表之前。预警、执行监控、异常、自动化与任务采用统一标题、数据时间和操作区域。大屏竖屏展示六个关键指标，宽屏仍可查看排期和完整图表。 [Grafana 看板](https://grafana.com/docs/grafana/latest/visualizations/dashboards/build-dashboards/best-practices/) · [Oracle 制造工作区](https://docs.oracle.com/en/cloud/saas/supply-chain-and-manufacturing/26b/faumf/overview-of-the-work-execution-work-area-classic.html) |
| 排产、制造准备、BOM、资源冲突与流程对比 | DHTMLX Gantt 将时间刻度和缩放放在排程附近。Fiori 用稳定页头表达标题、状态和操作 | 分析页标题和状态采用同一组件。排产保留原有图例和滑块，新增恢复 100% 入口。BOM 和制造准备把产品、日期等查询条件留在结果之前。 [DHTMLX Gantt](https://docs.dhtmlx.com/gantt/guides/zooming/) · [SAP Fiori 页面布局](https://learning.sap.com/courses/ui-development-with-sap-fiori/working-with-sap-fiori-design-guidelines_ab11c169-54de-4f51-87b9-f61c8a5198be) |
| 登录、账号安全、消息和错误页 | GOV.UK 的密码模式强调清楚的标签和可见反馈。Atlassian 区分空状态和失败提示 | 登录使用表单提交，保留密码可见性及刷脸说明。消息失败显示重试，通知打开和确认收到是独立控件。403、404 和服务不可用使用一致的恢复操作。窄屏顶栏提供模块搜索，键盘可跳到内容。 [GOV.UK 密码输入](https://design-system.service.gov.uk/components/password-input/) · [Atlassian 空状态](https://atlassian.design/content/designing-messages/empty-state/) |

工作台首屏和知识页用已有米白、浅蓝和图标，沿用 Vue 与 Element Plus。首屏默认四项是演示及业务流程起点，用户可以置顶自己常用的事项。

## 核验口径

- 前端 201 项测试通过，生产构建通过。阶段 2 的六项本机连通预检通过。
- Ego Lite 打开 106 个 PC 页面，含 86 个业务列表，并复查 28 个代表页面的窄屏布局。菜单管理实测 504 条记录按每页 20 条读取，按权限码筛选返回单条。
- 管理员显示 21 个办理入口，质检员显示生产检验、领用复验和出货检验。实测创建订单表单、搜索和分类、置顶后刷新、生产工单关联明细、排产缩放还原和登录回车提交。
- 在本地检索模式查到 L100 的驱动板型号 DRV-07，从答复打开引用原文。复看已建图谱、工作单和偏好入口。业务办理验收到表单，图谱使用已建数据。
- 桌面和窄屏预览来自本机演示页面的浏览器渲染。
