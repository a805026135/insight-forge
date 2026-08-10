package com.insightforge.api.auth;

import com.insightforge.persistence.mapper.UserMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;

/**
 * 鉴权服务（Phase 14.3）：
 *   - 登录：BCrypt 校验密码（不再是明文比对）；
 *   - 签发 JWT（HS256，含 userId/role/displayName，24h 过期）；
 *   - 校验/解析 JWT。
 *
 * 诚实边界：JWT 密钥默认值是开发用常量（application.yml 可覆盖），生产必须换成环境变量。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    /** 开发用默认密钥（与 application.yml 默认值一致，用于本地/测试）。 */
    private static final String DEMO_SECRET = "insightforge-demo-secret-please-override-0123456789";

    private final UserMapper userMapper;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final SecretKey key;
    private final long ttlHours;

    public AuthService(UserMapper userMapper,
                       @Value("${insightforge.auth.jwt-secret}") String secret,
                       @Value("${insightforge.auth.token-ttl-hours:24}") long ttlHours,
                       @Value("${spring.profiles.active:}") String activeProfiles) {
        this.userMapper = userMapper;
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlHours = ttlHours;
        // 安全收尾：非 local 环境若仍用开发默认密钥，明显告警（生产应注入环境变量）
        if (DEMO_SECRET.equals(secret) && !activeProfiles.contains("local")) {
            log.warn("JWT 密钥仍为开发默认值！生产环境必须通过 INSIGHTFORGE_AUTH_JWT_SECRET 注入强密钥。");
        }
    }

    /** 用户名+密码 → 认证通过返回 AuthUser；失败返回 empty（不区分“用户不存在/密码错”）。 */
    public Optional<AuthUser> authenticate(String username, String rawPassword) {
        var user = userMapper.findByUsername(username);
        if (user == null || user.getPassword() == null || !encoder.matches(rawPassword, user.getPassword())) {
            return Optional.empty();
        }
        return Optional.of(AuthUser.from(user));
    }

    /** 签发 JWT。 */
    public String issueToken(AuthUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(user.id()))
                .claim("username", user.username())
                .claim("role", user.role())
                .claim("displayName", user.displayName())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttlHours, ChronoUnit.HOURS)))
                .signWith(key)
                .compact();
    }

    /** 解析并校验 JWT；签名/过期非法返回 empty。 */
    public Optional<AuthUser> parse(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            return Optional.of(new AuthUser(
                    Long.parseLong(claims.getSubject()),
                    claims.get("username", String.class),
                    claims.get("role", String.class),
                    claims.get("displayName", String.class)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
