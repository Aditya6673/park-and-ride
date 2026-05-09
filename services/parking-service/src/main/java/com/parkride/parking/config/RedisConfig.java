package com.parkride.parking.config;

import com.parkride.security.JwtUtil;
import com.parkride.security.RsaKeyUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
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

    /** Verify-only — parking-service never mints tokens, only validates them. */
    @Bean
    public JwtUtil jwtUtil(@Value("classpath:keys/public.pem") Resource publicKeyRes)
            throws java.io.IOException {
        return new JwtUtil(RsaKeyUtil.loadPublicKey(publicKeyRes.getInputStream()));
    }
}
