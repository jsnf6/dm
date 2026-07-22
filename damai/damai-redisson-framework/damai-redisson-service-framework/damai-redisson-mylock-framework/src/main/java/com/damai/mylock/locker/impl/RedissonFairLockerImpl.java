package com.damai.mylock.locker.impl;

import com.damai.mylock.locker.ServiceLocker;
import lombok.AllArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

@AllArgsConstructor
public class RedissonFairLockerImpl implements ServiceLocker {
    private final RedissonClient redissonClient;


    @Override
    public RLock lock(String lockKey) {
        return redissonClient.getFairLock(lockKey);
    }

    @Override
    public boolean tryLock(String lockKey, TimeUnit unit, long waitTime) {
        RLock lock = redissonClient.getFairLock(lockKey);
        try {
            return lock.tryLock(waitTime, unit);
        } catch (InterruptedException e) {
            return false;
        }
    }

    @Override
    public void unlock(String lockKey) {
        redissonClient.getFairLock(lockKey).unlock();
    }

    @Override
    public void unlock(RLock lock) {
        lock.unlock();
    }
}
