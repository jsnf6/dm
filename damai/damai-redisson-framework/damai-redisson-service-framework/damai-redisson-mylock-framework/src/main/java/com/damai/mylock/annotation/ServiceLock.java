package com.damai.mylock.annotation;

import com.damai.mylock.core.LockTimeOutStrategy;
import com.damai.mylock.core.LockType;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ServiceLock {
    LockType lockType() default LockType.Reentrant;
    String name() default "";
    String[] keys();
    long waitTime() default 10;
    TimeUnit timeUnit() default TimeUnit.SECONDS;
    LockTimeOutStrategy lockTimeOutStrategy() default LockTimeOutStrategy.FAIL;
    String customLockTimeoutStrategy() default "";
}