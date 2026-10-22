package io.pgredis;

import io.pgredis.codec.JacksonValueCodec;
import io.pgredis.dialect.MySqlDialect;
import io.pgredis.pubsub.PollingPubSub;
import io.pgredis.pubsub.PubSub;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests against a real MySQL (table-based polling pub/sub).
 * Skipped automatically when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
class PgRedisMySqlIntegrationTest extends AbstractSqlRedisIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    static PgRedisTemplate ops;

    @BeforeAll
    static void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");

        JdbcTemplate jdbc = new JdbcTemplate(ds);
        MySqlDialect dialect = new MySqlDialect();

        PgRedisProperties props = new PgRedisProperties();
        props.setDialect("mysql");
        props.setPubSubPollMs(200);

        PubSub pubSub = new PollingPubSub(jdbc, props.getTablePrefix() + "events", 200);

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

    @Override
    long subscribeSettleMs() {
        return 400; // polling interval is 200ms
    }
}
