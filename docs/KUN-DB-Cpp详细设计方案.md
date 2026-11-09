# KUN-DB 小型 DBMS 详细设计方案（C/C++ 底层实现版）

> 路线：C++17 全自研内核 · 周期 6–8 周 · 目标冲高分 · 客户端 = CLI + 远程客户端
> 与 Java 版的关系：**功能范围完全一致**，差异集中在内存管理、磁盘 I/O、并发原语与调试方式。

---

## 1. 为什么 C++ 版更"硬核"，代价是什么

| 维度 | C++ 版 | Java 版 |
|---|---|---|
| 页内存 | `char data_[8192]`，用 `memcpy` 直接读写字节偏移 | `ByteBuffer` / 字节数组 |
| 内存管理 | 手动，靠 RAII + 智能指针约束 | GC，无需关心 |
| 页的生命周期 | **`PageGuard` RAII 强制 unpin**，异常路径也要析构 | `try-finally` 或显式释放 |
| 并发原语 | `std::shared_mutex`（读写锁）、`std::mutex`、spinlock | `ReentrantReadWriteLock` |
| 磁盘 I/O | `pread/pwrite`（Linux）/ `ReadFile+OVERLAPPED`（Windows），需自己抽象 | `RandomAccessFile` |
| 调试成本 | ASan/UBSan/valgrind/gdb，指针错误排查耗时 | 栈信息直读，调试快 |
| 性能上限 | 高（无 GC、无对象头、可零拷贝） | 中 |
| 开发效率 | **约为 Java 的 1/2** | 基准 |

**结论**：C++ 版是"高风险高回报"路线。评分潜力最高（原理展示最充分、性能数据最好看），但 8 周内必须严格控范围。

---

## 2. 技术选型

| 环节 | 选择 | 说明 |
|---|---|---|
| 标准 | **C++17** | `std::shared_mutex`、`std::string_view`、`std::variant`、`std::optional` 都用得上；不上 C++20 保证 MSVC/GCC 兼容 |
| 构建 | **CMake 3.20+**，Presets | 一个构建脚本同时支持 GCC/Clang/MSVC |
| 编译器 | GCC 11+ / Clang 14+ / MSVC 19.3x | **强烈建议在 Linux 或 WSL2 上开发**，ASan/valgrind 齐全 |
| 测试 | **GoogleTest**（FetchContent 拉取，离线环境下沉到 `third_party/`） | 与 CTest 集成 |
| 第三方依赖 | **零运行时依赖**（JSON 自己写、日志自己写） | 答辩可强调"除测试框架外无任何依赖" |
| 网络 | 手写 BSD socket 封装：Linux `epoll` / Windows `select` 或每连接一线程 | 不用 libuv/asio，保持"全自研" |
| CLI | 自研 REPL（Linux 可 vendor 单文件 `linenoise`） | 支持历史、多行、ASCII 表格 |
| 错误处理 | 启用异常 + 自定义 `KunDBException` | 配合 RAII，异常路径自动 unpin |
| 代码规范 | clang-format + clang-tidy | 团队协作与答辩代码观感 |

### 编译选项（关键）

```cmake
# Debug：必开 sanitizer，这是 C++ 版能活下来的前提
target_compile_options(kundb PRIVATE -Wall -Wextra -Werror
                       -fsanitize=address,undefined -fno-omit-frame-pointer -g)
# Release
target_compile_options(kundb PRIVATE -O2 -DNDEBUG)
```

> ASan 会让 B+ 树测试慢 2–3 倍，但能在第一时间抓到越界/use-after-free。**不要关掉它硬扛。**

---

## 3. 工程结构

```
KUN-DB/
├── CMakeLists.txt
├── cmake/                   编译器选项、sanitizer 开关、依赖拉取
├── third_party/             googletest（vendored 可选）、linenoise
├── src/
│   ├── common/              config.h、macros.h、exception、rwlatch、hash_util、string_util
│   ├── type/                type_id、value、schema、column、tuple、comparator
│   ├── storage/
│   │   ├── disk/            disk_manager（跨平台 I/O 抽象）
│   │   ├── page/            page、page_header 宏、table_page、index_page
│   │   ├── buffer/          buffer_pool_manager、replacer（LRU/Clock）、page_guard
│   │   ├── table/           table_heap、table_iterator
│   │   ├── index/           b_plus_tree_page、b_plus_tree、generic_key
│   │   └── recovery/        log_record、log_manager、log_recovery、checkpoint
│   ├── catalog/             catalog、table_info、sys_* 元数据表
│   ├── sql/                 lexer、token、parser（递归下降）、ast/*.h
│   ├── planner/             binder、optimizer/rules、plan_node/*.h
│   ├── executor/            abstract_executor、seq_scan/…、expression 求值
│   ├── txn/                 transaction、transaction_manager、lock_manager
│   ├── access/              user、role、privilege、session
│   └── server/              socket_server、protocol（帧编解码）、remote_session
├── cli/                     main.cpp（REPL）
├── tests/                   gtest 单测 + tests/sql/*.sql 回放
└── tools/
    ├── btree_printer/       把 B+ 树 dump 成 Graphviz dot
    └── benchmark/           批量插入 / 索引对比压测
```

命名空间统一 `kundb::`。依赖方向：`cli/server → executor → planner → sql/catalog → storage → type → common`。

---

## 4. 存储引擎设计（C++ 版核心差异点）

### 4.1 Page 类与页头访问

```cpp
static constexpr uint32_t PAGE_SIZE = 8192;

class Page {
 public:
  inline char *GetData() { return data_; }
  page_id_t GetPageId() const;
  void WLatch(); void WUnlatch(); void RLatch(); void RUnlatch();

 private:
  char data_[PAGE_SIZE]{};      // 真实页数据，直接按字节操作
  page_id_t page_id_ = INVALID_PAGE_ID;
  int pin_count_ = 0;
  bool is_dirty_ = false;
  ReaderWriterLatch latch_;     // 页级 latch
};
```

页头字段**一律用宏 + `memcpy` 读写**，避免结构体对齐与大小端带来的 UB：

```cpp
// page_header.h —— Slotted Page 布局（32 字节）
#define OFFSET_PAGE_ID      0   // int32
#define OFFSET_PAGE_TYPE    4   // uint8: 0 元数据 1 数据 2 索引内部 3 索引叶子 4 位图 5 溢出
#define OFFSET_FREE_SPACE   6   // uint16 元祖区末尾偏移
#define OFFSET_SLOT_COUNT   8   // uint16
#define OFFSET_PREV_PAGE    12  // int32
#define OFFSET_NEXT_PAGE    16  // int32
#define OFFSET_PAGE_LSN     20  // int64，WAL 恢复用
#define OFFSET_CHECKSUM     28  // uint32
#define PAGE_HEADER_SIZE    32

// 槽目录从页尾向低地址增长，每槽 4 字节：offset:uint16 + length:uint16
#define SLOT_SIZE 4
inline uint32_t SlotOffset(uint32_t slot_no) {
  return PAGE_SIZE - (slot_no + 1) * SLOT_SIZE;
}
```

**必写单测**：`static_assert(sizeof(Page) 内 data_ 偏移 == 0)`、`PAGE_HEADER_SIZE == 32`，防止宏改错导致整库崩坏。

### 4.2 Tuple / Value

```cpp
class Value {
 public:
  TypeId type_;
  union { int32_t int_; int64_t bigint_; double double_; bool bool_; uint32_t date_; } v_{};
  std::string str_;            // VARCHAR 单独存，union 不放非平凡类型
  // Compare / SerializeTo / DeserializeFrom / ToString
};

class Tuple {                  // 内存态
  std::vector<Value> values_;
  RID rid_;
 public:
  void SerializeTo(char *storage) const;      // 写入页内
  void DeserializeFrom(const char *storage);  // 从页内读出
};
```

页内元组布局与 Java 版一致：`[nullBitmap: ceil(n/8) B][定长列区][变长列区]`。
注意：变长列读出来用 `std::string_view` 指向页内数据，**不要拷贝**；但页被 unpin 后指针失效——所以 `TableIterator` 必须在持有 pin 的生命周期内消费数据。

### 4.3 DiskManager（跨平台 I/O 抽象）

```cpp
class DiskManager {
 public:
  void WritePage(page_id_t page_id, const char *page_data);
  void ReadPage(page_id_t page_id, char *page_data);
 private:
  std::unordered_map<page_id_t, std::streampos> page_offsets_;
  std::fstream db_io_;
  std::mutex io_mutex_;        // 保证 seek+read 原子
};
```

- 跨平台可移植版：`std::fstream` + `seekg/seekp` + `read/write`，加互斥锁（**先做这个，稳**）
- 加分优化版：`#ifdef __linux__` 走 `pread/pwrite`（免锁、快）；`#ifdef _WIN32` 走 `ReadFile/WriteFile` + `OVERLAPPED` 传偏移
- 文件布局同 Java 版：`data/{db}/{table}.db`、`kundb.meta`、`kundb.wal`

### 4.4 BufferPoolManager + PageGuard（最重要的一节）

```cpp
class BufferPoolManager {
 public:
  bool UnpinPage(page_id_t page_id, bool is_dirty);
  bool FlushPage(page_id_t page_id);
  Page *NewPage(page_id_t *page_id);
  Page *FetchPage(page_id_t page_id);   // 内部：命中/替换/读盘/pin
 private:
  size_t pool_size_ = 1024;             // 8 MB
  Page *pages_;                         // 连续分配的帧数组
  std::unordered_map<page_id_t, frame_id_t> page_table_;
  std::unique_ptr<LRUReplacer> replacer_;
  std::list<frame_id_t> free_list_;
  std::scoped_lock / std::mutex latch_;
};
```

**PageGuard 是 C++ 版的生命线**——用 RAII 保证任何路径（包括异常）都会 unpin：

```cpp
class BasicPageGuard {
 public:
  BasicPageGuard(BasicPageGuard &&that) noexcept;             // 只能移动
  BasicPageGuard &operator=(BasicPageGuard &&that) noexcept;
  BasicPageGuard(const BasicPageGuard &) = delete;            // 禁止拷贝
  ~BasicPageGuard() { if (page_) bpm_->UnpinPage(page_->GetPageId(), is_dirty_); }
  Page *operator->() { return page_; }
 private:
  BufferPoolManager *bpm_; Page *page_; bool is_dirty_;
};
// ReadPageGuard / WritePageGuard 在其上叠加 latch
```

> 工程纪律：**代码里永远不出现裸的 `FetchPage()`/`NewPage()`**，一律返回 Guard。这条规则能消灭 90% 的缓冲池泄漏。

**刷盘约束**：`page->GetLSN() <= log_manager->GetFlushedLSN()` 才允许 `FlushPage`。

**Replacer**：先做 LRU（`std::list` + `unordered_map`，O(1)）；有余力做 **Clock / LRU-K** 并对比命中率（加分）。

### 4.5 TableHeap / TableIterator

`TableHeap` 维护 `first_page_id_`，插入时线性找有空间的页（可维护"空闲空间链表"优化）；
`TableIterator` 持有 `BasicPageGuard`，跨页时用 `OFFSET_NEXT_PAGE` 前进。

### 4.6 空闲空间与表空间（可选 P2）

- 位图页（`pageType=4`）管理页空闲状态，1 位 1 页 → 8 KB 页可管 65536 页（512 MB）
- 按**区（Extent，64 页）**分配，减少随机 I/O
- `SHOW TABLESPACES` / `SHOW EXTENTS` 作为演示命令

---

## 5. B+ 树索引（C++ 版的核心加分项）

### 5.1 节点布局

```
索引内部节点 (type=2)： [ size:u16 ][ max_size:u16 ][ parent:u32 ]
                        [ key[0..size-1]: KEY_LEN ][ value[0..size]: u32(page_id) ]
索引叶子节点 (type=3)： [ size ][ max_size ][ parent ]
                        [ key[0..size-1] ][ rid[0..size-1] ][ next_leaf:u32 ]
```

- 为避免模板爆炸，用**定长通用键** `GenericKey<32>`（32 字节缓冲区 + `KeyComparator` 按类型解释），一套代码覆盖 INT/BIGINT/DOUBLE/DATE/VARCHAR(前 32B)
- 阶数：`max_size = (8192 - 32 - 8) / (32 + 4) ≈ 225`；演示多层树时可临时调小到 8
- 需要时也可用模板 `BPlusTree<KeyType, ValueType, KeyComparator>` 显式实例化几种类型

### 5.2 并发控制：Latch Crabbing（高分亮点）

```
查/插路径：  父(读/写) → 子(读/写) → 若子节点"安全"(插入不分裂/删除不合并)则释放所有祖先锁
删除路径：   保守策略（一路加写锁，直到确认安全）
```

实现要点：
- 用 `WritePageGuard` / `ReadPageGuard` 组成 `std::vector` 作为"手上握着的锁栈"
- 插入时先乐观地全程读锁，发现会分裂再退化为写锁重跑（乐观锁耦合 vs 悲观锁耦合，二选一实现即可）
- 根节点单独用 `root_page_id_` + `root_latch_` 保护

> 实现顺序建议：先做**整树一把 `std::shared_mutex`**（正确、简单），功能跑通后再升级为 latch crabbing，并在报告里对比两种方案的 TPS。这个"演进过程"本身就是很好的答辩素材。

### 5.3 正确性验证

随机 10 万次插入/删除/查找，与 `std::map` 的结果逐条比对 + 遍历叶子链检查有序性。ASan 全开跑一遍。

---

## 6. SQL 层（与 Java 版语法一致，实现差异如下）

| 组件 | C++ 实现要点 |
|---|---|
| Lexer | `std::string_view` 切片避免拷贝；关键字 `unordered_map<string_view, TokenType>` |
| AST | `std::unique_ptr<AstNode>` 树，禁止裸指针所有权 |
| Parser | 递归下降，异常 `ParseException` 携带行列号 |
| Binder | 输出 `std::unique_ptr<AbstractPlanNode>`，同时做权限校验与视图展开 |
| Optimizer | 规则式，对 PlanNode 树做后序遍历改写；连接顺序用 DP（`dp[subset]`，N≤6） |
| Expression 求值 | `Value Evaluate(const Tuple *tuple, const Schema *schema)`，虚函数分发 |
| Executor | `bool Next(Tuple *tuple, RID *rid)` + `const Schema *GetOutputSchema()` |

```cpp
class AbstractExecutor {
 public:
  virtual void Init() = 0;
  virtual bool Next(Tuple *tuple, RID *rid) = 0;          // 火山模型，一次一行
  virtual const Schema *GetOutputSchema() = 0;
 protected:
  ExecutorContext *exec_ctx_;
};
```

`ExecutorContext` 统一持有 `Transaction* / Catalog* / BufferPoolManager* / LogManager* / LockManager*`，避免到处传参。

算子清单与 Java 版一致：
`SeqScanExecutor` `IndexScanExecutor` `FilterExecutor` `ProjectionExecutor` `NestedLoopJoinExecutor` `HashJoinExecutor` `SortMergeJoinExecutor` `HashAggregationExecutor` `SortExecutor` `LimitExecutor` `DistinctExecutor` `InsertExecutor` `UpdateExecutor` `DeleteExecutor`

---

## 7. 事务、WAL 与恢复

### 7.1 LogRecord 布局

```
[ size:u32 ][ lsn:i64 ][ txn_id:i64 ][ prev_lsn:i64 ][ type:u8 ]
[ table_oid / page_id:i32 ][ offset:u16 ][ len:u32 ]
[ before_image: len B ][ after_image: len B ]
```

`type`：`BEGIN / INSERT / UPDATE / DELETE / COMMIT / ABORT / CLR / CHECKPOINT / END`

用 `std::vector<char>` 累积后一次性 `write`，`LogManager` 起一个 `std::thread` 后台按 buffer 满或超时（如 1 ms）刷盘。

### 7.2 简版 ARIES 恢复

1. **Analysis**：从末次 `CHECKPOINT` 正向扫描，重建 `ActiveTxnTable` 与 `DirtyPageTable`
2. **Redo**：从 `DPT` 最小 `recLSN` 起重放；`if (page.GetLSN() >= record.lsn) skip;` 保证幂等
3. **Undo**：`ATT` 中未提交事务按 `prev_lsn` 逆序回滚，每步写 `CLR`（`CLR` 的 `prev_lsn` 指向同事务上一条，防重复回滚）

### 7.3 分档（与 Java 版同策略）

| 档 | 策略 | 说明 |
|---|---|---|
| **L1 保底** | Force + No-Steal | 提交时刷全部脏页，无需 Undo；日志仅作操作审计 |
| **L2 加分** | Steal + No-Force + 物理 Redo/Undo | 完整崩溃恢复 |

### 7.4 锁

- 保底：整库一把 `std::shared_mutex`（写串行、读并发）
- 加分：`LockManager` 行级锁，`unordered_map<RID, LockRequestQueue>` + `std::condition_variable` 实现等待；**2PL**（Growing/Shrinking）+ **wait-die** 防死锁 + 死锁检测线程（等待图）

---

## 8. 访问控制与视图

与 Java 版完全一致（见 Java 版方案第 9、10 节）：

- `sys_user / sys_role / sys_user_role / sys_privilege`，密码 SHA-256 + 盐
- 权限检查统一放在 **Binder 阶段**，未授权抛 `AccessDeniedException`
- 视图为**逻辑视图**：`sys_view` 存 SQL 文本，Binder 阶段解析为子查询递归展开（访问栈检测循环）

C++ 差异点：解析视图 SQL 时注意 `Parser` 非线程安全，展开过程需在会话锁内完成，或对 Parser 实例做线程局部化。

---

## 9. 远程访问（手写 socket）

```
帧格式： [ magic:2B = 0x4B55 ][ length:u32 ][ type:u8 ][ payload ]
type:    0x01 AUTH  0x02 QUERY  0x03 RESULT  0x04 ERROR  0x05 PING
payload: 自研轻量 JSON（只实现需要的编解码，约 300 行）
```

- Linux：`epoll` ET 模式 + 非阻塞 socket；Windows：`select` 或**每连接一线程**（实现快，连接数少时够用）
- Windows 需 `WSAStartup` 初始化，封装进 `SocketEnv` RAII 类
- 每连接一个 `RemoteSession`（当前库、用户、事务上下文），服务端全局 `std::shared_mutex` 保护会话表
- 客户端 CLI 加 `--host --port` 走网络，与本地模式共用一套结果渲染

**取舍建议**：如果 W8 时间紧，**远程访问的优先级低于 WAL 恢复**——"崩溃恢复"的演示冲击力远大于"能远程连"。

---

## 10. 测试与调试（C++ 版的胜负手）

| 类型 | 做法 |
|---|---|
| 单测（gtest） | 页头宏、Slot 分配回收、LRU 替换、B+ 树随机一致性、LogRecord 编解码、Lexer/Parser 边界、权限矩阵 |
| 集成测试 | `tests/sql/*.sql` + 期望输出回放，CTest 自动跑 |
| **崩溃恢复** | Linux：`fork()` 子进程插入后 `_exit(137)` 模拟 kill -9，父进程重启校验；Windows：`std::abort()` 生成 dump |
| **Fuzzing** | 随机生成 SQL 语句，ASan 下跑 1 小时——这是 C++ 版最值钱的投入 |
| 性能 | 100 万行插入耗时、全表扫描 vs 索引扫描、BufferPool 命中率；**可与 SQLite 同条件对比**（非常有说服力） |
| 内存 | Debug 全程 ASan + LSan，Release 跑 valgrind（Linux）确认零泄漏 |

---

## 11. 8 周里程碑（C++ 版，比 Java 版更紧）

| 周 | 主题 | 交付物 |
|---|---|---|
| **W1** | 基础设施 + 磁盘 + 缓冲池 | CMake 工程、DiskManager、Page + 页头宏、BufferPool + LRUReplacer、**PageGuard**、gtest 框架跑通 |
| **W2** | 元组与堆表 | Value/Tuple/Schema、TablePage（增删改查槽）、TableHeap、TableIterator、空闲空间管理、单测 |
| **W3** | 类型系统 + Catalog + DDL | 系统表（元数据也走页存储，第 0 页自举）、`CREATE/DROP DATABASE/TABLE`、`ALTER`、CLI 可执行 DDL |
| **W4** | SQL 解析 | Lexer、Token、递归下降 Parser、AST、错误定位；单测覆盖全部语法产生式 |
| **W5** | Binder + 执行器 + DML | 语义分析、火山模型算子（SeqScan/Filter/Projection/Insert/Update/Delete）、CLI v1 能跑增删改查 |
| **W6** | 连接 + 优化器 + 聚合 | NLJ / HashJoin、多表连接顺序 DP、谓词下推、GROUP BY / ORDER BY / LIMIT |
| **W7** | B+ 树索引 | BPlusTree 插入/删除/范围扫描、唯一索引、IndexScanExecutor、优化器索引选择、`EXPLAIN`；**整树锁 → 可选升级 latch crabbing** |
| **W8** | 弹性收尾（按优先级） | ① 权限 + 视图（必选，必须做）② WAL + 崩溃恢复（L1 保底 → L2 加分）③ 远程访问 ④ 测试报告 |

> **诚实的提醒**：C++ 版估工约 46 人日，8 周若为业余投入会比较紧张。
> **建议的砍法**：必选 > 索引 > WAL(L1) > 权限视图 > 多表优化 > WAL(L2) > 远程 > 表空间。
> 若到 W7 末发现超期，**直接把远程访问和 WAL-L2 砍掉**，保证权限/视图/索引完整，分数不会差。

---

## 12. 风险与对策（C++ 专属）

| 风险 | 影响 | 对策 |
|---|---|---|
| 页内越界写 / use-after-free | 数据静默损坏，最难查 | **全程 ASan + UBSan**；所有页操作走 `memcpy` + 宏；单测覆盖边界 |
| 忘记 unpin → 缓冲池耗尽 | 系统卡死 | 强制 PageGuard，禁止裸 FetchPage；缓冲池满时抛异常并打印持有栈 |
| 字节对齐 / 大小端 | 换机器数据就废 | 不用结构体强转读页头，只用 `memcpy`；声明"仅支持同机小端" |
| 跨平台 I/O | Windows 上跑不起来 | DiskManager 抽象，先 fstream 保底，再加 pread/ReadFile 优化分支 |
| 页锁与事务锁混淆（latch vs lock） | 死锁难复现 | 严格约定：**latch 保护内存结构（临界区短、不加事务逻辑），lock 保护数据（事务级）** |
| B+ 树删除合并 bug | 索引结果错 | 先只分裂不合并；与 `std::map` 做 10 万次随机对拍 |
| 开发效率只有 Java 一半 | 8 周做不完 | 严格按第 11 节砍优先级；W1 就把 CI/单测/ASan 搭好，避免后期返工 |

---

## 13. 答辩加分点（C++ 版专属）

- **崩溃恢复现场演示**：`kill -9` 进程 → 重启 → 数据自愈（最有冲击力）
- **性能对比图**：与 SQLite 同条件对比 100 万行插入/查询耗时
- **B+ 树可视化**：`tools/btree_printer` 输出 Graphviz dot，直接贴图到报告
- **缓冲池命中率对比**：LRU vs Clock（或 LRU-K）曲线图
- **锁粒度演进**：整树锁 → latch crabbing 的 TPS 对比
- **ASan 零告警截图**：证明内存安全工程实践
- **零第三方运行时依赖**：依赖树只有 googletest（且仅测试用）
- **EXPLAIN 优化前后算子树对比**
