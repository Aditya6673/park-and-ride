package com.parkride.pricing.config;

import com.parkride.security.JwtUtil;
import com.parkride.security.RsaKeyUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Infrastructure beans for pricing-service:
 * <ul>
 *   <li>{@link StringRedisTemplate} — occupancy counters + price cache</li>
 *   <li>{@link JwtUtil} — JWT parsing (common-security is a plain library, no component scan)</li>
 * </ul>
 */
@Configuration
public class RedisConfig {

    @Bean
    @SuppressWarnings("null")
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    /** Verify-only — pricing-service never mints tokens, only validates them. */
    @Bean
    public JwtUtil jwtUtil(@Value("classpath:keys/public.pem") Resource publicKeyRes)
            throws java.io.IOException {
        return new JwtUtil(RsaKeyUtil.loadPublicKey(publicKeyRes.getInputStream()));
    }
}
