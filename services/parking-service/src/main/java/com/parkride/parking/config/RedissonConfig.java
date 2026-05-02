package com.parkride.parking.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates a {@link RedissonClient} bean for distributed slot locking.
 *
 * <p>Redisson is used exclusively for {@code RLock} in {@code SlotAssignmentService}.
 * The standard {@code RedisTemplate} (from {@link RedisConfig}) handles the
 * availability cache. Both connect to the same Redis instance.
 */
@Configuration
public class RedissonConfig {

    @Value("${spring.data.redis.host}")     private String host;
    @Value("${spring.data.redis.port}")     private int    port;
    @Value("${spring.data.redis.password}") private String password;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setPassword(password.isEmpty() ? null : password)
                .setConnectionMinimumIdleSize(2)
                .setConnectionPoolSize(10)
                .setConnectTimeout(3000)
                .setTimeout(3000);
        return Redisson.create(config);
    }
}
