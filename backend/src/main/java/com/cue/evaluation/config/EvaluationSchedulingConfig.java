package com.cue.evaluation.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** {@code cue.evaluation.enabled=false}면 스케줄링 자체를 켜지 않는다. */
@Configuration
@EnableConfigurationProperties(EvaluationProperties.class)
public class EvaluationSchedulingConfig {

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(prefix = "cue.evaluation", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class Enabled {}
}
