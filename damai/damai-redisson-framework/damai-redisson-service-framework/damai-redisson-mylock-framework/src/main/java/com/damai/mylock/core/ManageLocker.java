package com.damai.mylock.core;

import com.damai.mylock.locker.ServiceLocker;
import com.damai.mylock.locker.impl.RedissonFairLockerImpl;
import com.damai.mylock.locker.impl.RedissonReadLockerImpl;
import com.damai.mylock.locker.impl.RedissonWriteLockerImpl;
import com.damai.mylock.locker.impl.ServiceLockerImpl;
import org.redisson.api.RedissonClient;
import java.util.HashMap;
import java.util.Map;

public class ManageLocker {
    private final Map<LockType, ServiceLocker> cache = new HashMap<>();
    public ManageLocker(RedissonClient redissonClient) {
        cache.put(LockType.Reentrant, new ServiceLockerImpl(redissonClient));
        cache.put(LockType.Read, new RedissonReadLockerImpl(redissonClient));
        cache.put(LockType.Write, new RedissonWriteLockerImpl(redissonClient));
        cache.put(LockType.Fair, new RedissonFairLockerImpl(redissonClient));
    }

    public ServiceLocker getReentrantLocker() {
        return cache.get(LockType.Reentrant);
    }
    public ServiceLocker getReadLocker() {
        return cache.get(LockType.Read);
    }
    public ServiceLocker getWriteLocker() {
        return cache.get(LockType.Write);
    }
    public ServiceLocker getFairLocker(){
        return cache.get(LockType.Fair);
    }
}