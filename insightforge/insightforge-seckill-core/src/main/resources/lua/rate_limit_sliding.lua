-- ============================================================
-- 滑动窗口限流脚本（Phase 5 的"闸机"）
-- ============================================================
-- 为什么要滑动窗口而不是固定窗口？
--   固定窗口（如"每秒 20 次"按整秒切）在窗口边界会出现"前一秒末尾 20 次 + 后一秒开头 20 次"
--   在 1 秒内放行 40 次的漏洞。滑动窗口只认"最近 window 毫秒内"的请求数，边界平滑、更严。
--
-- 怎么实现：把每次请求的时间戳塞进一个 ZSET（有序集合），score 和 member 都用时间戳。
--   - ZREMRANGEBYSCORE 删掉"窗口之外"的旧时间戳（过期请求）；
--   - ZCARD 数一下窗口里还剩几个（即最近 window 毫秒内的请求数）；
--   - 没超阈值就再加一个当前时间戳，放行；否则拦截。
--   整段脚本在 Redis 单线程里原子执行，多机共享同一个 ZSET，限流才准。
--
-- 入参：
--   KEYS[1] = 限流 key，例如 rate:limit:u:1001:a:1
--   ARGV[1] = 窗口大小（毫秒）
--   ARGV[2] = 窗口内最大放行次数 max
--   ARGV[3] = 当前时间戳（毫秒，由 Java 端传入，避免 Redis 与业务机时钟差异）
--
-- 返回值：1 = 放行；0 = 限流（拦截）
-- ============================================================

local key = KEYS[1]
local window = tonumber(ARGV[1])
local max = tonumber(ARGV[2])
local now = tonumber(ARGV[3])

-- 1) 清掉窗口外的旧请求（now - window 之前的时间戳统统过期）
redis.call('ZREMRANGEBYSCORE', key, 0, now - window)

-- 2) 统计窗口内当前请求数
local count = redis.call('ZCARD', key)

if count < max then
  -- 3) 放行：把本次请求的时间戳加进 ZSET。
  --    用 key..':seq' 的自增序列拼成唯一 member，避免"同一毫秒两次请求 member 撞车"
  --    （若 member 相同，ZADD 只是覆盖、不新增元素，会导致少计数、多放行）。
  local member = now .. ':' .. redis.call('INCR', key .. ':seq')
  redis.call('ZADD', key, now, member)
  -- 给 ZSET 和其序号计数器都设过期，避免冷 key 永久占用内存
  redis.call('PEXPIRE', key, window)
  redis.call('PEXPIRE', key .. ':seq', window)
  return 1
end

-- 超过阈值：拦截
return 0
