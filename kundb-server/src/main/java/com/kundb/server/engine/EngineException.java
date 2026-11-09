package com.kundb.server.engine;

import com.kundb.common.protocol.ErrorCode;

/**
 * 引擎层统一异常，携带一个机器可读 {@link ErrorCode}，供服务端映射为协议 ERROR 回包。
 */
public class EngineException extends Exception {

    private final ErrorCode code;

    public EngineException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public EngineException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
