# 发布与版本规则

二进制产物**只通过 GitHub Releases 分发**，不进 Git、不进仓库目录。
本仓库采用**三条互相独立的发布线**，用 tag 前缀区分，各自建一个 Release、各自只挂自己的资产。

## 一、三条发布线

| 发布线 | 版本来源 | 当前版本 | tag | Release 标题 | 唯一资产 | 官网 channel |
|---|---|---|---|---|---|---|
| **主发行**（内核 + MSI 安装版） | Maven `pom.xml` | `1.1.1` | `v1.1.1` | `KUN-DB v1.1.1 — 服务端与 Windows 安装版` | `KUN-DB-1.1.1.msi` | `MSI` |
| **便携版**（跨平台 ZIP） | 跟随主版本，但独立 tag | `1.1.1` | `portable-v1.1.1` | `KUN-DB 便携版 v1.1.1` | `KUN-DB-1.1.1-portable.zip` | `PORTABLE` |
| **Qt 桌面客户端**（独立 ZIP） | **独立版本线** | `1.0.0` | `qt-v1.0.0` | `KUN-DB Qt 桌面客户端 v1.0.0` | `KUN-DB-QtClient-1.0.0.zip` | `QT` |

产物在构建机上的相对路径：`build/msi/`、`build/portable/`、`build/qt-client/`。
官网 `application.yml` 的 `file:` 也是相对 `site.releases-dir` 的这几条路径，`file-name:` 保持裸文件名。

### 为什么版本要分开

- **Qt 客户端是独立产品**：它只是通过线协议连服务端，`kundb-qt/` 代码不变就不升版。
  所以 Qt 客户端是 `1.0.0`，而内核已是 `1.1.1` —— 这是两条版本线，**不是漏更新**。
- **便携版与内核同版本，但独立 tag**：每次内核发布都同步产出同号便携版；
  用 `portable-` 前缀避免和主发行 `v1.1.1` 抢同一个 tag / 同一个 Release。
- **一个 Release 只挂一个资产**：不要把 MSI、便携 ZIP、Qt ZIP 塞进同一个 Release，
  否则下载页和 Releases 页面会出现「1.1.1 里混着 1.0.0 的客户端」这类歧义。

## 二、升版规则

| 改动内容 | 升级哪条线 | 是否联动 |
|---|---|---|
| 内核 SQL / 事务 / 存储 / 协议改动 | `vX.Y.Z` | 便携版同步产出 `portable-vX.Y.Z`；Qt 客户端**不动** |
| 仅便携版启动器、打包内容变化 | `portable-vX.Y.Z`（可只出修订版） | 主发行不必动 |
| `kundb-qt/` 客户端代码变化 | `qt-vA.B.C` | 内核与便携版**不动** |
| 仅官网文案、文档变化 | 不升任何二进制版本 | 更新官网仓库即可 |

语义化版本：不兼容变更升主版本，新增能力升次版本，修复升修订号。
`1.1.1` 这类纯代码清理属于修订版。

## 三、发布前一致性核对

改动版本号时必须同时核对（漏一处就会出现「装的是新版、报的是旧版」）：

1. 根 `pom.xml`、`kundb-server/pom.xml`、**`kundb-common/pom.xml` 的父版本引用**（三处必须一致）。
2. `kundb-server/src/main/resources/kundb-version.properties`。
3. MSI 的 `product.wxs` 由 pom 版本生成，重新生成后再打包。
4. 官网 `application.yml`：`site.version`，以及 `MSI` / `PORTABLE` 各条目的
   `version`、`file`、`file-name`、`title`、`title-en`。
5. Qt 客户端 ZIP 的文件名与 `QT` 条目的 `version` / `file` / `file-name`，
   按 `qt-vA.B.C` 单独核对，**不要**误填成内核版本。
6. `CHANGELOG.md` 与 `releases/notes/` 增加对应条目。

## 四、发布步骤

```bash
# 1. 三条线各自打 tag（可指向同一提交，也可各自指向对应发布提交）
git tag -a v1.1.1          -m "KUN-DB v1.1.1"
git tag -a portable-v1.1.1 -m "KUN-DB portable v1.1.1"
git tag -a qt-v1.0.0       -m "KUN-DB Qt client v1.0.0"
git push origin v1.1.1 portable-v1.1.1 qt-v1.0.0
```

> **tag ≠ Release**：`git push origin <tag>` 只创建标签，不会生成 Releases 页面。
> 本机没有 `gh` CLI 时，需到仓库 Releases 页面点 **Create a new release**，
> 逐个选对应 tag、填标题、贴 `releases/notes/` 里的说明正文、上传对应资产，再发布。

每个 Release 的建议写法：

- 标题：按上表，写明**通道 + 版本号**，例如「KUN-DB Qt 桌面客户端 v1.0.0」。
- 正文：直接复制 `releases/notes/<tag>.md`，并在末尾注明所需环境
  （MSI 内嵌 JRE；便携版需 Java 17+；Qt 客户端需先运行服务端）。
- 资产：只上传该发布线对应的那一个文件，文件名与上表一致。
- 历史版本可按需补建 Release；顺序按版本号倒序即可。

## 五、不对外发布的产物

| 文件 | 原因 |
|---|---|
| `KUN-DB-1.1.1-midterm.zip` | 中期检查演示包，内部产物 |
| `*.wixpdb`、`*.msi.bak`、`_jpackage.log` | 打包中间文件 |
| `build/appimage2/`、`build/runtime/`、`build/jpkg_input/` | jpackage 中间目录 |
| `kundb-data*/`、`*.log`、`*.pid` | 运行期数据，可能含真实数据 |

## 六、官网侧对应

官网仓库 `kundb-web` 的 `application.yml` 已有 `channel: MSI / PORTABLE / QT` 字段，
下载页按 channel 分组显示。发布新版本时：

- 新增一条对应 channel 的 release 条目，填好 `version` / `file` / `file-name` / `title` / `changelog`；
- `sort-order` 控制倒序展示；`release-date` 用 `YYYY-MM-DD`；
- Qt 条目的 `version` 永远填 `qt-vA.B.C` 的号，**不填内核版本**。
