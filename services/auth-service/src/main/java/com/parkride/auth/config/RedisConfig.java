package com.parkride.auth.config;

import com.parkride.security.JwtUtil;
import com.parkride.security.RsaKeyUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    /**
     * Auth-service uses BOTH private key (to sign tokens) and public key (to verify them).
     * Private key never leaves auth-service.
     */
    @Bean
    public JwtUtil jwtUtil(
            @Value("classpath:keys/private.pem") Resource privateKeyRes,
            @Value("classpath:keys/public.pem")  Resource publicKeyRes) throws java.io.IOException {
        return new JwtUtil(
                RsaKeyUtil.loadPrivateKey(privateKeyRes.getInputStream()),
                RsaKeyUtil.loadPublicKey(publicKeyRes.getInputStream()));
    }

    /**
     * String-to-String template used for:
     * <ul>
     * <li>JWT blacklist: key = {@code blacklist:{jti}}, value = {@code "1"}</li>
     * <li>Rate limiting: key = {@code rate:{userId}:requests}, value = count</li>
     * </ul>
     */
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
}
