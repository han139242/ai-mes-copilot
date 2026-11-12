# 参与贡献 / Contributing

项目包含基础 MES、业务 AI、阶段 2 私有知识、图谱、研判工作单与认证扩展，欢迎社区维护。当前范围见[AI 架构](docs/AI_ARCHITECTURE.md)和[验证说明](docs/VERIFICATION.md)，历史记录见[阶段 2 验收](docs/STAGE2_ACCEPTANCE.md)。可以从文档、独立环境验证、自动化检查或具体业务问题入手。

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
- 阶段2私有服务：按[服务README](services/private-assistant/README.md)准备独立环境。维护者已取消20次验证上限，调用记录保留；自动检查使用`-NoCloud`及模拟模型，不以取消上限为由主动产生收费请求。真人摄像头验收由本人执行。

没有运行的检查请直接注明，不用旧结果代替本次验证。不通过删除、跳过测试或弱化断言掩盖问题。

## 许可

提交时请确认有权公开所提交的内容。项目原创代码采用 [MIT License](LICENSE)，保留第三方依赖及引入代码的版权和许可声明。

## English

Community contributions are welcome. Open an issue with reproducible steps or submit a focused pull request. Use an isolated database, keep secrets in environment variables, update `COLLABORATION.md`, and report the checks you actually ran. Public contributor credits use GitHub usernames. Original project code is licensed under MIT; retain third-party notices.

### Start here

Read the [English project overview](README.en.md), [setup guide](docs/SETUP.en.md), and [AI capability catalog](docs/FEATURES.en.md). Search existing issues before opening a new one. Report the project version, environment, reproduction steps, expected behavior, and actual behavior. Remove credentials, personal information, and real business data from logs and screenshots.

### Submit a change

1. Fork the repository and create a focused branch.
2. Prepare your own isolated demo or test database using the setup guide.
3. Preserve business prerequisites, transaction boundaries, inventory conservation, and duplicate-request checks.
4. Update relevant documentation and append a dated entry to `COLLABORATION.md` with the scope and verification result.
5. Open a pull request describing the problem, final behavior, checks actually run, and any migration or compatibility impact.

The repository's local Git hooks require each commit to update `COLLABORATION.md`. Use an email associated with your GitHub account; GitHub's noreply address can protect a private email. Use usernames for public contributor credits.

### Verify the affected scope

- **PC frontend:** run `npm ci`, `npm test`, and `npm run build` in `workspace/vscode/ems-forword`. Also check actual pages when changing interactions.
- **Runtime/preflight tools:** run `node --test scripts/local-runtime.test.mjs scripts/ai-decision-preflight.test.mjs` from the repository root.
- **Backend:** prepare an isolated test database, then run `.\mvnw.cmd -B package` in `workspace/idea/ems`; use `./mvnw -B package` on Linux/macOS.
- **Documentation:** verify local links, commands, configuration names, and consistency with the source. Check both language versions when changing shared instructions.
- **Stage 2 private service:** follow the [isolated setup (Chinese)](services/private-assistant/README.md). The owner removed the local 20-call validation cap; preserve the journal. Use offline/mocked requests for maintenance. Removing the cap does not authorize paid test traffic. Human camera acceptance requires the participant.

State which checks were not run. Keep existing test assertions and report actual failures. Submit only content you have the right to publish, and retain third-party license notices.
