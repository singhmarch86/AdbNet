package io.mongoredis;

import com.mongodb.client.MongoClient;
import io.mongoredis.codec.JacksonValueCodec;
import io.mongoredis.codec.ValueCodec;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration that wires a {@link MongoRedisTemplate} from the
 * application's existing {@link MongoClient}.
 */
@AutoConfiguration(after = MongoAutoConfiguration.class)
@ConditionalOnClass(MongoClient.class)
@EnableConfigurationProperties(MongoRedisProperties.class)
public class MongoRedisAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ValueCodec mongoRedisValueCodec() {
        return new JacksonValueCodec();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnBean(MongoClient.class)
    @ConditionalOnMissingBean
    public MongoRedisTemplate mongoRedisTemplate(MongoClient client,
                                                 MongoRedisProperties properties,
                                                 ValueCodec codec) {
        MongoRedisTemplate template = new MongoRedisTemplate(client, properties, codec);
        if (properties.isInitializeOnStartup()) {
            template.init();
        }
        return template;
    }
}
