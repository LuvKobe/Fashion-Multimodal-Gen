package com.edison.util;

import com.edison.entity.User;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

// jwt工具类
@Component
@Slf4j
public class JwtUtil {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Value(("${jwt.secret}"))
    private String secret; // 令牌

    @Value(("${jwt.expiration}"))
    private Long expiration; // 过期时间

    // 缓存前缀key
    private static final String USER_TOKEN_KEY_PREFIX = "jwt:user:";

    public String generateToken(User user) {

        String userKey = USER_TOKEN_KEY_PREFIX + user.getId();
        if (redisTemplate.hasKey(userKey)) {
            return redisTemplate.opsForValue().get(userKey);
        }

        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", user.getId());
        claims.put("username", user.getUsername());

        Date now = new Date();
        Date expireDate = new Date(now.getTime() + expiration * 1000 * 60 * 60 * 2);

        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));

        // 生成的令牌
        String token = Jwts.builder()
                .setClaims(claims)
                .setIssuedAt(now)
                .setExpiration(expireDate)
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
        redisTemplate.opsForValue()
                .set(
                        userKey,
                        token,
                        expiration,
                        TimeUnit.HOURS
                );
        return token;
    }
}
