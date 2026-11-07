# 当前台灯系统与历史资料

当前运行与维护目标为智能调光台灯 L100 / L200 的后端和 PC 端。初始化只使用 [SETUP.md](SETUP.md) 列出的台灯脚本；不要对全部 SQL 文件批量执行。

以下资料保留原始业务含义，方便追溯历史，不属于当前台灯初始化或运行入口：

- `workspace/vx/`：早期羽毛球场景小程序及其 mock 数据，尚未迁移为台灯小程序。
- `workspace/idea/ems/docs/wechat-mini-program-requirements.md`、`羽毛球MES开发约束框架.md`：早期场景设计档案。
- 数据库目录中的 `enterprise_baseline_data.sql`、`renix_enterprise_master_migration.sql`、`account_username_abbreviation_v2.sql`、`account_username_pinyin_v1.sql`、`rbac_permission_repair_v2.sql`：历史基线或迁移，执行前需逐项核对适用环境，不能作为新台灯环境的初始化入口。
- `scripts/generate_enterprise_sops.py`：旧场景 SOP 生成器，不用于台灯工艺文件。
- 旧背景图片若被历史说明引用，其版权与素材来源仍需保留；当前登录页以台灯场景组件呈现。

`scripts/initialize_lamp_clean.py` 中的旧场景词表用于识别和拦截遗留数据，应保留。历史兼容注释不代表系统仍以旧场景运行。本轮不删除历史迁移、不改写既有数据库记录、不把旧产品的数据简单更名为台灯数据。
