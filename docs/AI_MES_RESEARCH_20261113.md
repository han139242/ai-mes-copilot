# AI-MES Copilot 调研与优化记录

2026-11-13 · [AI 架构](AI_ARCHITECTURE.md) · [教师演示](TEACHER_DEMO.md)

这轮优先解决三个现场问题：工业编号被语义检索漏掉，设备故障和历史维修被当成同一类查询，演示前难以定位哪一段服务未就绪。基于仓库实现核对公开论文、标准、产品文档，再在独立数据上验证能落地的改动。

## 1. 工业资料如何检索并给出可核对的依据

BEIR 在 18 个数据集上的实验表明，BM25 仍是稳健的检索基线，重排或后交互模型平均表现更好但成本也更高。pgvector 官方给出全文与向量混合、使用 RRF 或交叉编码器融合的路径。项目已有中文向量检索，现阶段最直接的缺口是精确编码排在候选窗之外，因此先增加编号原文回查。中文全文检索需要另测分词、连字符和版本号处理。 [BEIR](https://arxiv.org/abs/2104.08663) · [pgvector 混合检索](https://github.com/pgvector/pgvector#hybrid-search) · [RRF 原论文](https://cormack.uwaterloo.ca/cormacksigir09-rrf.pdf)

RGB 把拒答、信息整合和干扰耐受分开评估。RAGChecker 将检索质量与生成质量分开诊断，ALCE 则专门评估引用能否支撑陈述。现有演示增加事实和指定出处同片段的检查，并分别记录前四条摘录和完整引用列表的覆盖情况。字符串同现可以自动回归，语义支持和生成陈述仍需人工核对。 [RGB](https://ojs.aaai.org/index.php/AAAI/article/view/29728) · [RAGChecker](https://arxiv.org/abs/2408.08067) · [ALCE](https://aclanthology.org/2023.emnlp-main.398/)

微软 GraphRAG 论文侧重对大语料库做全局主题归纳。项目的 LightRAG 主要用于跨文件查找产品、工序、设备和检验之间的关系，并把图谱片段校回原文。台灯样本只有 10 份资料，优先验证具体路径和出处。全局摘要、局部查证和代码查询应该分别设计题目。 [GraphRAG 论文](https://www.microsoft.com/en-us/research/publication/from-local-to-global-a-graph-rag-approach-to-query-focused-summarization/) · [LightRAG 论文](https://arxiv.org/abs/2410.05779)

本轮保留现有向量排序。如果引用未覆盖明确编号，服务在本人已发布索引中按完整编号找原文，最多回查四个缺失编号，随后预留代表出处。图谱与文档合并时同样保护多个编号的出处。多编号对比仍缺资料时展示已有原文和缺失编号，并暂停生成对比结论。查到编号说明原文提及该编号，具体事实仍以段落语义为准。

### 可复现结果

| 检查 | 修改前 | 修改后 |
|---|---:|---:|
| 五格式资料原有离线检查 | 24/24 | 24/24 |
| 指定出处与事实同片段，完整引用 | 6/7 | 6/7 |
| 指定出处与事实同片段，前四条摘录 | 2/7 | 2/7 |
| 云模型请求增量 | 0 | 0 |

两次使用同一合成资料包和真实本地嵌入，共 10 份文件、71 个片段。S18 的检验规范片段未进入引用。编号回查能补召回“40 条近邻都没有完整目标编号”的情形，真实 PostgreSQL 回归覆盖同名近似编号、已删除文件和跨用户隔离。上述小样本可用于判断回归，尚无大库性能或模型答案准确率结论。

在已配置本地运行环境的仓库根目录中执行以下命令，将路径替换为本机资料包和输出目录。

```powershell
. .\scripts\load-stage2-environment.ps1 -NoCloud
$env:PYTHONPATH = Join-Path (Get-Location) 'services/private-assistant'
$python = 'D:\AI-MES-Stage2\.venv\Scripts\python.exe'
& $python services/private-assistant/scripts/verify_showcase.py --bundle '<资料包目录>' --output '<结果目录>'
& $python services/private-assistant/scripts/score_showcase_evidence.py '<结果目录>\results.json'
```

## 2. 哪些制造场景能复用现有 MES 数据

Siemens 的 Production Copilot 和 Maintenance Copilot 把设备、工单与资料结合用于操作和维护。这个思路在项目里已有两条入口：知识服务返回文件原文，工作台工具读业务库。本轮让设备查询明确区分当前故障、开放报修、指定日期的在册设备报修记录。当前结果按工位关联未完成任务，并标记缺少工位的设备。历史结果按报修时间返回，不套用今天的任务。故障与任务通过工位关联，延期影响要结合排程和现场状态核对。 [Siemens Production Copilot](https://blogs.sw.siemens.com/insights-hub/2025/01/30/production-copilot-ai-assistant-for-production/) · [Siemens Maintenance Copilot](https://help.assetmanagement.siemens.com/help/Content/Documentation/Maintenance/Asset%20Essentials/EnterpriseFeatures/AI-Driven%20Capabilities/Maintenance%20Copilot.htm)

GS1 EPCIS TransformationEvent 用输入、输出对象表达生产转化；制造追溯研究也展示了在明确关系上执行图查询的做法，论文验证范围为特定样例和实验设置。项目据此用 `trace_material_usage.batch_id` 与 `product_lot_id` 建立直接投入和产出关系，保留用料记录 ID、数量、单位和报工号。仓储成品批可沿 `source_product_lot_id` 找到生产批，再查它的直接投入物料。查询受跨模块权限和数据范围约束，缺少记录或关联时在结果中说明。 [GS1 EPCIS](https://ref.gs1.org/epcis/TransformationEvent) · [Nguyen 等制造追溯研究](https://link.springer.com/article/10.1007/s00170-026-19467-7)

## 第二轮验证

| 相同的台灯资料和题集 | 第二轮前 | 第二轮后 |
|---|---:|---:|
| 原有离线检查 | 24/24 | 24/24 |
| 指定出处与事实同片段，完整引用 | 6/7 | 7/7 |
| 指定出处与事实同片段，前四条摘录 | 2/7 | 7/7 |
| 云模型请求增量 | 0 | 0 |

在有界向量候选池中，排序结合完整编号、问题关注的证据类型和来源覆盖度。数量问题优先考虑含数量字段的清单，跨文件问题优先保留能补齐不同方面的原文。规则用本项目的题集调试，表中数字只说明这套合成资料上的变化，尚无其他语料库的独立评估。

### 第二轮追溯复现

在带台灯演练记录的独立库中，`LAMP-REH-20261025-M3` 和 `C3-LP-20261030-001` 两边命中同一条 `10 片` 的记录，关联工单为 `C3-WO-20261030-002`，报工单为 `C3-WR-20261030-002`。结果是一跳用料关系，多级组件链、库存结余和销售去向需要另外查询。

本机演练库的独立副本为 `mes_lamp_stage2_20261113_trace`。在本机已有台灯演练库的情况下，可用 [prepare-stage2-mes.py](../scripts/prepare-stage2-mes.py) 复制为独立库，按[部署说明](SETUP.md)配置运行时数据库。演练副本需包含上述样例记录，且应应用当前仓库的兼容迁移。在该库上可运行 `AiQuerySmokeTest#lampRehearsalReportsAgreeWithTheDemoPrompts` 复核两句演示问题。单独的事务测试同时覆盖空链、已删除关系、断开的端点、仓储成品批回溯和数量单位。

## 3. 如何判断演示环境可以使用

启动、进程监听和业务可用是不同状态。Docker Compose 的 `start --wait` 可等待已有容器运行或健康，Spring Boot 文档也要求按实际依赖决定 readiness 范围。仓库新增 `scripts/test-stage2-demo.ps1`，检查两库监听、前端、后端鉴权、前端代理鉴权响应和私有助手的数据库健康；另列本地模型清单是否存在。最后以实际账号各做一次业务查询、原文查证和图谱打开。 [Docker Compose start](https://docs.docker.com/reference/cli/docker/compose/start/) · [Spring Boot 探针](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.kubernetes-probes.external-state)

## 后续优先级

| 优先级 | 候选能力 | 达到什么条件再接入 |
|---|---|---|
| 高 | 独立检索题集 | 新增未参与调参的中文问题，比较当前方案、BM25/向量融合及重排的前四条命中和延迟 |
| 高 | 多级组件批次追溯 | 用含多级组件的业务种子验证跨层路径、循环保护、缺失节点和各层用量口径 |
| 中 | 设备影响追问和证据链接 | 明确实时状态、任务、排程的时间差，逐个验证角色权限和设备、工位缺失时的表现 |
| 中 | 验证写操作重复提交 | 用同一操作标识测试超时、重复确认和并发重试，先审查已有预览令牌和事务去重的覆盖范围，再决定扩展方式。 [AWS 幂等重试实践](https://aws.amazon.com/builders-library/making-retries-safe-with-idempotent-APIs/) |

这轮比较按项目数据规模和演示需要选取资料。后续应先用同一数据和问题对照评估，再决定是否引入新的搜索、重排或图谱组件。
