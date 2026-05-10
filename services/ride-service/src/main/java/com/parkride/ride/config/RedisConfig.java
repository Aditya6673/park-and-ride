package com.parkride.ride.config;

import com.parkride.security.JwtUtil;
import com.parkride.security.RsaKeyUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.io.IOException;
import java.security.interfaces.RSAPublicKey;

@Configuration
public class RedisConfig {

    @Bean
    public JwtUtil jwtUtil(
            @Value("classpath:keys/public.pem") Resource publicKeyResource) throws IOException {
        RSAPublicKey publicKey = RsaKeyUtil.loadPublicKey(publicKeyResource.getInputStream());
        return new JwtUtil(publicKey);
    }

    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        return template;
    }
}
