# AI-MES 项目对标与协作验收

这轮从质检员查资料、打开办理表单和查看图谱入手。保留现有 Vue、Spring Boot、FastAPI 和本地检索，优先修复会查错对象、误读状态或拖慢操作的问题。

## 调研与取舍

| 任务 | 比较过的机制 | 采用与验证 |
| --- | --- | --- |
| 同一份产品资料里追问一个型号 | LangChain 按需改写会话问题，LlamaIndex 先把承接问题改写成独立问题，Azure 给出使用近轮对话澄清指代的做法。QReCC 将改写、检索与回答分开；TREC CAsT 纳入澄清。 [LangChain](https://reference.langchain.com/python/langchain-classic/chains/history_aware_retriever/create_history_aware_retriever) · [LlamaIndex](https://developers.llamaindex.ai/python/framework-api-reference/chat_engines/condense_question/) · [Azure](https://learn.microsoft.com/en-us/azure/architecture/ai-ml/guide/rag/rag-prompt-engineering) · [QReCC](https://aclanthology.org/2021.naacl-main.44/) · [TREC CAsT](https://pages.nist.gov/trec-browser/trec28/cast/overview/) | 当前可识别精确编号，采用同会话、同文件版本且有引用支持的单编号接续。改前 L100、L200 的两个码一并返回；改后显示“接续编号 L100”，首条引用定位至 v1 第 9 行的 `SKY-731`。复查跨资料、跨会话、版本更新、多个编号和内容审核。 |
| 搜索并打开有权限的办理表单 | GitHub 命令面板支持高亮结果后用 Enter 执行。WAI 组合框模式接受已选建议并保留浏览器编辑按键。 [GitHub](https://docs.github.com/en/get-started/accessibility/github-command-palette) · [WAI](https://www.w3.org/WAI/ARIA/apg/patterns/combobox/) | 唯一结果可直接打开。改前搜索“出货检验”后焦点停在搜索框，Enter 无反应。改后打开“填写出货检验”表单，可以继续生成预览。多结果保留目录，中文输入法组合事件、重复按键和禁用状态由自动化检查。 |
| 确认图谱实际就绪 | WAI 的状态信息原则要求状态变化易于感知。图谱容器已维护读取状态。 [WAI 状态消息](https://www.w3.org/WAI/WCAG21/Understanding/status-messages.html) | 图谱请求期间暂隐数量。浏览器从读取态过渡到 **37 节点、80 关系**；原有检索与展开视图可继续使用。 |
| 在缺少原生 UUID 方法时打开知识页 | W3C 的 Web Crypto IDL 为 `randomUUID` 标注安全上下文；`getRandomValues` 可用于不提供该方法的环境。安全上下文规范单列了回环地址。 [Web Crypto](https://www.w3.org/TR/webcrypto-2/#crypto-interface) · [Secure Contexts](https://www.w3.org/TR/secure-contexts/#is-origin-trustworthy) | 优先使用系统方法，缺失时用 Web Crypto 随机字节生成同格式的 UUID。Ego 在环回地址预加载时模拟方法缺失，旧调用报 TypeError，新方案能打开知识页、提问和继续会话。 |

## DSH 和 Codex 各自查了什么

- 第一轮 DSH 用给定合成例子讨论方案。第二轮实际读取 Skill 入口，在同一份标准下做了旧产线只调研、教室新项目和 MES 现有流程三题盲测。旧产线题促成了“授权仅调研时，沿用只调研范围”的修订。Azure 断网容器的费用条件与 PaddleOCR 的本地效果待验分别保留。 [Azure 断网容器](https://learn.microsoft.com/en-us/azure/ai-services/document-intelligence/containers/disconnected) · [PaddleOCR 本地模型](https://www.paddleocr.ai/latest/en/FAQ.html)
- 独立审查阶段，DSH 实际读取三个原样代码片段和一份 Skill，自己提出三项查证。Codex 搜索 W3C 和 MDN，按 DSH 给的步骤实测两条系统路径，并把有出处的结果回到原会话复查。该阶段命令行首轮超时，实测按已成功记录的工具请求继续。
- DSH 指出的全库旧话题已复现。先问全库 `MES-100`，再限定资料问 `MES-200`，切回全库问“它呢”，旧查询确实带入了 `MES-100`。修复后两次纯代词都要求补编号，没有检索和引用。明确输入 `MES-200` 后，后续全库追问正常继续。
- 浏览器切换账号后重新请求可办能力。管理员 21 项，质检员只显示 3 项。管理员置顶的创建订单不会出现在质检员首屏，搜索结果依然为零且按 Enter 无表单。核验后恢复了临时置顶。
- DSH 复查指出内容审核失败可能进入后续历史。真实 PostgreSQL 配合模拟审核响应复现：保存的只有通用状态，但旧接续逻辑仍使用被拒问题。改后该轮会切断话题延续，测试核对了历史、引用和模型调用次数。

## 本机验收

| 检查 | 输出 |
| --- | --- |
| PC 自动化和生产构建 | `npm.cmd test` 212 项全通过；`npm.cmd run build` 通过，仍有第三方打包注释和现有包体积提示。 |
| 私有服务 | 独立 PostgreSQL 下 `python -m pytest -q` 122 项全通过；离线检索、出处、路由和生命周期评估 80/80。 |
| 实际页面 | 同范围追问、切换范围后补写、原文第 9 行、390px 流程、图谱加载、快捷表单、角色置顶隔离通过。业务办理实测到表单与生成预览入口。 |
| 演示环境与 Skill | 主入口六项连通预检通过。两套 Skill 的结构检查、引用目标和交接章节通过。 |

这轮模型内容审核使用模拟响应，离线评估的本机云模型请求数为 0。真实演示使用现有图谱和合成资料。教师演示可按 [讲稿](TEACHER_DEMO.md) 操作，单资料接续契约见 [AI 架构](AI_ARCHITECTURE.md#问答链路)。

## DSH 原生独立取证复验

协作启动器此前在运行配置中关闭 `tool-web`，限定文件工具的守卫也会拦住原生终端。这次使用 `--independent --workspace` 让 DSH 在项目工作树自行调用网页、文件和终端工具。搜索复用已装的 `dsh-free-search` 免费引擎，抓取使用 DSH 的公开 HTTP 提供器。原始工具日志和命令输出保存在本机任务记录。 [DSH 网页工具](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/web/tool-web/README.md) · [免费搜索提供器](https://github.com/DDDMUC/dsh-free-search)

| 自主取证步骤 | 实际结果 |
| --- | --- |
| DSH 自己搜索和阅读原文 | 搜索 LightRAG 的后续更新和引用位置的接口，在搜索结果中找相关来源。直接读到 LightRAG v3 正文和 Claude Citations 官方文档。LightRAG §3.2–3.3 说明特定实体与关系检索，也说明回答会结合原文摘录。Claude 提供文本偏移、页范围或内容块范围，供对照精确引用。 [LightRAG §3.2–3.3](https://arxiv.org/html/2410.05779v3#S3.SS2) · [Claude Citations](https://platform.claude.com/docs/en/build-with-claude/citations) |
| DSH 自己读代码并运行反例 | DSH 读了 `locateCitationExcerpt`、引用使用点和专项测试。实际探针显示 `DRV-070` 可误高亮 `DRV-07`，`TEST-9001` 可误高亮 `TEST-900`。前端三个相关测试文件分别 12、9、24 条通过，覆盖之外的探针揭示了这处问题。 |
| 修订和再次核验 | 正常文本查找与忽略排版空白的查找都在原文判断编号左右边界。跳过长编号的子串，保留唯一全编号，重复全编号仍拒绝定位。新增断言改前失败、改后通过。DSH 在原会话自行重跑引用用例 13 条和工作单用例 9 条，进程均为 0。 [原文定位实现](../workspace/vscode/ems-forword/src/utils/private-knowledge.js#L69) · [回归用例](../workspace/vscode/ems-forword/tests/private-knowledge.test.js#L111) |

DSH 的 Windows 测试环境在 Node 的默认子进程隔离模式报 `spawn EPERM`。按 Node 的测试执行模型，用进程内运行目标文件验证原断言。DSH 的 Python 全套仍受同一环境的临时目录权限影响。Codex 在正常终端复跑 213 项前端测试、构建、11 项真实 PostgreSQL 检索专项及六项预检。Ego 打开质检员的知识页，确认版本 v1 第 15 行的在用 `DRV-07` 原文正常高亮。 [Node.js 测试执行模型](https://nodejs.org/docs/latest-v24.x/api/test.html#test-runner-execution-model)
