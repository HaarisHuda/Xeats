package com.xeats.backend.demo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DemoFixtureConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DemoFixtureConfiguration.class)
            .withBean(DemoFixtureSeeder.class, () -> new DemoFixtureSeeder(null, null, null, ""));

    @Test
    void registersSeederRunnerOnlyForDemoProfile() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean("demoFixtureRunner"));
        contextRunner.withPropertyValues("spring.profiles.active=demo")
                .run(context -> assertThat(context).hasBean("demoFixtureRunner"));
        contextRunner.withPropertyValues("spring.profiles.active=demo,prod")
                .run(context -> assertThat(context).doesNotHaveBean("demoFixtureRunner"));
    }
}
