package io.pgredis;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Context smoke test: the starter registers a {@link PgRedisTemplate} when a
 * {@link DataSource} is present and honours property binding. Schema init is
 * disabled so no live PostgreSQL is required.
 */
class PgRedisAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PgRedisAutoConfiguration.class))
            .withPropertyValues("pgredis.initialize-on-startup=false");

    @Test
    void registersTemplateWhenDataSourcePresent() {
        runner.withBean(DataSource.class, () -> mock(DataSource.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(PgRedisTemplate.class);
                    assertThat(context).hasSingleBean(io.pgredis.codec.ValueCodec.class);
                });
    }

    @Test
    void backsOffWithoutDataSource() {
        runner.run(context -> assertThat(context).doesNotHaveBean(PgRedisTemplate.class));
    }

    @Test
    void bindsProperties() {
        runner.withBean(DataSource.class, () -> mock(DataSource.class))
                .withPropertyValues("pgredis.table-prefix=rc_", "pgredis.ttl-sweep-seconds=15")
                .run(context -> {
                    PgRedisProperties props = context.getBean(PgRedisProperties.class);
                    assertThat(props.getTablePrefix()).isEqualTo("rc_");
                    assertThat(props.getTtlSweepSeconds()).isEqualTo(15);
                });
    }
}
