package com.ncba.countriesinfo.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class CacheConfig {

    @Value("${CACHE_TTL:300}")
    private long cacheTtl;

    /**
     * Single snapshot cache for the full country list which acts as a one-value TTL cache; writes evict it explicitly.
     */
    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager("countries");
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(cacheTtl, TimeUnit.SECONDS)
                .recordStats());
        return cacheManager;
    }
}
