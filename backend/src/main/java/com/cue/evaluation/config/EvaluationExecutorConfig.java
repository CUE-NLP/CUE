package com.cue.evaluation.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 평가 전용 실행기. Gemini 무료 티어의 분당 한도 때문에 <b>평가는 한 번에 하나</b>만 돌도록 스레드를 1개로 두고,
 * 대기열은 {@code cue.evaluation.queue-capacity}로 제한한다(넘으면 {@code TaskRejectedException} → E004).
 * 종료 시에는 진행 중 평가를 {@code shutdown-wait-seconds}만큼 기다린 뒤 인터럽트한다. DB가 먼저 닫히지 않게 EntityManagerFactory 뒤에 만든다.
 */
@Configuration
public class EvaluationExecutorConfig {
    public static final String EXECUTOR = "evaluationExecutor";

    @Bean(name = EXECUTOR)
    @DependsOn("entityManagerFactory")
    ThreadPoolTaskExecutor evaluationExecutor(EvaluationProperties props) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(props.queueCapacity());
        executor.setThreadNamePrefix("evaluation-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(props.shutdownWaitSeconds());
        return executor;
    }
}
