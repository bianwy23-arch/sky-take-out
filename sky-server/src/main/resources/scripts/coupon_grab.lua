local stockKey = KEYS[1]
local grabbedKey = KEYS[2]
local userId = ARGV[1]

if redis.call('SISMEMBER', grabbedKey, userId) == 1 then
    return 2
end

local stock = tonumber(redis.call('GET', stockKey))
if stock == nil or stock <= 0 then
    return 1
end

redis.call('DECR', stockKey)
redis.call('SADD', grabbedKey, userId)
return 0
