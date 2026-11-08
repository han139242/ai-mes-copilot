# 第二轮升级：开源借鉴与集成清单

调研日期：2026-11-07，集成状态更新：2026-11-08。执行方案见 [STAGE2_PLAN.md](STAGE2_PLAN.md)，F01—F17 对应其中的教师要求编号。下表区分实际采用、方法参考和候选；当前证据见 [STAGE2_ACCEPTANCE.md](STAGE2_ACCEPTANCE.md)。

## 1. 主线组件：通过适配层接入

| 项目与官方地址 | 可复用的内容与入口 | 融入 MES 的具体方式 | 对应要求、版本与许可 |
|---|---|---|---|
| [HKUDS/LightRAG](https://github.com/HKUDS/LightRAG) | [版本说明](https://github.com/HKUDS/LightRAG/blob/v1.5.7/README.md)、[引擎](https://github.com/HKUDS/LightRAG/blob/v1.5.7/lightrag/lightrag.py)、[PostgreSQL 适配](https://github.com/HKUDS/LightRAG/blob/v1.5.7/lightrag/kg/postgres_impl.py)：实体/关系抽取、图检索、融合查询 | 独立 Python 服务调用 SDK，Spring Boot 提供身份与权限；自建统一的资料和问答界面。每用户独立运行上下文、库和角色，不能仅用 workspace。文档向量层与图层解耦 | F08—F13。实际 `lightrag-hku==1.5.7`，对应提交 `28ff1b05f2ac3f3e6fa14dd2cd33656579bd0c9c`。[MIT](https://github.com/HKUDS/LightRAG/blob/v1.5.7/LICENSE)。实际接库、抽取、图/融合检索及生成、更新删除已验证 |
| [pgvector/pgvector](https://github.com/pgvector/pgvector) | [v0.8.1 说明](https://github.com/pgvector/pgvector/blob/v0.8.1/README.md)：`vector(n)`、距离计算与向量索引 | 每人独立库；文本 `vector(512)` 与人脸 `vector(128)` 分表、分用途。维度绑定模型；先精确召回，再按实测规模决定近似索引 | F01、F06、F08。实际扩展 `v0.8.1`，提交 `778dacf20c07caf904557a88705142631818d8cb`。[PostgreSQL License](https://github.com/pgvector/pgvector/blob/v0.8.1/LICENSE)。普通角色真实距离查询及隔离通过；客户端单独锁定 |
| [apache/age](https://github.com/apache/age) | [PG17/v1.7.0-rc0 说明](https://github.com/apache/age/blob/PG17/v1.7.0-rc0/README.md)：PostgreSQL 内图数据和 openCypher | 在用户私有库中保存节点、边、版本与来源；普通角色完成真实图读写，前端仅接收授权子图 | F06、F10—F13。实际提交 `e1467f12e0b1d15dd35d3ab93f057a7112d425b8`，实读 1.7.0；该 PG17 标签为候选发布版本，本机组合已实测，未推导其他版本兼容。[Apache-2.0](https://github.com/apache/age/blob/PG17/v1.7.0-rc0/LICENSE)。LightRAG 的 AGE >=1.8 保护检查保留 |
| [qdrant/fastembed](https://github.com/qdrant/fastembed) | [用法](https://github.com/qdrant/fastembed/blob/main/README.md)、[支持模型](https://qdrant.github.io/fastembed/examples/Supported_Models/)：本地 ONNX 嵌入 | 本地生成资料与问题向量，缓存放专用环境；记录模型、维度、修订和哈希，更换模型重建索引 | F05、F08。当前依赖 `fastembed==0.9.1`。[Apache-2.0](https://github.com/qdrant/fastembed/blob/main/LICENSE)。512 维输出、五格式资料检索和固定合成案例已验证；实际质量分母见验收记录 |
| [opencv/opencv_zoo](https://github.com/opencv/opencv_zoo) | [YuNet](https://github.com/opencv/opencv_zoo/tree/48b10fa505aeb51fbd127fc60a5c660040e823b7/models/face_detection_yunet)、[SFace](https://github.com/opencv/opencv_zoo/tree/48b10fa505aeb51fbd127fc60a5c660040e823b7/models/face_recognition_sface)：检测、对齐和特征比对 | 本地免账号唯一识别，旧一对一接口兼容；服务端挑战、重录/解绑、管理员重置；认证接回原MES会话，不上传人脸到大模型，详见[识别说明](FACE_AUTO_CAPTURE.md) | F01—F04。模型库锁定 `48b10fa505aeb51fbd127fc60a5c660040e823b7`。[YuNet MIT](https://github.com/opencv/opencv_zoo/blob/48b10fa505aeb51fbd127fc60a5c660040e823b7/models/face_detection_yunet/LICENSE)、[SFace Apache-2.0](https://github.com/opencv/opencv_zoo/blob/48b10fa505aeb51fbd127fc60a5c660040e823b7/models/face_recognition_sface/LICENSE)。权重另做哈希校验；已有一次本人手动录入，新自动重登及反例待本人验收 |
| [apache/echarts](https://github.com/apache/echarts) | 现有库的 graph 系列：节点、边、布局和交互 | 复用现有 ECharts，实现搜索、类型筛选、一至两跳展开、来源及答案路径高亮；另有可操作文字列表，真实图存储由后端负责 | F12、F13。当前安装 `6.1.0`，[Apache-2.0](https://github.com/apache/echarts/blob/6.1.0/LICENSE)。实际图谱入口、节点/关系及来源已在浏览器检查 |

### 本地嵌入模型

| 官方模型页 | 决定 | 验证与分发边界 |
|---|---|---|
| [BAAI/bge-small-zh-v1.5](https://huggingface.co/BAAI/bge-small-zh-v1.5) | 首轮中文嵌入候选，512 维；配合 FastEmbed 控制资源成本 | 模型页 MIT。记录文件修订与哈希；用中文 SOP、精确料号、多文档问题测召回，不能只凭模型名称判断质量 |
| [BAAI/bge-m3](https://huggingface.co/BAAI/bge-m3) | 后续质量与资源对照，不是默认安装项 | 模型页 MIT。先测内存、耗时与召回收益；更换时建立新索引，不能与 512 维旧向量混用 |

## 2. 交互与方法参考：按模块吸收

| 官方仓库 | 借鉴细节 | MES 落点与验收 | 采用边界与许可 |
|---|---|---|---|
| [infiniflow/ragflow](https://github.com/infiniflow/ragflow) | 知识管理、处理进度、切块预览、答案引用 | “我的资料”分别显示解析/向量/图谱状态、失败原因和重试；引用打开文件版本与原文片段。F07—F09 | 参考交互，用现有 Vue 组件实现；不叠加另一整套平台及权限体系。[官方说明](https://github.com/infiniflow/ragflow/blob/main/README.md)，Apache-2.0 |
| [OpenSPG/KAG](https://github.com/OpenSPG/KAG) | 领域约束、知识与原文互索引、跨文件证据 | 约束产品—物料—工序—检验项—缺陷关系，每条边回到来源，实测多跳。F10、F13 | 借鉴建模方法，通过 LightRAG 提示、校验和来源台账适配，不启动第二套图引擎。[官方说明](https://github.com/OpenSPG/KAG/blob/master/README.md)，Apache-2.0 |
| [microsoft/graphrag](https://github.com/microsoft/graphrag) | 图谱增强检索、局部/全局问题区分、评测思路 | 比较单文档与跨文件题；检查图路径是否贡献有效证据。F11、F13、F16 | 方法参考。官方说明以维护为主，并提示索引费用；不引入第二套索引引擎。[官方说明](https://github.com/microsoft/graphrag/blob/main/README.md)，MIT |
| [docling-project/docling](https://github.com/docling-project/docling) | 复杂版面、表格、OCR | 首批五格式稳定后，对照复杂中文 PDF/扫描件，保留页、段落、表格定位。F07—F09 | 增强候选，首版扫描件无文本时明确失败，不能冒充已索引。[官方说明](https://github.com/docling-project/docling/blob/main/README.md)，MIT；外部模型另核对许可 |

## 3. 备选与有条件采用

| 官方仓库 | 何时考虑 | 当前决定与限制 |
|---|---|---|
| [antvis/G6](https://github.com/antvis/G6) | ECharts 原型不能满足图交互、布局或性能要求时 | 前端备选，不重复建设同一页面。候选 `5.1.1`，[MIT](https://github.com/antvis/G6/blob/5.1.1/LICENSE)。尚未集成，老师未要求必须用 G6 |
| [langgenius/dify](https://github.com/langgenius/dify) | 核心通过且有资源支持真实工作流运行时 | 质量异常核查工作流候选；仅导出流程文件不算已运行。优先评估既有 MES 编排。[许可证](https://github.com/langgenius/dify/blob/main/LICENSE)有多租户与前端标识附加条件，按实际采用方式核对，不能一概当作普通 Apache-2.0 |
| [deepinsight/insightface](https://github.com/deepinsight/insightface) | 确有比较需要且具体模型已获适用许可时 | 不作为默认分发方案。[官方说明](https://github.com/deepinsight/insightface/blob/master/README.md#license)区分代码 MIT 与训练数据、预训练模型的非商业研究限制；代码开源不代表所有权重可随可商用项目分发 |

## 4. 可直接安排的借鉴任务

| 优先级 | 参考来源 | 具体工作 | 完成证据 |
|---|---|---|---|
| P1 | pgvector、AGE、LightRAG PG 适配 | 候选镜像、扩展、两用户普通角色向量/图读写、跨库拒绝；确认 SDK 能否分离索引 | 扩展版本、真实查询与拒绝结果，不能只有构建日志 |
| P2 | FastEmbed、RAGFlow | 本地嵌入、五格式 CRUD、版本任务、向量先可用、图独立处理、引用预览 | 增改删同题对照、下载及引用鉴权、图失败时向量仍可用 |
| P3 | LightRAG、KAG、ECharts | 领域约束、来源台账、图谱 API、子图、模式与路径 | 多文件多跳逐跳有依据；撤销来源后真实路径变化 |
| P4 | OpenCV Zoo | 本人绑定/登录/重录/解绑、管理员重置、真实人脸向量存储、原会话接入 | 本人摄像头演示及错误脸、多人、重放、拒绝权限等反例 |
| P5 | RAGFlow 交互、原 MES 助手 | 偏好、上下文、引用跳转、业务预览与故障恢复 | 岗位差异合理，偏好不授权，写入仍经原系统确认 |
| P6 | GraphRAG 评测、Dify 候选 | 离线题库、有限真实调用、工作流、部署与报告 | 检索/生成/端到端样本分母分别报告，流程和分工有真实证据 |

## 5. 集成和开源检查

1. 优先安装调用，不随意复制完整工程。确需复制或改写上游代码时，记录固定提交、原文件和改动，保留适用版权、许可证及 NOTICE。
2. 参考交互时在现有米白、天蓝设计中实现，避免拼入上游账号和第二套前端。
3. 实际集成后更新依赖与第三方许可清单。项目自身的 MIT LICENSE 不能覆盖上游附加条件或模型独立许可。
4. `main`/`master` 用于定位项目，不是可复现锁文件。构建时固定镜像、依赖、模型与解析器版本并留证；未采用的参考项目无需伪造安装版本。
5. 云模型建图会发送待抽取文档片段，问答会发送检索依据，须先获得该用户的处理同意。维护者已取消本地20次验证上限，调用日志保留；维护测试使用合成资料和本地检索，不主动新增收费请求。人脸不外发。
6. 候选、已安装、已集成、已验证分别记录；安装成功不能替代兼容、权限、问答质量与实际资源验证。已有本人录入及维护者反馈的登录成功；最新版本的真人总耗时、第二位参与者和错误脸仍须现场验收。技术检查和人工验收分开记录。

## 6. 当前实际采用清单

- 直接依赖：LightRAG、pgvector、AGE、FastEmbed、OpenCV 和原有 ECharts；格式解析使用 pypdf、python-docx、openpyxl。
- BGE 原始模型来自 BAAI，运行采用 [Qdrant/bge-small-zh-v1.5](https://huggingface.co/Qdrant/bge-small-zh-v1.5) ONNX 转换，实际修订与文件哈希见 [模型锁文件](../services/private-assistant/models.lock.json)。未下载 BGE-M3。
- RAGFlow、KAG、Microsoft GraphRAG 提供交互、领域关系和评测方法参考；没有复制或启动这些平台。
- Docling、G6、Dify、InsightFace 未集成。Dify 加分项没有取得运行证据。
- Python 实际依赖锁定见 [requirements.lock.txt](../services/private-assistant/requirements.lock.txt)，适用许可见 [第三方说明](../services/private-assistant/THIRD_PARTY_NOTICES.md)。项目 MIT 许可不代替第三方或模型许可。
