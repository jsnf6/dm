package com.damai.mylock.localLock;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class localLockCache {

    private Cache<String, ReentrantLock> localLockCache;

    @PostConstruct
    public void init(){

        localLockCache = Caffeine.newBuilder()
                .expireAfterWrite(1, TimeUnit.HOURS)
                .build();
    }

    public ReentrantLock getLock(String lockkey){
        return localLockCache.get(lockkey, key->new ReentrantLock());
    }
}
