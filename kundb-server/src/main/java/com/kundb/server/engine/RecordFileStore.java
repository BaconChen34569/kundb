package com.kundb.server.engine;

import com.kundb.server.JsonUtils;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 三段式定长槽记录文件 —— 课程《开发要求》附加功能 1「优化数据库文件管理」的物理实现。
 *
 * <p><b>为什么原来是"整文件重写"不行：</b>一张表只要改一行，就得把整张表序列化一遍再整体覆盖写回磁盘。
 * 表一大，写放大极其严重；而且删除一行会让它后面的所有行在文件里整体前移，谁的位置都变了。
 * 本课程要求的是另一套思路：<b>把文件切成固定大小的"槽"（slot），一行占一个槽，槽号就是这一行的永久住址</b>。
 * 这样删一行只需要在"分配表"上打个叉，改一行只需要覆写它自己那个槽，插一行还可以直接住进被删掉留下的空位。</p>
 *
 * <p><b>文件布局（三段，与要求中的建议结构一一对应）：</b></p>
 * <pre>
 *  ┌───────────────────────────────────────────────────────────┐
 *  │ 段1 格式信息区 HEADER    固定 512 字节（JSON + 空格填充）  │
 *  │    magic / version / 槽大小 / 槽总数 / 有效记录数 /        │
 *  │    第一个记录位置 / 最后一个记录位置 / 空位数 /           │
 *  │    headerSize / bitmapOffset / bitmapSize / dataOffset /  │
 *  │    dataSize / fileSize / 创建与更新时间                   │
 *  ├───────────────────────────────────────────────────────────┤
 *  │ 段2 记录空间分配表 BITMAP 每槽 1 字节（按 64 字节对齐）   │
 *  │    '0' = 空槽（从未使用）                                 │
 *  │    '1' = 有数据                                           │
 *  │    '2' = 已被删除标记（槽内容仍在，位置可复用）           │
 *  ├───────────────────────────────────────────────────────────┤
 *  │ 段3 记录存储空间 DATA    槽总数 × 槽大小 字节             │
 *  │    每槽定长，行内容（紧凑 JSON 数组）不足则补空格         │
 *  └───────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>三种 DML 在这套结构上的落盘动作（也正是要求里的"记录随机读写策略"）：</p>
 * <ul>
 *   <li><b>插入</b>：在分配表里找第一个空位（优先复用 '2'），只写那一个槽 + 那一个标记字节；</li>
 *   <li><b>更新</b>：行还在同一个槽里，只覆写该槽的字节，<b>原地修改</b>，其他字节一概不动；</li>
 *   <li><b>删除</b>：只把分配表上那一格从 '1' 改成 '2'，<b>数据区一个字节都不动</b>（所以能看到"删了但内容还在"）。</li>
 * </ul>
 */
public final class RecordFileStore {

    public static final String MAGIC = "KUNDBF01";
    public static final int VERSION = 1;

    /** 段1 格式信息区固定大小。 */
    public static final int HEADER_SIZE = 512;
    /** 段2 分配表的对齐单位。 */
    public static final int SLOT_ALIGN = 64;

    /** 槽状态：空槽。 */
    public static final char SLOT_EMPTY = '0';
    /** 槽状态：有数据。 */
    public static final char SLOT_USED = '1';
    /** 槽状态：已被删除标记（内容仍在，位置可复用）。 */
    public static final char SLOT_DELETED = '2';

    /** 建表时默认预分配的槽数。 */
    public static final int DEFAULT_SLOTS = 256;
    public static final int MIN_SLOT_SIZE = 128;
    public static final int MAX_SLOT_SIZE = 8192;

    private RecordFileStore() {
    }

    /** 段1：格式信息区。 */
    public static final class Header {
        public String magic = MAGIC;
        public int version = VERSION;
        public int slotSize = 256;        // 每个记录位置的字节数
        public int slotCount = DEFAULT_SLOTS; // 记录位置总数
        public int recordCount = 0;       // 有效记录数（槽状态为 '1' 的个数）
        public int firstRecord = -1;      // 第一个有效记录所在的槽号
        public int lastRecord = -1;       // 最后一个有效记录所在的槽号
        public int freeCount = 0;         // 可复用的记录位置数（'0' 与 '2' 合计）
        public int headerSize = HEADER_SIZE;
        public int bitmapOffset = HEADER_SIZE;
        public int bitmapSize = DEFAULT_SLOTS;
        public long dataOffset = HEADER_SIZE + DEFAULT_SLOTS;
        public long dataSize = (long) DEFAULT_SLOTS * 256;
        public long fileSize = 0;
        public long createdAt = System.currentTimeMillis();
        public long updatedAt = System.currentTimeMillis();

        /** 根据 slotSize / slotCount 重算三段各自的偏移与总文件大小。 */
        public void recompute() {
            bitmapSize = align(slotCount);
            dataOffset = (long) headerSize + bitmapSize;
            dataSize = (long) slotCount * slotSize;
            fileSize = dataOffset + dataSize;
        }
    }

    static int align(int n) {
        return ((n + SLOT_ALIGN - 1) / SLOT_ALIGN) * SLOT_ALIGN;
    }

    // ===================== 段1：格式信息区 =====================

    /** 读取格式信息区；文件不存在 / 太短 / magic 不匹配（例如旧格式的整表 JSON）时返回 null。 */
    @SuppressWarnings("unchecked")
    public static Header readHeader(Path f) throws IOException {
        if (f == null || !Files.exists(f) || Files.size(f) < HEADER_SIZE) {
            return null;
        }
        byte[] buf = new byte[HEADER_SIZE];
        try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "r")) {
            raf.readFully(buf);
        }
        String txt = new String(buf, StandardCharsets.UTF_8).trim();
        if (txt.isEmpty() || txt.charAt(0) != '{') {
            return null;
        }
        Map<String, Object> m;
        try {
            m = JsonUtils.mapper().readValue(txt, Map.class);
        } catch (Exception e) {
            return null;
        }
        if (!MAGIC.equals(m.get("magic"))) {
            return null;
        }
        Header h = new Header();
        h.slotSize = intOf(m.get("slotSize"), 256);
        h.slotCount = intOf(m.get("slotCount"), DEFAULT_SLOTS);
        h.recordCount = intOf(m.get("recordCount"), 0);
        h.firstRecord = intOf(m.get("firstRecord"), -1);
        h.lastRecord = intOf(m.get("lastRecord"), -1);
        h.freeCount = intOf(m.get("freeCount"), 0);
        h.headerSize = intOf(m.get("headerSize"), HEADER_SIZE);
        h.bitmapOffset = intOf(m.get("bitmapOffset"), HEADER_SIZE);
        h.bitmapSize = intOf(m.get("bitmapSize"), h.slotCount);
        h.dataOffset = longOf(m.get("dataOffset"), (long) h.headerSize + h.bitmapSize);
        h.dataSize = longOf(m.get("dataSize"), (long) h.slotCount * h.slotSize);
        h.fileSize = longOf(m.get("fileSize"), h.dataOffset + h.dataSize);
        h.createdAt = longOf(m.get("createdAt"), System.currentTimeMillis());
        h.updatedAt = longOf(m.get("updatedAt"), System.currentTimeMillis());
        return h;
    }

    public static void writeHeader(Path f, Header h) throws IOException {
        h.updatedAt = System.currentTimeMillis();
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("magic", h.magic);
        m.put("version", h.version);
        m.put("slotSize", h.slotSize);
        m.put("slotCount", h.slotCount);
        m.put("recordCount", h.recordCount);
        m.put("firstRecord", h.firstRecord);
        m.put("lastRecord", h.lastRecord);
        m.put("freeCount", h.freeCount);
        m.put("headerSize", h.headerSize);
        m.put("bitmapOffset", h.bitmapOffset);
        m.put("bitmapSize", h.bitmapSize);
        m.put("dataOffset", h.dataOffset);
        m.put("dataSize", h.dataSize);
        m.put("fileSize", h.fileSize);
        m.put("createdAt", h.createdAt);
        m.put("updatedAt", h.updatedAt);
        byte[] json = JsonUtils.mapper().writeValueAsBytes(m);
        if (json.length > HEADER_SIZE) {
            throw new IOException("格式信息区超出 " + HEADER_SIZE + " 字节");
        }
        byte[] block = new byte[HEADER_SIZE];
        Arrays.fill(block, (byte) ' ');
        System.arraycopy(json, 0, block, 0, json.length);
        try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "rw")) {
            raf.seek(0);
            raf.write(block);
        }
    }

    // ===================== 段2：记录空间分配表 =====================

    /** 读整张分配表；长度不足的部分按空槽处理。 */
    public static char[] readBitmap(Path f, Header h) throws IOException {
        char[] out = new char[h.slotCount];
        Arrays.fill(out, SLOT_EMPTY);
        try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "r")) {
            raf.seek(h.bitmapOffset);
            byte[] buf = new byte[h.bitmapSize];
            int n = raf.read(buf);
            int len = Math.min(out.length, n < 0 ? 0 : n);
            for (int i = 0; i < len; i++) {
                char c = (char) buf[i];
                out[i] = (c == SLOT_USED || c == SLOT_DELETED) ? c : SLOT_EMPTY;
            }
        }
        return out;
    }

    /** 只改一个槽的状态字节 —— 删除操作落盘时唯一需要写的东西（数据区一个字节都不碰）。 */
    public static void writeFlag(Path f, Header h, int slot, char flag) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "rw")) {
            raf.seek(h.bitmapOffset + (long) slot);
            raf.write((byte) flag);
        }
    }

    // ===================== 段3：记录存储空间 =====================

    /** 读第 slot 个槽的内容（去掉尾部填充空格）。 */
    public static String readSlotText(Path f, Header h, int slot) throws IOException {
        byte[] buf = new byte[h.slotSize];
        try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "r")) {
            raf.seek(h.dataOffset + (long) slot * h.slotSize);
            raf.readFully(buf);
        }
        return new String(buf, StandardCharsets.UTF_8).trim();
    }

    /**
     * 覆写第 slot 个槽 —— 更新操作就是"原地修改"：只写这一条记录占的字节，
     * 同一张表里其他行的字节、以及分配表，都不需要动。
     */
    public static void writeSlot(Path f, Header h, int slot, byte[] payload) throws IOException {
        if (payload.length > h.slotSize) {
            throw new IOException("记录长度 " + payload.length + " 超出槽大小 " + h.slotSize);
        }
        byte[] block = new byte[h.slotSize];
        Arrays.fill(block, (byte) ' ');
        System.arraycopy(payload, 0, block, 0, payload.length);
        try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "rw")) {
            raf.seek(h.dataOffset + (long) slot * h.slotSize);
            raf.write(block);
        }
    }

    // ===================== 整文件创建与重写 =====================

    /** 新建一个三段式文件：写格式信息区 + 全空的分配表 + 全空格的记录区（即"文件预分配"）。 */
    public static Header create(Path f, int slotSize, int slotCount) throws IOException {
        Header h = new Header();
        h.slotSize = Math.max(MIN_SLOT_SIZE, slotSize);
        h.slotCount = Math.max(DEFAULT_SLOTS, slotCount);
        h.recompute();
        char[] flags = new char[h.slotCount];
        Arrays.fill(flags, SLOT_EMPTY);
        byte[][] payloads = new byte[h.slotCount][];
        rewrite(f, h, flags, payloads);
        return h;
    }

    /**
     * 全量重写三段内容。仅在建表、槽数扩展（分配表变大、数据区要整体后移）、
     * 槽大小扩容、以及 OPTIMIZE TABLE 整理碎片时调用；日常 DML 走随机写。
     */
    public static void rewrite(Path f, Header h, char[] flags, byte[][] payloads) throws IOException {
        h.recompute();
        if (f.getParent() != null) {
            Files.createDirectories(f.getParent());
        }
        try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "rw")) {
            raf.setLength(h.fileSize);           // 预分配：文件在磁盘上真实占这么大
            // 段2 分配表
            byte[] bitmap = new byte[h.bitmapSize];
            Arrays.fill(bitmap, (byte) SLOT_EMPTY);
            for (int i = 0; i < h.slotCount && i < flags.length; i++) {
                char c = flags[i];
                bitmap[i] = (byte) ((c == SLOT_USED || c == SLOT_DELETED) ? c : SLOT_EMPTY);
            }
            raf.seek(h.bitmapOffset);
            raf.write(bitmap);
            // 段3 数据区
            byte[] blank = new byte[h.slotSize];
            Arrays.fill(blank, (byte) ' ');
            for (int i = 0; i < h.slotCount; i++) {
                raf.seek(h.dataOffset + (long) i * h.slotSize);
                byte[] p = payloads != null ? payloads[i] : null;
                if (p == null || p.length == 0) {
                    raf.write(blank);
                } else if (p.length == h.slotSize) {
                    raf.write(p);
                } else {
                    byte[] block = blank.clone();
                    System.arraycopy(p, 0, block, 0, Math.min(p.length, h.slotSize));
                    raf.write(block);
                }
            }
        }
        writeHeader(f, h);
    }

    /** 保证磁盘文件大小不少于格式信息区声明的总大小（扩展后把新增区域补成空槽）。 */
    public static void ensureFileSize(Path f, Header h) throws IOException {
        if (!Files.exists(f) || Files.size(f) < h.fileSize) {
            try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "rw")) {
                raf.setLength(h.fileSize);
            }
        }
    }

    // ===================== 行编解码 =====================

    /** 行对象 → 槽内字节（紧凑 JSON 数组，例如 [1,"zhangsan",90]）。 */
    public static byte[] encodeRow(Object[] row) throws IOException {
        return JsonUtils.mapper().writeValueAsBytes(Arrays.asList(row));
    }

    /** 槽内文本 → 行值列表。 */
    public static List<?> decodeRow(String text) throws IOException {
        return JsonUtils.mapper().readValue(text, List.class);
    }

    private static int intOf(Object v, int def) {
        return v == null ? def : ((Number) v).intValue();
    }

    private static long longOf(Object v, long def) {
        return v == null ? def : ((Number) v).longValue();
    }
}
