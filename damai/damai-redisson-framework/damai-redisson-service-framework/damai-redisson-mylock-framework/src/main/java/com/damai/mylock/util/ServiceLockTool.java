package com.damai.mylock.util;

import com.damai.mylock.core.LockTimeOutStrategy;
import com.damai.mylock.core.LockType;
import com.damai.mylock.factory.ServiceLockFactory;
import com.damai.mylock.locker.ServiceLocker;
import java.util.concurrent.TimeUnit;

public class ServiceLockTool {

    private final ServiceLockFactory lockFactory;

    public ServiceLockTool(ServiceLockFactory lockFactory) {
        this.lockFactory = lockFactory;
    }

    public void execute(TaskRun taskRun, String name, String[] keys) {
        execute(LockType.Reentrant, taskRun, name, keys);
    }

    public void execute(LockType lockType, TaskRun taskRun, String name, String[] keys) {
        ServiceLocker serviceLocker = lockFactory.getServiceLock(lockType);
        String lockkey = name + ":" + String.join(":", keys);
        boolean isLock = serviceLocker.tryLock(lockkey, TimeUnit.SECONDS, 20);
        if (isLock) {
            try {
                taskRun.run();
            } finally {
                serviceLocker.unlock(lockkey);
            }
        } else {
            throw new RuntimeException("获取锁失败，key：" + lockkey);
        }
    }

    public void execute(TaskRun taskRun, String name, String[] keys, long waitTime) {
        execute(LockType.Reentrant, taskRun, name, keys, waitTime);
    }

    public void execute(LockType lockType, TaskRun taskRun, String name, String[] keys, long waitTime) {
        ServiceLocker serviceLocker = lockFactory.getServiceLock(lockType);
        String lockkey = name + ":" + String.join(":", keys);
        boolean isLock = serviceLocker.tryLock(lockkey, TimeUnit.SECONDS, waitTime);
        if (isLock) {
            try {
                taskRun.run();
            } finally {
                serviceLocker.unlock(lockkey);
            }
        } else {
            LockTimeOutStrategy.FAIL.handler(lockkey);
        }
    }

    public <T> T execute(TaskCall<T> taskCall, String name, String[] keys) {
        String lockkey=name + ":" + String.join(":", keys);
        ServiceLocker serviceLocker = lockFactory.getServiceLock(LockType.Reentrant);
        boolean isLock=serviceLocker.tryLock(lockkey, TimeUnit.SECONDS, 20);
        if(isLock){
            try{
                return taskCall.call();
            }finally{
                serviceLocker.unlock(lockkey);
            }
        }else{
            LockTimeOutStrategy.FAIL.handler(lockkey);
            return null;
        }
    }

}