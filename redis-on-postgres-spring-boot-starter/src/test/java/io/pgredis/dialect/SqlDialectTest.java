package io.pgredis.dialect;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests of the dialect SQL generation — no database or Spring context.
 */
class SqlDialectTest {

    @Test
    void postgresUsesOnConflictAndLimitAll() {
        SqlDialect d = new PostgresDialect();
        assertThat(d.id()).isEqualTo("postgresql");
        assertThat(d.hasNativePubSub()).isTrue();
        assertThat(d.upsertKv("mr_kv")).contains("ON CONFLICT (rkey) DO UPDATE");
        assertThat(d.upsertZset("mr_zset")).contains("ON CONFLICT (k, m) DO UPDATE");
        assertThat(d.rangeSql("mr_zset", 0, -1, true)).contains("LIMIT ALL").contains("ASC");
        assertThat(d.rangeSql("mr_zset", 5, 10, false)).contains("OFFSET 5").contains("LIMIT 10").contains("DESC");
        assertThat(d.ddl("mr_kv", "mr_counter", "mr_zset", "mr_events"))
                .anyMatch(s -> s.contains("TIMESTAMPTZ"));
    }

    @Test
    void mysqlUsesOnDuplicateKeyAndBoundedLimit() {
        SqlDialect d = new MySqlDialect();
        assertThat(d.id()).isEqualTo("mysql");
        assertThat(d.hasNativePubSub()).isFalse();
        assertThat(d.upsertKv("mr_kv")).contains("ON DUPLICATE KEY UPDATE");
        assertThat(d.upsertZset("mr_zset")).contains("ON DUPLICATE KEY UPDATE");
        assertThat(d.rangeSql("mr_zset", 0, -1, true)).contains("OFFSET 0").doesNotContain("LIMIT ALL");
        assertThat(d.rangeSql("mr_zset", 5, 10, false)).contains("LIMIT 10 OFFSET 5").contains("DESC");
        // MySQL needs an events table for polling-based pub/sub.
        assertThat(d.ddl("mr_kv", "mr_counter", "mr_zset", "mr_events"))
                .anyMatch(s -> s.contains("mr_events") && s.contains("AUTO_INCREMENT"));
    }
}
