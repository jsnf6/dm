package com.damai.mylock.locker.impl;

import com.damai.mylock.locker.ServiceLocker;
import lombok.AllArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import java.util.concurrent.TimeUnit;

@AllArgsConstructor
public class ServiceLockerImpl implements ServiceLocker {

    private final RedissonClient redissonClient;

    @Override
    public RLock lock(String lockKey) {
        return redissonClient.getLock(lockKey);
    }

    @Override
    public boolean tryLock(String lockKey, TimeUnit unit, long waitTime) {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            return lock.tryLock(waitTime, unit);
        } catch (InterruptedException e) {
            return false;
        }
    }

    @Override
    public void unlock(String lockKey) {
        redissonClient.getLock(lockKey).unlock();
    }

    @Override
    public void unlock(RLock lock) {
        lock.unlock();
    }
}