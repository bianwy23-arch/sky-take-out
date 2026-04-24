package com.sky.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sky.mapper.CategoryMapper;
import com.sky.vo.DishVO;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Configuration
@Slf4j
public class CacheConfiguration {

    /**
     * Caffeine 本地缓存：L1，最大 100 条，TTL=60s
     */
    @Bean
    public Cache<String, List<DishVO>> dishLocalCache() {
        return Caffeine.newBuilder()
                .maximumSize(100)
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Redisson 布隆过滤器：拦截不存在的 categoryId，防止缓存穿透
     */
    @Bean
    public RBloomFilter<Long> categoryBloomFilter(RedissonClient redissonClient, CategoryMapper categoryMapper) {
        RBloomFilter<Long> bloom = redissonClient.getBloomFilter("bloom:dish:category");
        bloom.tryInit(1000, 0.01);
        List<Long> ids = categoryMapper.listAllIds();
        ids.forEach(bloom::add);
        log.info("Bloom filter initialized with {} categories", ids.size());
        return bloom;
    }
}
