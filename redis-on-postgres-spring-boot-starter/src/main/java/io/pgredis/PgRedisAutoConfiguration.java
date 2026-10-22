package io.pgredis;

import io.pgredis.codec.JacksonValueCodec;
import io.pgredis.codec.ValueCodec;
import io.pgredis.dialect.MySqlDialect;
import io.pgredis.dialect.PostgresDialect;
import io.pgredis.dialect.SqlDialect;
import io.pgredis.pubsub.PollingPubSub;
import io.pgredis.pubsub.PostgresPubSub;
import io.pgredis.pubsub.PubSub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Locale;

/**
 * Auto-configuration that wires a {@link PgRedisTemplate} from the application's
 * existing {@link DataSource}, selecting a {@link SqlDialect} and matching
 * {@link PubSub} strategy (native LISTEN/NOTIFY for PostgreSQL, polling otherwise).
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnClass({DataSource.class, JdbcTemplate.class})
@EnableConfigurationProperties(PgRedisProperties.class)
public class PgRedisAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PgRedisAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public ValueCodec pgRedisValueCodec() {
        return new JacksonValueCodec();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean
    public PgRedisTemplate pgRedisTemplate(DataSource dataSource,
                                           PgRedisProperties properties,
                                           ValueCodec codec) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        SqlDialect dialect = resolveDialect(properties, dataSource);
        PubSub pubSub = dialect.hasNativePubSub()
                ? new PostgresPubSub(dataSource)
                : new PollingPubSub(jdbc, properties.getTablePrefix() + "events", properties.getPubSubPollMs());

        PgRedisTemplate template = new PgRedisTemplate(jdbc, properties, codec, dialect, pubSub);
        if (properties.isInitializeOnStartup()) {
            template.init();
        }
        return template;
    }

    private SqlDialect resolveDialect(PgRedisProperties properties, DataSource dataSource) {
        String choice = properties.getDialect();
        if (choice == null || choice.equalsIgnoreCase("auto")) {
            choice = detect(dataSource);
        }
        switch (choice.toLowerCase(Locale.ROOT)) {
            case "mysql":
            case "mariadb":
                return new MySqlDialect();
            case "postgresql":
            case "postgres":
                return new PostgresDialect();
            default:
                log.warn("Unknown pgredis.dialect '{}', defaulting to PostgreSQL", choice);
                return new PostgresDialect();
        }
    }

    private String detect(DataSource dataSource) {
        try (Connection c = dataSource.getConnection()) {
            String product = c.getMetaData().getDatabaseProductName();
            if (product != null && product.toLowerCase(Locale.ROOT).contains("mysql")) {
                return "mysql";
            }
            if (product != null && product.toLowerCase(Locale.ROOT).contains("maria")) {
                return "mysql";
            }
            return "postgresql";
        } catch (Exception e) {
            log.warn("Could not detect database product, defaulting to PostgreSQL: {}", e.getMessage());
            return "postgresql";
        }
    }
}
