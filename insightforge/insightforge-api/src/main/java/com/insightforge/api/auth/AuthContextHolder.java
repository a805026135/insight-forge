package com.insightforge.api.auth;

/**
 * 当前请求的登录用户（由 {@link JwtAuthFilter} 在过滤链中设置/清理）。
 *
 * 为什么用 ThreadLocal 而不是塞进每个方法参数：
 *   秒杀/订单等业务方法签名保持干净（不污染契约），又能在 Controller 层强制
 *   userId 以「登录身份」为准，杜绝伪造他人身份。
 */
public final class AuthContextHolder {

    private static final ThreadLocal<AuthUser> CURRENT = new ThreadLocal<>();

    private AuthContextHolder() {
    }

    public static void set(AuthUser user) {
        CURRENT.set(user);
    }

    public static AuthUser get() {
        return CURRENT.get();
    }

    /** 取当前登录用户 id；未登录抛异常（正常流程到不了这：过滤器已 401）。 */
    public static long requireUserId() {
        AuthUser user = CURRENT.get();
        if (user == null || user.id() == null) {
            throw new IllegalStateException("未登录");
        }
        return user.id();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
