local stockKey = KEYS[1]
local grabbedKey = KEYS[2]
local userId = ARGV[1]

if redis.call('SISMEMBER', grabbedKey, userId) == 1 then
    redis.call('SREM', grabbedKey, userId)
    redis.call('INCR', stockKey)
    return 1
end

return 0
