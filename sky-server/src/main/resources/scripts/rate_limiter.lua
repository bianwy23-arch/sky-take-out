-- 滑动窗口限流 Lua 脚本
-- KEYS[1] = 限流 key（如 rate:userId）
-- ARGV[1] = 窗口起始时间（now - windowMs）
-- ARGV[2] = 当前时间戳（ms）
-- ARGV[3] = 最大请求数
-- ARGV[4] = 窗口大小（ms），用于设置 key 的 TTL

-- 1. 删除窗口外的旧数据
redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[1])

-- 2. 统计窗口内数量
local count = redis.call('ZCARD', KEYS[1])

-- 3. 判断是否超限
if count >= tonumber(ARGV[3]) then
    return 0
end

-- 4. 未超限，添加当前请求
redis.call('ZADD', KEYS[1], ARGV[2], ARGV[2])

-- 5. 设置 key 过期时间，防止残留
redis.call('PEXPIRE', KEYS[1], ARGV[4])

return 1
