---
version: 1
slug: ai-workbench-refinement-20261111
primary_target: workspace/vscode/ems-forword/src/pages/ai/workbench.vue
related_targets: [workspace/vscode/ems-forword/src/pages/ai/workbench.css, workspace/vscode/ems-forword/src/components/ai/AiDock.vue]
---

# AI 工作台逐模块精修

Mode: Operate. Incumbent refinement.

THESIS: 让用户直接描述任务、找到办理入口，并在切换会话或展示形态时保住尚未发送的输入。

OWN-WORLD: 延续米白、暖白、天蓝、既有字体与 AppIcon。采用现有 Vue、Element Plus、会话 store、接口及权限目录。质量体现为内容层次、清晰反馈、连续操作和失败恢复。

STORY: 从输入或示例开始 → 可撤销填入示例 → 按会话保留草稿 → 选择快捷办理并预览 → 切换工作台/浮窗继续 → 失败保留原历史与输入 → 退出清理当前身份的内存状态。

FIRST VIEWPORT: 保持左侧快捷办理入口，增加搜索及独立会话入口。欢迎区以当前任务和两个可回填问题为主，其他场景按需展开。输入区在各尺寸可见，计数随输入出现。移动侧栏有清楚的开关，选择任务或会话后回到正文。

FORM: code-led refinement，沿用现有视觉系统，无新 comp、seed、图像资产或依赖。参考已记录的 Claude/Codex 内容优先组织和 Carbon/Linear 的任务导航做法，当前范围保留所有能力及办理路径。

FINISH: 真实 store 回归覆盖草稿、撤销、同步、身份变更和远程操作失败。Ego Lite 只在专用测试页进行后台操作，禁止恢复窗口或激活前台。检查桌面、平板、390×844、470×668 的布局与交互。缺少有效原生图时保留视觉复核状态，不用打印缩放图代替像素验收。
