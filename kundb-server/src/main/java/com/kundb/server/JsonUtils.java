package com.kundb.server;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kundb.server.engine.EngineException;

/**
 * Jackson 封装：协议 payload 的 UTF-8 JSON 编解码。
 * <p>关键设置：关闭 {@code FAIL_ON_UNKNOWN_PROPERTIES}，使协议演进时旧客户端可忽略新增字段
 * （docs/protocol.md §13 演进约定）。</p>
 */
public final class JsonUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private JsonUtils() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static byte[] write(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (Exception e) {
            throw new RuntimeException("JSON 序列化失败: " + e.getMessage(), e);
        }
    }

    public static <T> T read(byte[] bytes, Class<T> clazz) throws EngineException {
        try {
            return MAPPER.readValue(bytes, clazz);
        } catch (Exception e) {
            throw new EngineException(com.kundb.common.protocol.ErrorCode.SYNTAX_ERROR,
                    "JSON 解析失败: " + e.getMessage());
        }
    }
}
