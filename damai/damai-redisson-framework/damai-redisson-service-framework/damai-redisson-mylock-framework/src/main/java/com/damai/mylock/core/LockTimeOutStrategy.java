package com.damai.mylock.core;

import com.damai.mylock.util.LockTimeOutHandler;

public enum LockTimeOutStrategy implements LockTimeOutHandler {
    FAIL {
        @Override
        public void handler(String lockName) {
            throw new RuntimeException(lockName + " 获取锁超时");
        }
    }

}
