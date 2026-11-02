# 参与贡献 / Contributing

项目已完成课程交付，后续欢迎社区维护。可以从文档、独立环境运行验证、自动化检查或具体业务问题入手。

## 反馈问题

先搜索已有 Issue，提供复现步骤、预期结果、实际结果和运行环境。日志与截图请移除密钥、密码、令牌、私人邮箱及个人资料。请勿提交真实业务数据或数据库快照。

功能建议请说明业务场景、涉及模块和可检查的完成条件；不要将设想描述为已实现的能力。

## 开发与提交

1. Fork 仓库并创建独立分支，按 [SETUP.md](docs/SETUP.md) 准备环境。
2. 使用自己的独立演示库或测试库。数据库连接和 AI 密钥通过环境变量提供。
3. 保留既有接口、业务前置校验、库存守恒和重复请求保护；修改行为时补充对应的验证证据。
4. 更新相关文档和 `COLLABORATION.md`，描述改动范围、检查结果及仍需处理的问题。
5. 提交 Pull Request，说明解决的问题、主要改动和实际运行过的检查。

仓库的本地提交钩子要求每次提交包含 `COLLABORATION.md` 的更新。使用关联到本人 GitHub 账号的邮箱提交代码；需要保护私人邮箱时，可使用 GitHub 提供的 noreply 邮箱。公开署名使用 GitHub 用户名。

## 按改动范围验证

- 前端：在 `workspace/vscode/ems-forword` 运行 `npm ci`、`npm test`、`npm run build`；界面交互修改还需实际页面检查。
- 运行监管与推演工具：在根目录运行 `node --test scripts/local-runtime.test.mjs scripts/ai-decision-preflight.test.mjs`。
- 后端：按运行说明初始化独立测试库，再在 `workspace/idea/ems` 运行 `./mvnw.cmd -B package`；Linux/macOS 使用 `./mvnw -B package`。
- 文档：检查链接、命令和说明是否与当前源码一致。

没有运行的检查请直接注明，不用旧结果代替本次验证。不通过删除、跳过测试或弱化断言掩盖问题。

## 许可

提交时请确认有权公开所提交的内容。项目原创代码采用 [MIT License](LICENSE)，保留第三方依赖及引入代码的版权和许可声明。

## English

Community contributions are welcome. Open an issue with reproducible steps or submit a focused pull request. Use an isolated database, keep secrets in environment variables, update `COLLABORATION.md`, and report the checks you actually ran. Public contributor credits use GitHub usernames. Original project code is licensed under MIT; retain third-party notices.
