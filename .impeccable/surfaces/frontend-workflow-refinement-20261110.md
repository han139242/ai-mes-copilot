---
version: 1
slug: frontend-workflow-refinement-20261110
primary_target: workspace/vscode/ems-forword/src/pages/resource/index.vue
related_targets: [workspace/vscode/ems-forword/src/components/layout/AppHeader.vue, workspace/vscode/ems-forword/src/components/layout/AppTabs.vue]
---

# 共用业务界面与导航精修

Mode: Operate. Refinement of the incumbent system, not a replacement visual world.

THESIS: 让用户把时间花在找到记录、核对明细与完成表单上。删去空面板和无效操作，清楚区分编辑中的条件与已查到的结果，保护尚未保存的工作。

OWN-WORLD: 保留米白画布、暖白操作面、克制天蓝色与现有状态色，使用 tokens.css、Element Plus、AppIcon 和现有中文字体。无新品牌、无装饰性动画、无新依赖；不触碰摄像头、数据库、模型额度和原业务权限。

STORY: 搜索或返回最近模块 → 输入条件并查询 → 核对当前结果条件 → 点选记录展开关联明细 → 编辑表单 → 校验并保存；取消离开时明确保留或舍弃。请求失败能重试，较早响应不能盖掉较晚查询。

FIRST VIEWPORT: 桌面端将原页签并入顶栏，保留多页管理而少占一整行；窄屏仍分行避免挤压。主操作、查询、数据按一个连续工作区排列。无选中记录时不渲染空明细表；短列表按内容收缩，长列表在可用空间滚动。批量操作只在选中后出现。移动端操作和筛选换行，表格横向滚动局限在表内，抽屉正文滚动、操作易找到。

FORM: incumbent refinement, code-led; seed not applicable. 采用 IBM Carbon 的表格工具栏与按需批量操作、SAP Fiori 的可解释筛选状态、Linear 的相关性与最近导航、Fluent 2 的抽屉防丢失原则；沿用本项目组件实现，不移植它们的框架或品牌外观。

参考补充：用户明确允许借鉴 Claude 生成页面、Claude 产品和 Codex 的界面。实际研究 Fable 5.2 公开页面合集与仪表盘复现，采用工具不抢主体、内容附近放操作、细线与间距建立层级的原则；不照搬品牌、虚构数据或不适合 MES 的舞台动效。

FINISH: Ego Lite 使用真实后端的只读查询与未提交表单；自动化测试覆盖乱序响应、失败保留、取消路由、键盘/输入法与防重复提交。查看 1440×960、1920×1080、1024×768、390×844、470×668 的实际渲染，复核加载/空/错/长内容/权限。独立代码与界面复核后再报告，保留修改前源码与证据，不宣称未经横向评测的排名。
