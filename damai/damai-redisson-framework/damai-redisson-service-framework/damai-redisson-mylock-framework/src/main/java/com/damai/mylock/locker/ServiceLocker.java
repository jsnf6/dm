package com.damai.mylock.locker;

import org.redisson.api.RLock;
import java.util.concurrent.TimeUnit;

public interface ServiceLocker {
    RLock lock(String lockKey);
    boolean tryLock(String lockKey, TimeUnit unit, long waitTime);
    void unlock(String lockKey);
    void unlock(RLock lock);
}