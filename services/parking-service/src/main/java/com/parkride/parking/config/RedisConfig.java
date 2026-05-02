package com.parkride.parking.config;

import com.parkride.security.JwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Infrastructure beans shared across the parking-service:
 * <ul>
 *   <li>{@link RedisTemplate} — availability cache (String → String)</li>
 *   <li>{@link JwtUtil} — JWT validation for the {@code JwtAuthFilter}</li>
 * </ul>
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }

    /**
     * JwtUtil must be declared as a {@code @Bean} — it has no {@code @Component}
     * annotation because {@code common-security} is a plain library with no
     * Spring dependency. Each service that uses it declares it here.
     */
    @Bean
    public JwtUtil jwtUtil(@Value("${security.jwt.secret}") String secret) {
        return new JwtUtil(secret);
    }
}
