package com.kundb.server.engine;

import com.kundb.common.protocol.ErrorCode;
import com.kundb.server.JsonUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 表空间管理器 —— 课程《开发要求》可选功能「表空间管理」的实现。
 *
 * <p><b>为什么要有它：</b>如果每张表各自随便往磁盘上写文件，磁盘空间很快会碎片化、也无法统一管理容量。
 * 表空间就是"一个逻辑的磁盘容器"：一个表空间对应磁盘上一个目录，里面放若干<b>数据文件</b>，
 * 建表时指定表放在哪个表空间，表的增长由表空间统一按<b>区（extent）</b>分配。</p>
 *
 * <p>本实现的物理组织（与主流 DBMS 的页/区概念一致）：</p>
 * <ul>
 *   <li><b>页 Page</b>：最小 IO 单位，16KB；</li>
 *   <li><b>区 Extent</b>：连续 16 个页 = 256KB，是<b>一次分配给表的最小单位</b>（避免一行一行要空间）；</li>
 *   <li><b>数据文件</b>：表空间目录下的 {@code <库>.<表>.kdb}，创建时<b>预分配</b>到整数个区的大小，
 *       数据写满后按区<b>自动扩展</b>（真实 {@code setLength}，磁盘上可见）；</li>
 *   <li>统计口径取自<b>真实序列化字节数</b>，不是拍脑袋估算。</li>
 * </ul>
 */
public class TablespaceManager {

    public static final int PAGE_SIZE = 16 * 1024;                 // 页 16KB
    public static final int PAGES_PER_EXTENT = 16;                 // 每区 16 页
    public static final int EXTENT_SIZE = PAGE_SIZE * PAGES_PER_EXTENT; // 区 256KB
    public static final String DEFAULT_TS = "kundspace";

    public static final class Ts {
        final String name;
        final Path dir;
        boolean autoExtend = true;
        long initialSize = EXTENT_SIZE;
        long createdAt = System.currentTimeMillis();
        long extendCount = 0;   // 自动/手动扩展次数

        Ts(String name, Path dir) {
            this.name = name;
            this.dir = dir;
        }
    }

    private final Path root;             // kundb-data/tablespaces
    private final Path metaPath;         // kundb-data/tablespaces.json
    private final Map<String, Ts> spaces = new LinkedHashMap<>();
    private final Map<String, String> tableOwner = new LinkedHashMap<>(); // "db.table" -> 表空间名
    private final Map<String, Long> allocated = new LinkedHashMap<>();    // "db.table" -> 已分配字节
    private final Map<String, Long> used = new LinkedHashMap<>();         // "db.table" -> 实际数据字节

    public TablespaceManager(Path dataRoot) {
        this.root = dataRoot.resolve("tablespaces");
        this.metaPath = dataRoot.resolve("tablespaces.json");
    }

    // ===================== 加载 / 持久化 =====================

    @SuppressWarnings("unchecked")
    public void load() throws EngineException {
        try {
            Files.createDirectories(root);
            if (Files.exists(metaPath)) {
                Map<String, Object> obj = JsonUtils.mapper().readValue(metaPath.toFile(), Map.class);
                List<Map<String, Object>> tsList = (List<Map<String, Object>>) obj.get("tablespaces");
                if (tsList != null) {
                    for (Map<String, Object> m : tsList) {
                        String name = (String) m.get("name");
                        Ts ts = new Ts(name, root.resolve(name));
                        ts.autoExtend = m.get("autoExtend") == null || Boolean.TRUE.equals(m.get("autoExtend"));
                        ts.initialSize = m.get("initialSize") == null ? EXTENT_SIZE
                                : ((Number) m.get("initialSize")).longValue();
                        ts.createdAt = m.get("createdAt") == null ? System.currentTimeMillis()
                                : ((Number) m.get("createdAt")).longValue();
                        ts.extendCount = m.get("extendCount") == null ? 0 : ((Number) m.get("extendCount")).longValue();
                        spaces.put(name, ts);
                        Files.createDirectories(ts.dir);
                    }
                }
                Map<String, Object> tbl = (Map<String, Object>) obj.get("tables");
                if (tbl != null) {
                    for (Map.Entry<String, Object> e : tbl.entrySet()) {
                        tableOwner.put(e.getKey(), (String) e.getValue());
                    }
                }
                Map<String, Object> al = (Map<String, Object>) obj.get("allocated");
                if (al != null) {
                    for (Map.Entry<String, Object> e : al.entrySet()) {
                        allocated.put(e.getKey(), ((Number) e.getValue()).longValue());
                    }
                }
                Map<String, Object> us = (Map<String, Object>) obj.get("used");
                if (us != null) {
                    for (Map.Entry<String, Object> e : us.entrySet()) {
                        used.put(e.getKey(), ((Number) e.getValue()).longValue());
                    }
                }
                // 兼容旧版本（只有 allocations 估算值）
                Map<String, Object> legacy = (Map<String, Object>) obj.get("allocations");
                if (legacy != null) {
                    for (Map.Entry<String, Object> e : legacy.entrySet()) {
                        used.putIfAbsent(e.getKey(), ((Number) e.getValue()).longValue());
                        tableOwner.putIfAbsent(e.getKey(), DEFAULT_TS);
                    }
                }
            }
            ensure(DEFAULT_TS);
        } catch (IOException e) {
            throw new EngineException(ErrorCode.SERVER_ERROR, "加载表空间失败: " + e.getMessage());
        }
    }

    private synchronized void persist() {
        try {
            Map<String, Object> obj = new LinkedHashMap<>();
            List<Map<String, Object>> tsList = new ArrayList<>();
            for (Ts ts : spaces.values()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", ts.name);
                m.put("autoExtend", ts.autoExtend);
                m.put("initialSize", ts.initialSize);
                m.put("createdAt", ts.createdAt);
                m.put("extendCount", ts.extendCount);
                tsList.add(m);
            }
            obj.put("tablespaces", tsList);
            obj.put("tables", tableOwner);
            obj.put("allocated", allocated);
            obj.put("used", used);
            Files.write(metaPath, JsonUtils.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(obj));
        } catch (IOException ignore) {
            // 元数据写入失败不阻断主流程
        }
    }

    private Ts ensure(String name) throws IOException {
        Ts ts = spaces.get(name);
        if (ts == null) {
            Path dir = root.resolve(name);
            Files.createDirectories(dir);
            ts = new Ts(name, dir);
            spaces.put(name, ts);
            persist();
        }
        return ts;
    }

    // ===================== DDL =====================

    public synchronized void create(String name, long initialSize, boolean autoExtend) throws EngineException {
        if (spaces.containsKey(name)) {
            throw new EngineException(ErrorCode.SERVER_ERROR, "表空间已存在: " + name);
        }
        try {
            Ts ts = ensure(name);
            ts.initialSize = Math.max(EXTENT_SIZE, roundUp(initialSize <= 0 ? EXTENT_SIZE : initialSize));
            ts.autoExtend = autoExtend;
            persist();
        } catch (IOException e) {
            throw new EngineException(ErrorCode.SERVER_ERROR, "创建表空间失败: " + e.getMessage());
        }
    }

    public synchronized void drop(String name) throws EngineException {
        if (DEFAULT_TS.equals(name)) {
            throw new EngineException(ErrorCode.SERVER_ERROR, "默认表空间 " + DEFAULT_TS + " 不可删除");
        }
        Ts ts = spaces.get(name);
        if (ts == null) {
            throw new EngineException(ErrorCode.SERVER_ERROR, "表空间不存在: " + name);
        }
        for (Map.Entry<String, String> e : tableOwner.entrySet()) {
            if (name.equals(e.getValue())) {
                throw new EngineException(ErrorCode.SERVER_ERROR,
                        "表空间 " + name + " 仍被表占用: " + e.getKey() + "，请先删除或迁移该表");
            }
        }
        try {
            if (Files.exists(ts.dir)) {
                try (var s = Files.walk(ts.dir)) {
                    List<Path> all = new ArrayList<>();
                    s.forEach(all::add);
                    all.sort((a, b) -> b.compareTo(a));
                    for (Path p : all) {
                        Files.deleteIfExists(p);
                    }
                }
            }
            spaces.remove(name);
            persist();
        } catch (IOException e) {
            throw new EngineException(ErrorCode.SERVER_ERROR, "删除表空间失败: " + e.getMessage());
        }
    }

    /** 把表登记到表空间（建表 / 迁移时调用）。 */
    public synchronized void assign(String dbTable, String tsName) throws EngineException {
        Ts ts = spaces.get(tsName);
        if (ts == null) {
            throw new EngineException(ErrorCode.SERVER_ERROR, "表空间不存在: " + tsName);
        }
        tableOwner.put(dbTable, tsName);
        allocated.putIfAbsent(dbTable, ts.initialSize);
        persist();
    }

    public synchronized void release(String dbTable) {
        String tsName = tableOwner.remove(dbTable);
        allocated.remove(dbTable);
        used.remove(dbTable);
        if (tsName != null) {
            Ts ts = spaces.get(tsName);
            if (ts != null) {
                int dot = dbTable.indexOf('.');
                try {
                    Files.deleteIfExists(ts.dir.resolve(dataFileName(dbTable.substring(0, dot),
                            dbTable.substring(dot + 1))));
                } catch (Exception ignore) {
                    // 删数据文件失败不影响内存状态
                }
            }
        }
        persist();
    }

    public String tablespaceOf(String dbTable) {
        return tableOwner.getOrDefault(dbTable, DEFAULT_TS);
    }

    public boolean exists(String name) {
        return spaces.containsKey(name);
    }

    // ===================== 数据文件写入与扩展 =====================

    private static String dataFileName(String db, String table) {
        return db + "." + table + ".kdb";
    }

    private static long roundUp(long bytes) {
        return ((bytes + EXTENT_SIZE - 1) / EXTENT_SIZE) * EXTENT_SIZE;
    }

    /**
     * 返回表在其所属表空间中的<b>数据文件</b>路径（即三段式记录文件 {@link RecordFileStore} 的载体）。
     * 文件不存在时按"整数个区"预分配创建 —— 这就是要求里的「文件预分配」。
     */
    public synchronized Path fileOf(String db, String table) throws EngineException {
        String key = db + "." + table;
        Ts ts = spaces.get(tableOwner.getOrDefault(key, DEFAULT_TS));
        if (ts == null) {
            tableOwner.put(key, DEFAULT_TS);
            ts = spaces.get(DEFAULT_TS);
        }
        if (ts == null) {
            throw new EngineException(ErrorCode.SERVER_ERROR, "默认表空间不存在");
        }
        try {
            Files.createDirectories(ts.dir);
            Path f = ts.dir.resolve(dataFileName(db, table));
            Long allocObj = allocated.get(key);
            long alloc = allocObj == null ? ts.initialSize : allocObj;
            if (allocObj == null) {
                allocated.put(key, alloc);
            }
            if (!Files.exists(f)) {
                try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f.toFile(), "rw")) {
                    raf.setLength(alloc);   // 预分配到整数个区，磁盘上立刻可见
                }
            }
            persist();
            return f;
        } catch (IOException e) {
            throw new EngineException(ErrorCode.SERVER_ERROR, "创建数据文件失败: " + e.getMessage());
        }
    }

    /** 数据文件已存在则返回其路径，否则返回 null（加载阶段用，避免凭空创建文件）。 */
    public synchronized Path fileOfIfExists(String db, String table) {
        String key = db + "." + table;
        Ts ts = spaces.get(tableOwner.getOrDefault(key, DEFAULT_TS));
        if (ts == null) {
            return null;
        }
        Path f = ts.dir.resolve(dataFileName(db, table));
        return Files.exists(f) ? f : null;
    }

    /**
     * 汇报该表数据文件当前的真实占用字节数，必要时按区<b>自动扩展</b>（真实 setLength，磁盘可见）。
     *
     * @return 本次是否发生了扩展
     */
    public synchronized boolean reportUsed(String db, String table, long usedBytes) throws EngineException {
        String key = db + "." + table;
        used.put(key, usedBytes);
        long alloc = allocated.getOrDefault(key, 0L);
        String tsName = tableOwner.getOrDefault(key, DEFAULT_TS);
        Ts ts = spaces.get(tsName);
        if (alloc <= 0) {
            alloc = ts == null ? EXTENT_SIZE : ts.initialSize;
        }
        boolean extended = false;
        long target = alloc;
        if (usedBytes > alloc) {
            if (ts != null && !ts.autoExtend) {
                throw new EngineException(ErrorCode.SERVER_ERROR,
                        "表空间 " + tsName + " 中 " + key + " 空间不足（已分配 " + alloc + " 字节，需要 " + usedBytes
                                + "），且该表空间未开启自动扩展");
            }
            target = roundUp(usedBytes);
            long extents = Math.max(1, (target - Math.max(alloc, 0L)) / EXTENT_SIZE);
            if (ts != null) {
                ts.extendCount += extents;
            }
            extended = true;
        }
        allocated.put(key, target);
        try {
            Path f = fileOf(db, table);
            if (Files.size(f) < target) {
                // 两条路都会走到这里：① 真的需要扩展；② 记录文件重写后变小了，
                // 要把它重新撑回"整数个区"的预分配大小（预分配不能因为重写而丢失）。
                try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f.toFile(), "rw")) {
                    raf.setLength(target);
                }
            }
        } catch (IOException e) {
            throw new EngineException(ErrorCode.SERVER_ERROR, "扩展数据文件失败: " + e.getMessage());
        }
        persist();
        return extended;
    }

    /** 手动扩展：给指定表（或整个表空间）追加若干区。 */
    public synchronized long extend(String tsName, String dbTable, long extents) throws EngineException {
        Ts ts = spaces.get(tsName);
        if (ts == null) {
            throw new EngineException(ErrorCode.SERVER_ERROR, "表空间不存在: " + tsName);
        }
        long add = Math.max(1, extents) * EXTENT_SIZE;
        ts.extendCount += Math.max(1, extents);
        if (dbTable != null && !dbTable.isBlank()) {
            long cur = allocated.getOrDefault(dbTable, ts.initialSize);
            allocated.put(dbTable, cur + add);
        }
        for (Map.Entry<String, String> e : tableOwner.entrySet()) {
            if (tsName.equals(e.getValue())) {
                try {
                    int dot = e.getKey().indexOf('.');
                    Path f = ts.dir.resolve(dataFileName(e.getKey().substring(0, dot), e.getKey().substring(dot + 1)));
                    if (Files.exists(f)) {
                        long want = allocated.getOrDefault(e.getKey(), Files.size(f)) + add;
                        try (var raf = new java.io.RandomAccessFile(f.toFile(), "rw")) {
                            raf.setLength(want);   // 真实扩展文件长度
                        }
                        allocated.put(e.getKey(), want);
                    }
                } catch (IOException ignore) {
                    // 单个文件扩展失败不影响其他文件
                }
            }
        }
        persist();
        return add;
    }

    // ===================== 展示 =====================

    /** SHOW TABLESPACES：每个表空间一行。 */
    public synchronized List<List<Object>> rows() {
        List<List<Object>> out = new ArrayList<>();
        for (Ts ts : spaces.values()) {
            long files = 0;
            long alloc = 0;
            long usedBytes = 0;
            for (Map.Entry<String, String> e : tableOwner.entrySet()) {
                if (!ts.name.equals(e.getValue())) {
                    continue;
                }
                files++;
                alloc += allocated.getOrDefault(e.getKey(), 0L);
                usedBytes += used.getOrDefault(e.getKey(), 0L);
            }
            double pct = alloc == 0 ? 0.0 : Math.round(usedBytes * 1000.0 / alloc) / 10.0;
            out.add(Arrays.asList(ts.name, ts.dir.toString(), files,
                    (long) EXTENT_SIZE, alloc, usedBytes, pct,
                    ts.autoExtend ? "ON" : "OFF", ts.extendCount));
        }
        return out;
    }

    /** SHOW TABLESPACE 〈名〉：该表空间内的数据文件清单。 */
    public synchronized List<List<Object>> fileRows(String tsName) throws EngineException {
        Ts ts = spaces.get(tsName);
        if (ts == null) {
            throw new EngineException(ErrorCode.SERVER_ERROR, "表空间不存在: " + tsName);
        }
        List<List<Object>> out = new ArrayList<>();
        for (Map.Entry<String, String> e : tableOwner.entrySet()) {
            if (!tsName.equals(e.getValue())) {
                continue;
            }
            int dot = e.getKey().indexOf('.');
            long u = used.getOrDefault(e.getKey(), 0L);
            long a = allocated.getOrDefault(e.getKey(), 0L);
            out.add(Arrays.asList(e.getKey().substring(0, dot), e.getKey().substring(dot + 1),
                    dataFileName(e.getKey().substring(0, dot), e.getKey().substring(dot + 1)),
                    u, a, a / EXTENT_SIZE, (u + PAGE_SIZE - 1) / PAGE_SIZE));
        }
        return out;
    }

    /** 单表用量（供 SHOW TABLE STATUS 等扩展使用）。 */
    public synchronized long usedBytes(String dbTable) {
        return used.getOrDefault(dbTable, 0L);
    }

    public synchronized long allocatedBytes(String dbTable) {
        return allocated.getOrDefault(dbTable, 0L);
    }

    public static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
    }

    /** 数据文件在磁盘上的真实大小（用于校验"预分配/扩展"确实生效）。 */
    public synchronized long realFileSize(String db, String table) {
        String key = db + "." + table;
        Ts ts = spaces.get(tableOwner.getOrDefault(key, DEFAULT_TS));
        if (ts == null) {
            return 0;
        }
        Path f = ts.dir.resolve(dataFileName(db, table));
        try {
            return Files.exists(f) ? Files.size(f) : 0;
        } catch (IOException e) {
            return 0;
        }
    }
}
