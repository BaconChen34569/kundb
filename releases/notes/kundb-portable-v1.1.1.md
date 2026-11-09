# KUN-DB 便携版 v1.1.1

发布线：便携版 `kundb-portable-vX.Y.Z` | 发布日期：2026-10-17 | 资产：`KUN-DB-1.1.1-portable.zip`

> 版本号与同期的内核 / MSI 主发行一致（`1.1.1`），但这是**独立 tag 与独立 Release**。
> Qt 桌面客户端是另一条版本线，当前为 `kundb-qt-v1.0.0`，不在本 Release 内。

## 本次变更

- 与 1.1.1 安装版同一份构建，存储格式与 1.0.x / 1.1.0 兼容
- 内部代码清理（删除死代码、元数据去重），无功能变更
- 启动器同步到 1.1.1（`bat` / `sh` 均指向新版 jar）

## 适用环境

- Windows / Linux / macOS（跨平台 jar）
- 需本机已安装 Java 17 或更高版本
- 解压后运行 `bin/kundb.bat`（Linux / macOS 用 `bin/kundb.sh`）

## 注意

- 整个目录即为数据与配置，备份 / 迁移时整目录复制即可。
- 默认监听 8020，可用 `conf/kundb.ini` 或命令行参数修改端口。
- 本包不含 Qt 桌面客户端；需要 GUI 请另下载 `kundb-qt-v1.0.0`。

## 相关链接

- 主仓库：https://github.com/BaconChen34569/kundb
- 官网仓库：https://github.com/BaconChen34569/kundb-web
