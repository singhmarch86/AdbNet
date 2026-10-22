package io.mongoredis;

import com.mongodb.client.MongoClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

/**
 * Context-level smoke test: the starter registers a {@link MongoRedisTemplate}
 * when a {@link MongoClient} is present, and honours property binding. No live
 * MongoDB is required (init is disabled, client is mocked).
 */
class MongoRedisAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MongoRedisAutoConfiguration.class))
            .withPropertyValues("mongoredis.initialize-on-startup=false");

    @Test
    void registersTemplateWhenMongoClientPresent() {
        runner.withBean(MongoClient.class, () -> mock(MongoClient.class, RETURNS_DEEP_STUBS))
                .run(context -> {
                    assertThat(context).hasSingleBean(MongoRedisTemplate.class);
                    assertThat(context).hasSingleBean(io.mongoredis.codec.ValueCodec.class);
                });
    }

    @Test
    void backsOffWithoutMongoClient() {
        runner.run(context -> assertThat(context).doesNotHaveBean(MongoRedisTemplate.class));
    }

    @Test
    void bindsProperties() {
        runner.withPropertyValues("mongoredis.database=app", "mongoredis.collection-prefix=rc_")
                .run(context -> {
                    MongoRedisProperties props = context.getBean(MongoRedisProperties.class);
                    assertThat(props.getDatabase()).isEqualTo("app");
                    assertThat(props.getCollectionPrefix()).isEqualTo("rc_");
                });
    }
}
