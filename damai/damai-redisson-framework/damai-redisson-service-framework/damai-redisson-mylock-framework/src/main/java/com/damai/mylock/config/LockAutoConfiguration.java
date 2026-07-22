package com.damai.mylock.config;

import com.damai.locallock.LocalLockCache;
import com.damai.mylock.core.ManageLocker;
import com.damai.mylock.factory.ServiceLockFactory;
import com.damai.mylock.util.ServiceLockTool;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
public class LockAutoConfiguration {

    @Bean
    public ManageLocker manageLocker(RedissonClient redissonClient) {
        return new ManageLocker(redissonClient);
    }

    @Bean
    public ServiceLockFactory serviceLockFactory(ManageLocker manageLocker) {
        return new ServiceLockFactory(manageLocker);
    }

    @Bean
    public ServiceLockTool serviceLockTool(ServiceLockFactory serviceLockFactory) {
        return new ServiceLockTool(serviceLockFactory);
    }

    @Bean
    public LocalLockCache localLockCache() {
        return new LocalLockCache();
    }
}