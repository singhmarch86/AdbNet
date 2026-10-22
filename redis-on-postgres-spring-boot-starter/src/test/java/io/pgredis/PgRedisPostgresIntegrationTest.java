package io.pgredis;

import io.pgredis.codec.JacksonValueCodec;
import io.pgredis.dialect.PostgresDialect;
import io.pgredis.pubsub.PostgresPubSub;
import io.pgredis.pubsub.PubSub;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests against a real PostgreSQL (native LISTEN/NOTIFY pub/sub).
 * Skipped automatically when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
class PgRedisPostgresIntegrationTest extends AbstractSqlRedisIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    static PgRedisTemplate ops;

    @BeforeAll
    static void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        ds.setDriverClassName("org.postgresql.Driver");

        JdbcTemplate jdbc = new JdbcTemplate(ds);
        PostgresDialect dialect = new PostgresDialect();
        PubSub pubSub = new PostgresPubSub(ds);

        PgRedisProperties props = new PgRedisProperties();
        props.setDialect("postgresql");

        ops = new PgRedisTemplate(jdbc, props, new JacksonValueCodec(), dialect, pubSub);
        ops.init();
    }

    @AfterAll
    static void tearDown() {
        if (ops != null) {
            ops.close();
        }
    }

    @Override
    PgRedisTemplate ops() {
        return ops;
    }
}
