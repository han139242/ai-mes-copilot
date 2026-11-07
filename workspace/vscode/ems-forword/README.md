# 智能调光台灯 MES PC 管理端

这是 AI-MES Copilot 的 Vue 3 PC 前端，业务场景为 L100 / L200 智能调光台灯。使用 Vue Router、Element Plus、Axios 和 ECharts；完整运行条件见[环境配置](../../../docs/SETUP.md)。

## 启动与检查

在此目录使用 Node.js 24，首次安装运行 `npm ci`，随后执行：

```powershell
npm run dev
npm test
npm run build
```

默认开发地址为 `http://localhost:5173`，`/api` 转发至 `http://localhost:8088`。通过 `VITE_API_PROXY_TARGET` 设置后端地址。登录页的“查看基础框架”仅在开发环境可用，展示结构与预览数据；真实业务需连接自己的演示库和后端。

## 统一视觉

默认“晴空米白”：米白为主，中性深灰正文；天蓝色仅点缀主按钮、当前选中项与少量图表。墨色导航和绿、橙、红强调色沿用相同组件尺寸。颜色、字体、间距和状态色由 `src/styles/tokens.css` 维护，公共控件由 `base.css` 维护。

名称统一来自 `src/config/branding.js`；`VITE_APP_SCOPE` 只隔离本地会话，不决定业务场景。可用 `VITE_APP_NAME`、`VITE_APP_SHORT_NAME`、`VITE_APP_SUBTITLE` 自定义展示名称。

布局包括侧栏、搜索、通知、主题、标签页和移动抽屉；通用业务页共享筛选、表格、明细、表单和业务动作；AI 提供独立工作台和悬浮入口。生产运营大屏与工作台共享米白界面，状态与数据使用统一语义色。

## AI 配置

前端通过后端调用模型，不保存或直连第三方 API 密钥。按[环境配置](../../../docs/SETUP.md)在后端进程中设置 `AI_ENABLED`、`AI_API_KEY`、`AI_API_URL`、`AI_MODEL`。没有内置可供使用的真实密钥；不得把凭据写入前端或上传仓库。

## 主要目录

- `src/config/resources`：业务资源与表单配置。
- `src/components`：布局、图表、AI 和业务组件。
- `src/pages`：首页、AI、生产、质量、仓储等页面。
- `src/styles`：统一样式变量和基础控件。
- `src/utils`：会话、权限、主题及辅助逻辑。

## 参考与历史资料

早期布局研究参考过 [iMES-Factory](https://github.com/zmrid/iMES-Factory)，保留其既有许可与来源说明。本轮参考公开系统界面的布局与交互结构，没有引入其他项目的源代码或截图素材。登录页当前使用本项目的台灯场景组件；旧背景图片不作为当前界面的实现说明。

历史小程序与旧场景资料范围见[历史资料说明](../../../docs/LEGACY.md)。
