# 私有知识与人脸服务

阶段 2 的独立服务，复用原 MES 的账号、权限与业务确认流程。浏览器只访问 Spring Boot；本服务仅监听回环地址，接收带时间戳、一次性随机值和 HMAC 的请求。

阶段2功能与本轮教师要求复核见[发布验收](../../docs/STAGE2_FINAL_RELEASE.md)；详细设计见[实施说明](../../docs/STAGE2_IMPLEMENTATION.md)，历史测量见[验收记录](../../docs/STAGE2_ACCEPTANCE.md)，现场顺序见[演示步骤](../../docs/STAGE2_DEMO.md)。已有本人录入及维护者反馈的免账号登录成功；最新真人总耗时、第二位参与者和错误脸仍需现场核验。

11月10日新增[私有研判工作单与体验完善](../../docs/EXPERIENCE_REFINEMENT_20261110.md)：从本人有效历史保存原答复与来源，人工补备注/清单，版本冲突保留草稿，导出和转业务前重新检查来源。`notes.list/add/update/delete`由原网关绑定身份，工作单表在本人库按需创建；原资料和历史不被改写。

人脸界面现使用[自动动作引导](../../docs/FACE_AUTO_CAPTURE.md)：连续合格后采集，三步后自动提交，可关闭语音；不要求每步点拍照。已有本人模板保留，维护者反馈免账号登录成功；最新体感及反例待现场确认。当前不检测眨眼。

## 组成

| 部分 | 实现 |
|---|---|
| 业务系统 | 原 Spring Boot / MySQL / Vue；原生产、库存、质量、AI 快捷办理保留 |
| 私有存储 | 每个 MES 用户独立 PostgreSQL 数据库、受限角色、原件目录 |
| 文档检索 | FastEmbed / BGE 中文 512 维嵌入，pgvector 真实距离检索 |
| 图谱 | LightRAG 1.5.7 / Apache AGE；按用户、候选代际启动独立进程 |
| 人脸 | 本地 YuNet / SFace 128维；免账号唯一识别，旧一对一接口兼容；模板留在每用户私有库 |
| 前端 | 我的知识、图谱、引用预览、偏好、账号安全；沿用米白与天蓝主题 |

普通上传支持 PDF、DOCX、XLSX、TXT、MD。启用 DeepSeek 工具后，可用独立扫描件入口选择 PDF、PNG、JPEG，选择页面、核对识别文字再发布私有资料版本。

课堂演示可使用[10份关联台灯资料](examples/lamp-showcase/README.md)，包含五格式构建、24题本地验收、带来源的实际建图/回答，以及明确的未批准变更与缺失阈值案例。运行事实和本轮边界见[展示完善记录](../../docs/STAGE2_SHOWCASE.md)，原两份最小样例继续保留。

## 当前环境直接启动

已有 `D:\AI-MES-Stage2\private-env.ps1`、`mes-runtime.clixml` 和模型缓存时，不要重新初始化或替换密钥：

```powershell
# 从仓库根目录执行
. .\scripts\load-stage2-environment.ps1 -NoCloud
docker compose -f .\services\private-assistant\deploy\compose.yaml up -d
.\scripts\start-stage2.ps1
```

PostgreSQL现在使用Docker原生命名卷，数据留在Docker自己的Linux文件系统中。`PRIVATE_PG_VOLUME`必须指向已创建或迁移核验的卷；缺失时Compose停止，避免把旧Windows绑定目录误当成新空库。旧配置若只有`PRIVATE_PG_DATA`，应先停机迁移并核对文件/原件/模板，再切换，不能直接新建空卷替代。`docker compose down`不会删除外部命名卷；不要把正式卷当缓存清理。本机Docker磁盘仍位于D盘。

只做本地检索或无外部模型测试时，使用`.\scripts\start-stage2.ps1 -NoCloud`；该开关会传给所有新启动的子进程。启动器先检查相应数据库端口，缺失时停止并指出依赖，不再仅留下一个能打开但无法登录的前端。已占用的服务不被替换，切换运行模式前须正常停止对应旧进程。

本机新版前端为 `http://127.0.0.1:5181/#/ai/knowledge`，后端 8089，私有服务 8091，专用 PostgreSQL 55432。已有 5180 / 8088 实例独立保留；启动脚本遇到端口占用只报告，不终止其他程序。

2026-11-08维护者明确取消验证阶段20次上限，默认`PRIVATE_LLM_LIMIT=0`，表示无本地次数上限；正整数是部署者可选限额，负数无效。所有请求仍写入原日志，不清空旧记录。11月10日本轮验证按新授权新增5次真实生成，累计40→45；本人后续问答或已允许的建图按模型平台额度使用，处理同意和原权限保持。验证脚本默认0为无限，旧有限签名任务仍按原授权上限执行。[交互与策略变更](../../docs/INTERACTION_REFINEMENT.md)保留早期记录。

## 可选的 DeepSeek 应用工具

默认关闭。需要联网试用时，在启动环境设置 `PRIVATE_DEEPSEEK_TOOLS_ENABLED=1`，并通过助手进程的 `PRIVATE_DEEPSEEK_TOOLS_KEY` 或运行目录内的 `deepseek-tools-secret.clixml` 提供独立 DeepSeek API Key。Windows 启动器读取后者的 `ApiKey` SecureString，仅由启动它的同一 Windows 用户在本机解密。保留原模型配置。已经运行的进程按正常流程重启后读取新配置。

可在 Windows PowerShell 当前用户会话中创建独立凭据文件。先把示例路径换成自己的运行目录，如已有凭据文件则沿用。

```powershell
$toolsCredential = 'D:\AI-MES-Stage2\deepseek-tools-secret.clixml'
if (-not (Test-Path -LiteralPath $toolsCredential)) {
    @{ ApiKey = (Read-Host 'DeepSeek API Key' -AsSecureString) } | Export-Clixml -LiteralPath $toolsCredential
}
$env:PRIVATE_DEEPSEEK_TOOLS_ENABLED = '1'
.\scripts\start-stage2.ps1
```

设置 `PRIVATE_DEEPSEEK_FETCH_HOSTS` 可用逗号分隔的域名配置应用内网页读取。PDF 扫描需确保 `pdftoppm` 在助手的 PATH。官方 `deepseek-flash` 可独立设置 `PRIVATE_DEEPSEEK_SAFETY=1`。实际工具调用可能计费，课堂离线预检使用 `-NoCloud`。 [采用范围、价格和验收方法](../../docs/DEEPSEEK_APPLICATION_20261114.md)

## 新机器准备

实际验证环境是 Windows AMD64、Python 3.12.14、JDK 21、Node.js 24、Docker。以下仅用于新的独立环境，原业务数据库按 [原运行说明](../../docs/SETUP.md)维护。

```powershell
New-Item -ItemType Directory -Path 'D:\AI-MES-Stage2' -Force
py -3.12 -m venv 'D:\AI-MES-Stage2\.venv'
& 'D:\AI-MES-Stage2\.venv\Scripts\python.exe' -m pip install -r .\services\private-assistant\requirements.lock.txt
$env:PRIVATE_RUNTIME_ENV='D:/AI-MES-Stage2/private-env.ps1'
& 'D:\AI-MES-Stage2\.venv\Scripts\python.exe' .\services\private-assistant\scripts\init_runtime.py
```

初始化拒绝覆盖现有配置；配置丢失但已有数据库或资料时，也会停止，要求恢复原配置。HMAC 密钥参与用户角色密码和人脸附件加密，不能随意重新生成。

新配置应只允许当前 Windows 用户、SYSTEM 和管理员读取：

```powershell
$principal=[Security.Principal.WindowsIdentity]::GetCurrent().Name
icacls.exe 'D:\AI-MES-Stage2\private-env.ps1' /inheritance:r /grant:r ($principal + ':(F)') '*S-1-5-18:(F)' '*S-1-5-32-544:(F)'
. .\scripts\load-stage2-environment.ps1 -NoCloud
docker volume create $env:PRIVATE_PG_VOLUME
docker compose -f .\services\private-assistant\deploy\compose.yaml up -d --build
& 'D:\AI-MES-Stage2\.venv\Scripts\python.exe' .\services\private-assistant\scripts\prepare_models.py
```

镜像固定 PostgreSQL 基镜像摘要、pgvector 与 AGE 提交。模型来源与本轮实测文件哈希见 [models.lock.json](models.lock.json)；下载程序校验两个 OpenCV 权重，并在私有目录记录实际模型清单。更换嵌入模型必须重建向量和图索引。

云模型可沿用本机已有的 `AI_API_URL`、`AI_MODEL`、`AI_API_KEY`，或使用 Windows 用户加密的 `model-secret.clixml`。密钥只在后端进程内解密；不要写入前端变量、仓库、命令行参数或截图。新环境可由本人在 PowerShell 中安全输入：

```powershell
$modelPath='D:\AI-MES-Stage2\model-secret.clixml'
if(Test-Path -LiteralPath $modelPath) { throw '已有配置保留，请勿重复初始化。' }
$modelKey=Read-Host '请输入模型 API 密钥' -AsSecureString
[pscustomobject]@{ ApiUrl='https://api.deepseek.com'; Model='deepseek-chat'; ApiKey=$modelKey } | Export-Clixml -LiteralPath $modelPath
$modelKey=$null
icacls.exe $modelPath /inheritance:r /grant:r ($principal + ':(F)') '*S-1-5-18:(F)' '*S-1-5-32-544:(F)'
```

以上代码没有分发任何密钥，也不会发起模型请求。

## 独立 MES 副本与构建

将自己的本地演示库复制为新库。工具只读取源库，拒绝覆盖目标库，导入后核对所有基础表的行数和源库前后状态。凭据从当前进程或明确指定的本地环境脚本读取。

```powershell
.\scripts\initialize-stage2-mes.ps1 -SourceDatabase 'mes_lamp_demo' -DatabasePort 3307
. .\scripts\load-stage2-mes-environment.ps1 -NoCloud
Push-Location .\workspace\idea\ems
.\mvnw.cmd -B '-Dmes.build.directory=D:/AI-MES-Stage2/build/backend' package
Pop-Location
.\scripts\start-stage2.ps1
```

源库必须是自己管理的 `mes_lamp_*` 演示库。若现有回归测试要求库名为 `mes_lamp_lineside_*`，另外复制一个 `mes_lamp_lineside_stage2_YYYYMMDD` 测试库，不修改旧库，也不放宽测试隔离条件。

后端使用单独构建目录，并按SHA-256暂存可执行包，避免覆盖正在运行的旧包。`-NoCloud`用于普通测试，禁用外部模型连接；正常启动时，旧业务模型请求也通过本机受认证适配器写入同一调用记录。

## 验证命令

```powershell
. .\scripts\load-stage2-environment.ps1 -NoCloud
$env:PRIVATE_INTEGRATION='1'
Push-Location .\services\private-assistant
$env:PYTHONPATH=(Get-Location).Path
& 'D:\AI-MES-Stage2\.venv\Scripts\python.exe' -m pytest -q
& 'D:\AI-MES-Stage2\.venv\Scripts\python.exe' .\scripts\evaluate_offline.py --output 'D:\AI-MES-Stage2\evidence\my-offline-run'
Pop-Location
```

离线案例使用自动生成的两位测试用户和合成文件，覆盖 80 个检索、来源、路由和生命周期案例，不生成云端回答。题目、预期事实和逐项结果都会输出；它的通过率不能当作真实生成准确率。

网关验证脚本 `scripts/verify_gateway.py` 只支持独立回环演示端口；通过进程环境提供演示账号口令，验证鉴权、跨用户拒绝、下载哈希、原有受限岗位拒绝、管理员权限及注销，不打印令牌。

## 数据与恢复

- 文件当前版本与已发布问答版本分开记录。新向量成功后原子发布；失败继续使用旧版本并显示版本号。
- 图谱按完整候选代际重建；部分失败不影响本地文档问答。模型配置相同、来源 ID / 版本 / 哈希完全一致时，才复用抽取缓存。
- 删除先撤销检索资格和旧图，再清理原件、向量及缓存。共享片段只保留仍有效来源，不能因删掉一个重复来源而删掉另一个来源的依据。
- 旧问答标为失效，不进入新回答上下文；用户可另行清除本人历史。
- 上传、更新、删除、重试和建图提交后直接唤醒任务；周期扫描只补偿遗失通知和重启恢复。新操作优先于历史空库检查，单库故障不会阻断其他用户。
- 任务可取消、重试，最多三次显式尝试。跨进程索引租约保护活跃任务；取消与重试的代际检查覆盖向量发布、图谱发布及后续模型外发。取消前已经发布的版本保留，取消后尚未发布的版本不会继续上线。
- 私有配置、人脸、文件、数据库和请求日志留在运行目录，不随开源代码分发。备份与恢复应同时保护原件、数据库和原配置，不能只保留一份代码。

安全边界和人脸验收限制见 [实施说明](../../docs/STAGE2_IMPLEMENTATION.md)。依赖许可见 [第三方说明](THIRD_PARTY_NOTICES.md)，调研与具体借鉴入口见 [参考清单](../../docs/STAGE2_REFERENCES.md)。
