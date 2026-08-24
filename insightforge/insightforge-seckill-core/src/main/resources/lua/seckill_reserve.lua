-- 可恢复库存预留协议：把幂等、扣库存、恢复载荷和超时索引放进同一原子边界。
--
-- KEYS[1] stock key
-- KEYS[2] reservation key (order:idemp:{orderId})
-- KEYS[3] pending reservation ZSET
-- ARGV[1] qty
-- ARGV[2] recovery payload JSON
-- ARGV[3] reservation TTL seconds
-- ARGV[4] compensation deadline epoch millis
-- ARGV[5] orderId (ZSET member)
-- ARGV[6] max pending reservations (overload gate)
--
-- return  2 duplicate reservation
--         1 reserved
--         0 sold out
--        -1 stock not preheated
--        -2 managed key has unexpected Redis type
--        -3 pending recovery queue is full

local stockType = redis.call('TYPE', KEYS[1]).ok
if stockType ~= 'none' and stockType ~= 'string' then
  return -2
end
local reservationType = redis.call('TYPE', KEYS[2]).ok
if reservationType ~= 'none' and reservationType ~= 'string' then
  return -2
end
local pendingType = redis.call('TYPE', KEYS[3]).ok
if pendingType ~= 'none' and pendingType ~= 'zset' then
  return -2
end

if redis.call('EXISTS', KEYS[2]) == 1 then
  return 2
end
local maxPending = tonumber(ARGV[6])
if maxPending == nil or redis.call('ZCARD', KEYS[3]) >= maxPending then
  return -3
end


local stock = tonumber(redis.call('GET', KEYS[1]))
if stock == nil then
  return -1
end
local qty = tonumber(ARGV[1])
if qty == nil or qty <= 0 then
  return -2
end
if stock < qty then
  return 0
end

-- 原子提交：库存、恢复载荷和补偿索引必须同时成功或同时不发生。
redis.call('DECRBY', KEYS[1], qty)
redis.call('SET', KEYS[2], ARGV[2], 'EX', tonumber(ARGV[3]))
redis.call('ZADD', KEYS[3], tonumber(ARGV[4]), ARGV[5])

return 1
