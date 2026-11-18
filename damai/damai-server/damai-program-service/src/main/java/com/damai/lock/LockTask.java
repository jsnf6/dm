package com.damai.lock;

/**
 * @program: 
 * @description: 锁的任务
 * 
 **/
@FunctionalInterface
public interface LockTask<V> {
    /**
     * 执行锁的任务
     * @return 结果
     */
    V execute();
}