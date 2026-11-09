# 第三方组件与许可声明

本仓库只包含自研源码；下列组件通过 Maven / CMake / 系统工具链在构建时引入，
**不随本仓库分发二进制**。使用发布的二进制包时，请同时遵守各组件许可。

| 组件 | 版本 | 用途 | 许可证 |
|---|---|---|---|
| Eclipse Netty (`netty-all`) | 4.1.115.Final | TCP 服务端、MySQL 线协议帧收发 | Apache-2.0 |
| Jackson (`jackson-databind`) | 2.17.2 | JSON 持久化读写 | Apache-2.0 |
| JUnit Jupiter | 5.10.2 | 单元测试（仅 test scope） | EPL-2.0 |
| Qt 6（Core / Gui / Network / Widgets） | 6.x | 桌面 GUI 客户端 | LGPL-3.0 / GPL-2.0 / GPL-3.0（多选一） |
| MySQL Connector/J | 9.1.0 | 本地互操作对照测试，**未随仓库分发** | GPL-2.0 with FOSS Exception |
| Maven / JDK 17 / jpackage | — | 构建与打包工具 | 各自许可 |
| WiX Toolset 3 | — | 生成 Windows MSI 安装包（构建时工具） | Microsoft Reciprocal License |

## 说明

- **SQL 与存储引擎完全自研**：SQL 解析、执行、优化、事务、索引、WAL、表空间均由本项目实现，
  未引入任何第三方 SQL 解析器、优化器或数据库引擎。Netty 只承担网络传输。
- **协议子集**：仅实现 MySQL 线协议子集（v10 握手、`mysql_native_password`、
  COM_QUERY / COM_STMT_* 的部分命令与文本、二进制结果集），目的是让常见客户端能连上，
  **不是** MySQL 兼容实现。
- **Qt 客户端**：以动态链接方式使用 Qt 6，遵循 LGPL-3.0 的相关要求；
  独立分发的 ZIP 内含 Qt 运行时 DLL，其许可与源码获取途径见 Qt 官方说明。
- **安装包**：MSI / 便携版 ZIP / Qt 客户端 ZIP 通过 GitHub Releases 发布，
  不纳入 Git 历史。发布规则与版本线见 [RELEASES.md](RELEASES.md)。
- **官网**：`kundb-web` 为独立仓库（Spring Boot 3.2 / Vue 3 / Vite / MySQL 8），
  不在本仓库内，其依赖另见该仓库说明。
