package com.xeats.backend.demo;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
public class DemoFixtureConfiguration {
    @Bean
    @Profile("demo & !prod")
    ApplicationRunner demoFixtureRunner(DemoFixtureSeeder seeder) {
        return arguments -> seeder.seed();
    }
}
