# 第三方组件与模型

本轮通过依赖和公开 API 集成组件，没有复制上游整套工程、前端品牌素材或模型权重。项目自身的 MIT 许可证不替代依赖、镜像和模型的许可证。

下表根据本机已安装发行包的元数据核对；完整版本见 [requirements.lock.txt](requirements.lock.txt)。安装包自带的 LICENSE / NOTICE 保持原样，重新分发依赖或镜像时一并保留。

| 组件 | 本轮版本 | 发行包许可 | 用途 |
|---|---|---|---|
| [LightRAG](https://github.com/HKUDS/LightRAG) | 1.5.7 | MIT | 图抽取、存储适配、图检索、实体合并 |
| [FastEmbed](https://github.com/qdrant/fastembed) | 0.9.1 | Apache-2.0，含 NOTICE | 本地 CPU 文本嵌入 |
| [FastAPI](https://github.com/fastapi/fastapi) | 0.162.0 | MIT | 内部签名接口 |
| [Uvicorn](https://github.com/Kludex/uvicorn) | 0.54.0 | BSD-3-Clause | 回环服务进程 |
| [Psycopg](https://github.com/psycopg/psycopg) | 3.3.8 | LGPL-3.0-only | PostgreSQL 连接及事务 |
| [asyncpg](https://github.com/MagicStack/asyncpg) | 0.32.0 | Apache-2.0 | SDK 异步 PostgreSQL 存储 |
| [pgvector-python](https://github.com/pgvector/pgvector-python) | 0.5.1 | MIT | Python 向量类型适配 |
| [OpenCV Python](https://github.com/opencv/opencv-python) | 5.0.0.93 | Apache-2.0，含独立第三方说明 | 人脸检测、对齐、特征提取 |
| [pypdf](https://github.com/py-pdf/pypdf) | 7.0.0 | BSD-3-Clause | PDF 页码与文字提取 |
| [python-docx](https://github.com/python-openxml/python-docx) | 1.2.0 | MIT | Word 段落及表格来源 |
| [openpyxl](https://foss.heptapod.net/openpyxl/openpyxl) | 3.1.5 | MIT | 工作表及单元格来源 |
| [cryptography](https://github.com/pyca/cryptography) | 50.0.2 | Apache-2.0 或 BSD-3-Clause | 人脸特征附件加密 |
| [HTTPX](https://github.com/encode/httpx) | 0.28.1 | BSD-3-Clause | 有界、无隐式重试的模型请求 |
| [Pillow](https://github.com/python-pillow/Pillow) | 13.0.0 | MIT-CMU | 扫描图片归一化与页图输出 |
| [websockets](https://github.com/python-websockets/websockets) | 17.4 | BSD-3-Clause | DeepSeek 语音合成连接与完成帧 |

PDF 扫描件渲染调用部署机器已安装的 [Poppler](https://poppler.freedesktop.org/) `pdftoppm`。仓库和应用包不附带其可执行文件。DeepSeek 应用能力通过官方 API 接入，调用及外发范围见[调研说明](../../docs/DEEPSEEK_APPLICATION_20261114.md)。

数据库镜像基于 [PostgreSQL](https://www.postgresql.org/about/licence/)，安装 [pgvector](https://github.com/pgvector/pgvector) 与 [Apache AGE](https://github.com/apache/age)。构建固定的提交和镜像摘要保存在 [Dockerfile](deploy/Dockerfile)，数据库与发行物自带许可不作更改。

[YuNet](https://github.com/opencv/opencv_zoo/tree/main/models/face_detection_yunet) 和 [SFace](https://github.com/opencv/opencv_zoo/tree/main/models/face_recognition_sface) 的模型目录分别附 MIT 与 Apache-2.0 许可。文本嵌入原模型为 [BAAI BGE small Chinese](https://huggingface.co/BAAI/bge-small-zh-v1.5)，本机实际下载的是 [Qdrant 的转换仓库](https://huggingface.co/Qdrant/bge-small-zh-v1.5)；来源、修订和文件哈希见 [模型清单](models.lock.json)，权重不进入 Git。

RAGFlow、KAG、Microsoft GraphRAG 等是方法或交互参考；Dify、G6、Docling、InsightFace 是条件候选，不能写成已经部署的组件。采用决定与具体源码入口见 [STAGE2_REFERENCES.md](../../docs/STAGE2_REFERENCES.md)。
