package com.xingyan.shortlink.common.error;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErrorCodeTest {

    @Test
    void codesAreUnique() {
        String codes = Arrays.stream(ErrorCode.values())
                .map(ErrorCode::getCode)
                .collect(Collectors.joining(","));
        assertEquals(Arrays.stream(ErrorCode.values()).map(ErrorCode::getCode).distinct().count(),
                Arrays.stream(ErrorCode.values()).count(),
                "duplicate error code: " + codes);
    }

    @Test
    void httpMappedCodesMatchDesign() {
        assertEquals("SL-4291", ErrorCode.RATE_LIMITED.getCode());
        assertTrue(ErrorCode.LINK_EXPIRED.getCode().startsWith("SL-41"));
    }
}
