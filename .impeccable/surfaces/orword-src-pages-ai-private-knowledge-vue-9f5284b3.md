---
version: 1
slug: "orword-src-pages-ai-private-knowledge-vue-9f5284b3"
primary_target: "workspace/vscode/ems-forword/src/pages/ai/private-knowledge.vue"
related_targets: ["workspace/vscode/ems-forword/src/pages/account/security.vue","workspace/vscode/ems-forword/src/components/auth/FaceCaptureDialog.vue"]
---

# 私有知识与账号认证扩展

## 2026-11-11 输入与会话恢复精修

Mode: Operate，延续既有世界。知识示例与图谱回填可撤销，空白新会话可返回上一会话，恢复时继续核对资料版本；清除历史保留正在编辑的问题并拦截迟到回填。资料空搜索可直接清除，空计数收起，手机发送位置保持稳定。当前范围沿用现有布局、token和图标。

本批功能回归与Ego后台操作记录见docs/FRONTEND_EXPERIENCE_20261111.md。screen媒体导出仅作渲染补充，原生视口捕获仍待补齐，未宣称本批视觉批准或教师最终评分。

## 2026-11-10 研判工作单与阅读精修

THESIS: 从一条有出处的答复延伸到本人可保存、可核对和可交接的工作单；减少问答后重复复制整理。扩展原工作区，不替换既有业务或认证。

OWN-WORLD: 继承米白画布、暖白阅读面、天蓝操作色、中文系统字及同一套AppIcon与Element Plus。颜色用于操作和真实状态，靠文字、间距和内容建立层级。

STORY: 提问或打开历史 → 查看分段答复与少量来源 → 保存本人工作单 → 人工补备注/核对项 → 核对当前来源版本 → 导出交接或进入原业务草稿。资料变更后工作单明确过期，不替代审批。

FIRST VIEWPORT: 保留侧栏和原快捷入口；顶端紧凑四页签与上传操作。问答侧是资料，主区域以问题和答案为中心；工作单侧是标题列表，主面为答复/依据及人工核对双列，窄屏纵向排列，保存和导出可发现。

FORM: incumbent extension, code-led; seed: not applicable。用户已明确授权按成熟做法直接加入合适功能；既有配色与品牌已经锁定，不另换世界，不做概念选举。

FINISH: 按老师必做项保留RAG、图谱、隔离与人脸入口，Ego真实操作加桌面/窄屏渲染复核；完成独立代码与界面复核。文档记录实际证据与边界，不改原报告或虚构最终评分。

11月10日完成：Ego实际使用及1440×960、1920×1080、390×844、470×668复核。原生截图故障时使用同一页面screen-media导出渲染，并与真实交互、DOM测量分开记录。独立界面复核提出手机空会话滚到底和密集图标签重叠；同尺寸重捕后两项均resolved、disposition ship，结论覆盖该修复清单。既有tokens、组件和UI_DESIGN保持，无新世界/comp/素材。前端130项通过，细节见docs/EXPERIENCE_REFINEMENT_20261110.md。

Mode: Operate。基于已确认的第二轮方案，为现有 MES 增加资料、问答、图谱及本人认证入口；沿用原工作区与设计语汇，不重新选择视觉世界。

## Direction contract

THESIS: 用户先找到自己的资料并得到可打开的依据，再追查关系或办理业务。文件状态和失败恢复进入主流程，不把功能说明卡片当页面。

OWN-WORLD: 沿用现有 tokens.css 的米白画布、浅色面板、天蓝操作色、岗位状态色、中文系统字体及 Element Plus 控件；图标继续使用 AppIcon。

STORY: 上传文件，看到解析/向量/图谱状态；提出问题，点击引用查看同一版本的原文；查看关系路径；需要办理时回到原业务助手与人工确认。本人可管理偏好与人脸，管理员可受控重置。

FIRST VIEWPORT: 保留原侧栏和快捷入口。知识内容左侧为可搜索资料列表，主区域为问答；原文按需相邻展开。顶部只有主标题、状态和上传操作。图谱与设置使用熟悉的页签，窄屏改为纵向和可收起列表。

FORM: 现有 MES 工作区的功能扩展，保持已有视觉和标准控件；不做视觉重设计或概念选举。资料引用与原文定位是本轮的主要交互。seed: not applicable (incumbent extension, brief pinned)。

FINISH: ordinary extension of the incumbent system. Compare against tokens, existing components and docs/UI_DESIGN.md; preserve the visual world. Finish review first returned three local fixes; the follow-up scored all three resolved, disposition ship. This verdict covers the fix list, not human face-camera acceptance. No new shipping raster asset or new DESIGN.md was required.

## Required states and checks

空库、处理中、局部失败、模型未配置/认证失败/额度不足、引用失效、删除、重试、摄像头拒绝与过期。真实接口与来源，不放假数据；可见焦点、键盘操作、减少动态效果。先完成真实流程，再按用户桌面宽度和代表性窄屏批量验收。

## 2026-11-08 体验深化记录

沿用上述 Operate 约定，追加当前图中有来源的最短关联路径、文件拖入、聚焦业务类型、短屏固定输入和待办分页。1440px 桌面、390×844、470×668 及 1920×1080 大屏实际查看；Ego 验证原生键盘、资料生命周期、来源与草稿、原快捷办理和失败恢复。本轮前端94项与构建通过，三份专项回归包含32项内部断言，不再次相加。

独立界面复核先提出短屏输入与快捷按钮两个问题，修复后又纠正了放大按钮与下一行链接的2px碰撞。最终三项均 resolved，disposition: ship；此评分仅覆盖该修复清单，不代替真人摄像头和教师验收。单次检测发现的四项粗边及一项登录网格提示逐项整改并核对源码，没有增加忽略项。详情与官方参考见 docs/EXPERIENCE_REFINEMENT.md。未更换视觉世界或新增素材。
