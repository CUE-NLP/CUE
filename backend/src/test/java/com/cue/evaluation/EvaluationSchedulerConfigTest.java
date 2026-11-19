package com.cue.evaluation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.cue.evaluation.service.EvaluationBatchService;
import com.cue.evaluation.service.EvaluationScheduler;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.Scheduled;

class EvaluationSchedulerConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(EvaluationBatchService.class, () -> mock(EvaluationBatchService.class))
            .withUserConfiguration(EvaluationScheduler.class);

    @Test
    void 기본값과_true에서는_스케줄러_빈이_있고_false에서는_없다() {
        runner.run(ctx -> assertEquals(1, ctx.getBeanNamesForType(EvaluationScheduler.class).length));
        runner.withPropertyValues("cue.evaluation.enabled=true")
                .run(ctx -> assertEquals(1, ctx.getBeanNamesForType(EvaluationScheduler.class).length));
        runner.withPropertyValues("cue.evaluation.enabled=false")
                .run(ctx -> assertEquals(0, ctx.getBeanNamesForType(EvaluationScheduler.class).length));
    }

    @Test
    void 기본_cron은_매일_새벽_3시_서울_시간이고_설정으로_바꿀_수_있다() throws Exception {
        Method m = EvaluationScheduler.class.getMethod("runDaily");
        Scheduled scheduled = m.getAnnotation(Scheduled.class);
        assertEquals("${cue.evaluation.cron:0 0 3 * * *}", scheduled.cron());
        assertEquals("${cue.evaluation.zone:Asia/Seoul}", scheduled.zone());
    }
}
