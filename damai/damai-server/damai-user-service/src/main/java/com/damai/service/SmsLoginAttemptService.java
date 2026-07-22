package com.damai.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class SmsLoginAttemptService {
    private final Map<String, AtomicInteger> failCountMap = new ConcurrentHashMap<>();
    private final Map<String, Long> lockoutMap = new ConcurrentHashMap<>();

    private static final int MAX_ATTEMPTS = 5;
    private static final long LOCKOUT_DURATION = 30 * 60 * 1000L; // 30分钟

    public boolean checkLocked(String mobile) {
        Long lockUntil = lockoutMap.get(mobile);
        if (lockUntil == null) return false;
        if (System.currentTimeMillis() < lockUntil) return true;
        // 锁定期已过，清除记录
        lockoutMap.remove(mobile);
        failCountMap.remove(mobile);
        return false;
    }
    public void recordFailure(String mobile) {
        int count = failCountMap.computeIfAbsent(mobile, k -> new AtomicInteger(0)).incrementAndGet();
        if (count >= MAX_ATTEMPTS) {
            lockoutMap.put(mobile, System.currentTimeMillis() + LOCKOUT_DURATION);
        }
    }
    public void clearAttempts(String mobile) {
        failCountMap.remove(mobile);
        lockoutMap.remove(mobile);
    }
}
