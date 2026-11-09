# KUN-DB Qt 桌面客户端 v1.0.0

发布线：Qt 客户端 `kundb-qt-vA.B.C`（**独立版本线**） | 发布日期：2026-09-23 | 资产：`KUN-DB-QtClient-1.0.0.zip`

> **版本号说明**：Qt 客户端只有自身代码变化才升版，因此当前是 `1.0.0`，
> 与内核 / 便携版的 `1.1.1` 并不矛盾，也不是漏更新。服务端请另从
> `kundb-v1.1.1`（MSI）或 `kundb-portable-v1.1.1`（便携版）下载。

## 本次内容

- 独立 Qt 桌面客户端：安装向导里未勾选「Qt 客户端」的用户可单独下载，用完即走
- 自带全部 Qt 运行时（Qt6Core / Gui / Network / Widgets、MinGW 三件套）
  与 `platforms/qwindows.dll`，无需额外安装 Qt 或运行库
- 解压即用：双击 `kundb-qt.exe`，连接本机已运行的 KUN-DB 服务端（默认 8020）
- 与 MSI 内置的 Qt 客户端功能完全一致

## 适用环境

- Windows 10 / 11 x64
- 需先安装并运行 KUN-DB 服务端（MSI 或便携版）

## 注意

- 本 ZIP 含 Qt 运行时 DLL，其使用遵循 Qt 的 LGPL / GPL 许可，详见主仓库 `NOTICE.md`。
- 客户端通过 MySQL 线协议子集连接服务端；服务端版本建议使用 `1.1.1` 或更高。

## 相关链接

- 主仓库：https://github.com/BaconChen34569/kundb
- 官网仓库：https://github.com/BaconChen34569/kundb-web
