package io.pgredis;

import io.pgredis.codec.JacksonValueCodec;
import io.pgredis.codec.ValueCodec;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Auto-configuration that wires a {@link PgRedisTemplate} from the
 * application's existing {@link DataSource}.
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnClass({DataSource.class, JdbcTemplate.class})
@EnableConfigurationProperties(PgRedisProperties.class)
public class PgRedisAutoConfiguration {

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
        PgRedisTemplate template = new PgRedisTemplate(dataSource, properties, codec);
        if (properties.isInitializeOnStartup()) {
            template.init();
        }
        return template;
    }
}
