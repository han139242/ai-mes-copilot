---
version: 1
slug: "orword-src-pages-ai-private-knowledge-vue-9f5284b3"
primary_target: "workspace/vscode/ems-forword/src/pages/ai/private-knowledge.vue"
related_targets: ["workspace/vscode/ems-forword/src/pages/account/security.vue","workspace/vscode/ems-forword/src/components/auth/FaceCaptureDialog.vue"]
---

# 私有知识与账号认证扩展

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
