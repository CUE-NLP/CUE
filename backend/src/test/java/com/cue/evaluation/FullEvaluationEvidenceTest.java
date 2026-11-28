package com.cue.evaluation;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cue.evaluation.dto.EvaluationRunResult;
import com.cue.evaluation.repository.EvaluationRepository;
import com.cue.evaluation.service.EvaluationPolicy;
import com.cue.evaluation.service.EvaluationRecorder;
import com.cue.evaluation.service.EvidenceEvaluator;
import com.cue.evaluation.service.IdeaEvaluationPipeline;
import com.cue.evaluation.service.TrendEvaluator;
import com.cue.global.ai.GeminiClient;
import com.cue.global.ai.PromptLoader;
import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import com.cue.idea.entity.Idea;
import com.cue.idea.entity.IdeaType;
import com.cue.idea.repository.IdeaRepository;
import com.cue.source.client.SourceClient;
import com.cue.source.config.SourceApiProperties;
import com.cue.source.dto.EvaluatedItem;
import com.cue.source.dto.SourceItem;
import com.cue.source.entity.ApiProvider;
import com.cue.source.entity.Source;
import com.cue.source.repository.SourceRepository;
import com.cue.source.service.SourceCheckService;
import com.cue.source.service.SourceExcerptService;
import com.cue.source.service.SourceHasher;
import com.cue.trend.service.GlobalTrendService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class FullEvaluationEvidenceTest {
    @Test
    void 전체_평가는_모든_자료를_쓰고_일반_재평가는_추가와_삭제만_넘긴다() {
        long ideaId = 1L;
        var idea = new Idea(null, IdeaType.IT, "문제", "타깃", "해결책", "기대 효과");
        var ideas = mock(IdeaRepository.class);
        when(ideas.findById(ideaId)).thenReturn(Optional.of(idea));

        var first = new SourceItem("GitHub 자료", "https://example.test/github", "설명");
        var second = new SourceItem("Stack Exchange 자료", "https://example.test/stack", "설명");
        var githubSource = new Source(idea, ApiProvider.GITHUB, "keyword");
        var stackSource = new Source(idea, ApiProvider.STACK_EXCHANGE, "keyword");
        githubSource.updateHash(SourceHasher.hash(List.of(first)));
        stackSource.updateHash(SourceHasher.hash(List.of(second)));
        ReflectionTestUtils.setField(githubSource, "id", 1L);
        ReflectionTestUtils.setField(stackSource, "id", 2L);
        var sources = mock(SourceRepository.class);
        when(sources.findByIdeaId(ideaId)).thenReturn(List.of(githubSource, stackSource));

        var github = mock(SourceClient.class);
        when(github.provider()).thenReturn(ApiProvider.GITHUB);
        when(github.search("keyword")).thenReturn(List.of(first));
        var stack = mock(SourceClient.class);
        when(stack.provider()).thenReturn(ApiProvider.STACK_EXCHANGE);
        when(stack.search("keyword")).thenReturn(List.of(second));
        var sourceProps = mock(SourceApiProperties.class);
        when(sourceProps.topN()).thenReturn(5);
        when(sourceProps.excerptChars()).thenReturn(800);

        var tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(call -> ((TransactionCallback<?>) call.getArgument(0)).doInTransaction(null));
        var gemini = mock(GeminiClient.class);
        when(gemini.available()).thenReturn(true);
        var prompts = new ArrayList<String>();
        var mapper = JsonMapper.builder().build();
        when(gemini.generateJson(anyString(), anyMap())).thenAnswer(call -> {
            String prompt = call.getArgument(0);
            prompts.add(prompt);
            return mapper.readTree(prompt.contains("[자료 목록]") ? "{\"scores\":[]}" : "{\"changes\":[]}");
        });
        var clients = List.of(github, stack);
        var recorder = mock(EvaluationRecorder.class); // 저장 대신 반영한 항목만 source에 기록한다
        when(recorder.recordResults(eq(ideaId), anyList(), anyBoolean(), any(), anyMap())).thenAnswer(call -> {
            Map<Long, List<EvaluatedItem>> reflected = call.getArgument(4);
            reflected.forEach((id, items) -> (id == 1L ? githubSource : stackSource).recordEvaluatedItems(items));
            return false;
        });
        var pipeline = new IdeaEvaluationPipeline(ideas, sources, new SourceCheckService(sources, clients),
                recorder, new EvidenceEvaluator(gemini, new PromptLoader()),
                mock(TrendEvaluator.class), mock(GlobalTrendService.class), new SourceExcerptService(clients, sourceProps),
                gemini, tx, null, null, mock(EvaluationRepository.class), mock(EvaluationPolicy.class));

        pipeline.evaluate(ideaId);
        assertEquals(1, prompts.size());
        assertTrue(prompts.getFirst().contains(first.url()));
        assertTrue(prompts.getFirst().contains(second.url()));

        idea.completeFullEvaluation();
        assertEquals(EvaluationRunResult.Status.NO_CHANGE, pipeline.evaluate(ideaId).status());
        assertEquals(1, prompts.size());

        var replacement = new SourceItem("새 GitHub 자료", "https://example.test/new", "새 설명");
        when(github.search("keyword")).thenReturn(List.of(replacement));
        pipeline.evaluate(ideaId);
        assertEquals(2, prompts.size());
        // 재평가: 새 항목은 [새로 추가된 자료], 사라진 항목은 [삭제된 자료]에만 들어가고 그대로인 항목은 다시 보내지 않는다
        String reEvaluation = prompts.getLast();
        int addedAt = reEvaluation.lastIndexOf("[새로 추가된 자료]");
        int removedAt = reEvaluation.lastIndexOf("[삭제된 자료]");
        assertTrue(reEvaluation.substring(addedAt, removedAt).contains(replacement.url()));
        assertTrue(reEvaluation.substring(removedAt).contains(first.url()));
        assertFalse(reEvaluation.contains(second.url()));

        // 사용자 요청 재평가: 바뀐 것이 없어도 호출하고, 그대로인 자료를 함께 넘긴다
        idea.completeTrendEvaluation(1L);
        assertEquals(EvaluationRunResult.Status.EVALUATED, pipeline.evaluate(ideaId, true).status());
        assertEquals(3, prompts.size());
        String requested = prompts.getLast();
        String kept = requested.substring(requested.lastIndexOf("[그대로인 자료]"), requested.lastIndexOf("[삭제된 자료]"));
        assertTrue(kept.contains(replacement.url()));
        assertTrue(kept.contains(second.url()));

        // 모든 자료 조회가 실패하면 자료 없이 점수를 다시 매기지 않는다
        when(github.search("keyword")).thenThrow(new CustomException(ErrorCode.EXTERNAL_SERVER_ERROR));
        when(stack.search("keyword")).thenThrow(new CustomException(ErrorCode.EXTERNAL_SERVER_ERROR));
        assertEquals(ErrorCode.EXTERNAL_SERVER_ERROR,
                assertThrows(CustomException.class, () -> pipeline.evaluate(ideaId, true)).getErrorCode());
        assertEquals(3, prompts.size());
    }
}
