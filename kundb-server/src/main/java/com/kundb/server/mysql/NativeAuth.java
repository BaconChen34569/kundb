package com.kundb.server.mysql;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * mysql_native_password 鉴权：校验客户端回传的 token 是否与服务端按明文密码算出的期望一致。
 * <p>token = SHA1(SHA1(password)) XOR SHA1(scramble + SHA1(SHA1(password)))</p>
 */
public final class NativeAuth {

    private NativeAuth() {
    }

    public static boolean verify(byte[] scramble, String plainPassword, byte[] clientToken) {
        if (plainPassword == null || clientToken == null) {
            return false;
        }
        try {
            byte[] expected = expectedToken(scramble, plainPassword);
            return Arrays.equals(expected, clientToken);
        } catch (Exception e) {
            return false;
        }
    }

    /** 计算服务端期望的 native 凭据 token（调试用，便于与客户端回传的 token 对比）。 */
    public static byte[] expectedToken(byte[] scramble, String plainPassword) throws Exception {
        byte[] p = plainPassword.getBytes(StandardCharsets.UTF_8);
        byte[] stage1 = sha1(p);
        byte[] stage2 = sha1(stage1);
        byte[] concat = new byte[scramble.length + stage2.length];
        System.arraycopy(scramble, 0, concat, 0, scramble.length);
        System.arraycopy(stage2, 0, concat, scramble.length, stage2.length);
        return xor(stage1, sha1(concat));
    }

    private static byte[] sha1(byte[] in) throws Exception {
        return MessageDigest.getInstance("SHA-1").digest(in);
    }

    private static byte[] xor(byte[] a, byte[] b) {
        byte[] out = new byte[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = (byte) (a[i] ^ b[i]);
        }
        return out;
    }
}
