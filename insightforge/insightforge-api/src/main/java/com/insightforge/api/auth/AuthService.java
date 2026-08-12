package com.insightforge.api.auth;

import com.insightforge.persistence.mapper.UserMapper;
import com.insightforge.persistence.mapper.MerchantMapper;
import com.insightforge.persistence.entity.User;
import com.insightforge.persistence.entity.Merchant;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
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
    private final MerchantMapper merchantMapper;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final SecretKey key;
    private final long ttlHours;

    public AuthService(UserMapper userMapper,
                       MerchantMapper merchantMapper,
                       @Value("${insightforge.auth.jwt-secret}") String secret,
                       @Value("${insightforge.auth.token-ttl-hours:24}") long ttlHours,
                       Environment environment) {
        this.userMapper = userMapper;
        this.merchantMapper = merchantMapper;
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlHours = ttlHours;
        // local/test 可使用开发密钥；其余环境必须显式注入，避免误把公开密钥带到生产。
        boolean developmentProfile = environment.acceptsProfiles(Profiles.of("local", "test"));
        if (DEMO_SECRET.equals(secret) && !developmentProfile) {
            throw new IllegalStateException(
                    "非 local/test 环境禁止使用默认 JWT 密钥，请配置 INSIGHTFORGE_AUTH_JWT_SECRET");
        }
    }

    /** 用户名+密码 → 认证通过返回 AuthUser；失败返回 empty（不区分“用户不存在/密码错”）。 */
    public Optional<AuthUser> authenticate(String username, String rawPassword) {
        var user = userMapper.findByUsername(username);
        if (user == null || user.getPassword() == null || !encoder.matches(rawPassword, user.getPassword())) {
            return Optional.empty();
        }
        return Optional.of(bindMerchant(user));
    }

    /** 鐢ㄦ埛涓?merchant 琛ㄧ殑 user_id 鍏宠仈锛屾壘鍒板晢瀹惰韩浠藉悗鍐欏叆 AuthUser */
    private AuthUser bindMerchant(User user) {
        Long merchantId = null;
        if (user != null && ("merchant".equals(user.getRole()) || "admin".equals(user.getRole()))) {
            var m = merchantMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Merchant>()
                            .eq("user_id", user.getId()));
            if (m != null) {
                merchantId = m.getId();
            }
        }
        return new AuthUser(user.getId(), user.getUsername(), user.getRole(), user.getDisplayName(), merchantId);
    }

    /**
     * 注册（Phase 16）：BCrypt 加密入库，只开放买家身份。
     * 业务校验失败抛 IllegalArgumentException，由 Controller 转成 400。
     */
    public AuthUser register(String username, String rawPassword, String displayName) {
        String name = username == null ? "" : username.trim();
        if (name.length() < 3 || name.length() > 32) {
            throw new IllegalArgumentException("用户名需为 3~32 个字符");
        }
        if (rawPassword == null || rawPassword.length() < 6 || rawPassword.length() > 64) {
            throw new IllegalArgumentException("密码需为 6~64 位");
        }
        if (userMapper.findByUsername(name) != null) {
            throw new IllegalArgumentException("用户名已存在，请换一个");
        }
        User u = new User();
        u.setUsername(name);
        u.setPassword(encoder.encode(rawPassword));
        u.setRole("user");
        u.setDisplayName(displayName == null || displayName.isBlank() ? name : displayName.trim());
        userMapper.insert(u);
        log.info("新用户注册：{}（id={}）", name, u.getId());
        return AuthUser.from(u);
    }

    /** 签发 JWT。 */
    public String issueToken(AuthUser user) {
        return issueToken(user, new long[]{0});
    }

    /** 签发 JWT，并通过 outExpiresAt 回传过期时间（epoch 毫秒）。 */
    public String issueToken(AuthUser user, long[] outExpiresAt) {
        Instant now = Instant.now();
        Date expiresAt = Date.from(now.plus(ttlHours, ChronoUnit.HOURS));
        if (outExpiresAt != null && outExpiresAt.length > 0) {
            outExpiresAt[0] = expiresAt.getTime();
        }
        return Jwts.builder()
                .subject(String.valueOf(user.id()))
                .claim("username", user.username())
                .claim("role", user.role())
                .claim("displayName", user.displayName())
                .claim("merchantId", user.merchantId() == null ? 0L : user.merchantId())
                .issuedAt(Date.from(now))
                .expiration(expiresAt)
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
                    claims.get("displayName", String.class),
                    claims.get("merchantId", Long.class) != null && claims.get("merchantId", Long.class) > 0
                            ? claims.get("merchantId", Long.class) : null));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
