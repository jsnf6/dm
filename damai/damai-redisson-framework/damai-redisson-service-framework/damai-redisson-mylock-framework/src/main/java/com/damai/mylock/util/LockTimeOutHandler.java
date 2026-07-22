package com.damai.mylock.util;

@FunctionalInterface
public interface LockTimeOutHandler {
    void handler(String lockName);
}
