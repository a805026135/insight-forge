package com.insightforge.seckill.core.redis;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ReservationLuaContractTest {

    @Test
    void scriptContainsTheWholeAtomicCommit() throws IOException {
        try (var stream = getClass().getResourceAsStream("/lua/seckill_reserve.lua")) {
            assertTrue(stream != null, "reservation script must be packaged");
            String lua = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(lua.contains("redis.call('DECRBY', KEYS[1], qty)"));
            assertTrue(lua.contains("redis.call('SET', KEYS[2], ARGV[2], 'EX', tonumber(ARGV[3]))"));
            assertTrue(lua.contains("redis.call('ZADD', KEYS[3], tonumber(ARGV[4]), ARGV[5])"));
            assertTrue(lua.stripTrailing().endsWith("return 1"));
        }
    }
}
