package com.xingyan.shortlink.common.codec;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShortCodeGeneratorTest {

    private static final Pattern BASE62 = Pattern.compile("^[0-9A-Za-z]{7}$");

    @Test
    void producesBase62CodesOfFixedLength() {
        for (int i = 0; i < 10_000; i++) {
            assertTrue(BASE62.matcher(ShortCodeGenerator.next()).matches(), "violates Base62@7");
        }
    }

    @Test
    void honorsCustomLength() {
        assertEquals(3, ShortCodeGenerator.next(3).length());
        assertEquals(12, ShortCodeGenerator.next(12).length());
        assertThrows(IllegalArgumentException.class, () -> ShortCodeGenerator.next(0));
    }

    @Test
    void collisionRateOverLargeSampleIsNegligible() {
        int n = 200_000;
        Set<String> seen = new HashSet<>(n * 2);
        for (int i = 0; i < n; i++) {
            seen.add(ShortCodeGenerator.next());
        }
        // 62^7≈3.5万亿：20 万样本期望碰撞 <个位数；断言宽松上界防抖动
        assertTrue(n - seen.size() < 10, "collision count " + (n - seen.size()) + " abnormally high");
    }
}
