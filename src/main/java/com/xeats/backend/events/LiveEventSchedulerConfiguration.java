package com.xeats.backend.events;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class LiveEventSchedulerConfiguration {
    @Bean(destroyMethod = "shutdown")
    ScheduledExecutorService liveEventScheduler() {
        return Executors.newScheduledThreadPool(2, Thread.ofPlatform()
                .daemon(true)
                .name("xeats-live-events-", 0)
                .factory());
    }
}
