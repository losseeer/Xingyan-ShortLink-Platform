package com.xingyan.shortlink.common.codec;

import java.security.SecureRandom;

/**
 * 不可预测短码生成（DESIGN 9.3）：CSPRNG + Base62，长度 7。
 * 用 SecureRandom 而非顺序雪花，杜绝枚举遍历；池化后由 Redis SET 保证全局唯一。
 */
public final class ShortCodeGenerator {

    private static final char[] ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    public static final int DEFAULT_LENGTH = 7;

    private ShortCodeGenerator() {
    }

    public static String next() {
        return next(DEFAULT_LENGTH);
    }

    public static String next(int length) {
        if (length <= 0) {
            throw new IllegalArgumentException("length must be positive");
        }
        char[] buf = new char[length];
        for (int i = 0; i < length; i++) {
            buf[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return new String(buf);
    }
}
