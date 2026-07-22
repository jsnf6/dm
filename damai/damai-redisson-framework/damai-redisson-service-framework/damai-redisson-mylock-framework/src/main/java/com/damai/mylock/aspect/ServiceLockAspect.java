package com.damai.mylock.aspect;

import com.damai.mylock.annotation.ServiceLock;
import com.damai.mylock.factory.ServiceLockFactory;
import com.damai.mylock.locker.ServiceLocker;
import lombok.AllArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Arrays;

@Aspect
@Component
@AllArgsConstructor
public class ServiceLockAspect {
    private final ServiceLockFactory serviceLockFactory;

    @Around("@annotation(com.damai.mylock.annotation.ServiceLock)")
    public Object around(ProceedingJoinPoint joinPoint, ServiceLock serviceLock) throws Throwable {
        //原来的写法
        //String lockkey = serviceLock.name() + ":" + String.join(":", serviceLock.keys());
        Method method =  ((MethodSignature) joinPoint.getSignature()).getMethod();
        Object[] args = joinPoint.getArgs();
        ParameterNameDiscoverer discoverer = new DefaultParameterNameDiscoverer();
        ExpressionParser parser = new SpelExpressionParser();
        String[] keys= Arrays.stream(serviceLock.keys())
                .map(key->{
                    if(key.startsWith("#")){
                        EvaluationContext context = new MethodBasedEvaluationContext(null, method, args, discoverer);
                        Object value=parser.parseExpression(key).getValue(context);
                        return value.toString();
                    }
                    else{
                        return key;
                    }
                })
                .toArray(String[]::new);
        String lockkey = serviceLock.name() + ":" + String.join(":", keys);
        ServiceLocker locker = serviceLockFactory.getServiceLock(serviceLock.lockType());
        boolean isLock = locker.tryLock(lockkey, serviceLock.timeUnit(), serviceLock.waitTime());
        if (isLock) {
            try {
                return joinPoint.proceed();
            } finally {
                locker.unlock(lockkey);
            }
        } else {
            serviceLock.lockTimeOutStrategy().handler(lockkey);
            return null;
        }
    }
}