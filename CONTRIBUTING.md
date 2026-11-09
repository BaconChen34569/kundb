# 参与贡献

欢迎提 Issue 和 Pull Request。这是一个课程课题项目，维护节奏以教学演示为主，
回复可能不及时，但每条反馈都会被看到。

## 提 Issue

- Bug：写清 KUN-DB 版本、操作系统、复现 SQL、期望结果与实际结果（可贴 `kundb.log` 片段）。
- 功能建议：说明使用场景，以及是否涉及 SQL 语法扩展。
- **不要**在 Issue 里贴真实口令、手机号、私钥或业务数据。
- 若为比赛 / 活动报名的格式类 Issue，请严格按活动方模板填写（标题与正文之间不换行、
  字段之间不插空行、链接写纯文本 URL）。

## 开发环境

| 组件 | 要求 |
|---|---|
| JDK | 17 或更高 |
| Maven | 3.9+（需在 PATH 中） |
| Qt / CMake | Qt 6（Widgets、Network），CMake 3.16+（仅构建 GUI 客户端时需要） |
| Python | 3.10+（仅运行 `test_client.py` 协议测试时需要） |

内核模块不依赖 Qt，只有 `kundb-qt/` 需要。

```bash
mvn -q install -DskipTests          # 构建 kundb-common + kundb-server
cmake -S kundb-qt -B kundb-qt/build && cmake --build kundb-qt/build

# 仓库暂未收录 JUnit 用例，功能验收以 sql/ 下的脚本为准
```

## 提交规范

1. 从 `main` 建分支，分支名用 `fix/xxx`、`feat/xxx`、`docs/xxx`。
2. 提交信息写清「做了什么 + 为什么」，一行标题，必要时空一行写正文。
3. 提交前自检：

   ```bash
   git status --short
   python ~/.workbuddy/skills/github-preflight/scripts/preflight.py . --staged
   ```

   出现 `[BLOCK]` 必须先处理；`[WARN]` 逐条确认后再提交。

4. **不要提交**：`kundb-data/` 运行数据、`target/` / `build/` 产物、
   `*.msi` / `*.zip` / `*.jar` / `*.dll`、`.env`、日志、IDE 配置、课程报告与模板。
   安装包只走 GitHub Releases。
5. 涉及 SQL 语法或执行计划变更时，请在 PR 里附最小复现 SQL 与 `EXPLAIN` 输出。
6. 涉及协议层变更时，请同时跑 `test_client.py` 与 Qt 协议自测（`kundb-qt-test`）。

## 许可

提交即表示你同意你的贡献以本仓库 [LICENSE.md](LICENSE.md)（MIT）发布，
并确认你有权提交这些内容。第三方代码或素材请在 PR 中注明来源与许可证。
