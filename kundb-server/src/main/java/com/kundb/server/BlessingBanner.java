package com.kundb.server;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 开机祈福横幅：从 classpath 读取 {@code /blessing.txt}（UTF-8）原样打印到 stdout。
 * <p>纯彩蛋，任何 IO 异常都静默忽略，绝不影响服务端启动。</p>
 */
public final class BlessingBanner {

    private BlessingBanner() {
    }

    public static void print() {
        try (InputStream in = BlessingBanner.class.getResourceAsStream("/blessing.txt")) {
            if (in == null) {
                return; // 资源缺失（例如从 jar 外运行）时直接跳过
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                }
            }
            System.out.println();
        } catch (Exception ignored) {
            // 横幅失败不影响启动
        }
    }
}
