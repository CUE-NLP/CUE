package com.cue.idea.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cue.evaluation.repository.EvaluationRepository;
import com.cue.evaluation.repository.IdeaScoreHistoryRepository;
import com.cue.idea.dto.UpdateIdeaRequest;
import com.cue.idea.entity.Idea;
import com.cue.idea.entity.IdeaType;
import com.cue.idea.entity.ItCategory;
import com.cue.idea.entity.KeywordOrigin;
import com.cue.idea.event.IdeaEvaluationRequested;
import com.cue.idea.repository.IdeaRepository;
import com.cue.source.repository.SourceRepository;
import com.cue.user.entity.User;
import com.cue.user.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

class IdeaTitleUpdateTest {
    @Test
    void 제목만_바꾸면_분류_소스_평가를_건드리지_않고_내용도_바꾸면_평가를_요청한다() {
        var user = mock(User.class);
        when(user.getId()).thenReturn(1L);
        var idea = new Idea(user, IdeaType.IT, "흩어진 질문", "개발자", "질문을 한 화면에 모은다.", "시간 절약");
        ReflectionTestUtils.setField(idea, "id", 2L);
        idea.applyKeywords(List.of("developer questions"), KeywordOrigin.AI);
        idea.completeFullEvaluation();
        idea.completeTrendEvaluation(4L);

        var ideas = mock(IdeaRepository.class);
        when(ideas.findByIdForUpdate(2L)).thenReturn(Optional.of(idea));
        var sources = mock(SourceRepository.class);
        var analyzer = mock(IdeaAnalyzer.class);
        var cache = mock(AnalysisCache.class);
        var events = mock(ApplicationEventPublisher.class);
        var service = new IdeaService(ideas, mock(UserRepository.class), mock(EvaluationRepository.class),
                mock(IdeaScoreHistoryRepository.class), sources, analyzer, cache, events);

        var renamed = service.update(1L, 2L, new UpdateIdeaRequest("  개발 질문 노트  ", null, null, null, null, null));

        assertThat(renamed.title()).isEqualTo("개발 질문 노트");
        assertThat(renamed.solution()).isEqualTo("질문을 한 화면에 모은다.");
        assertThat(idea.isNeedsFullEvaluation()).isFalse();
        assertThat(idea.getLastTrendId()).isEqualTo(4L);
        verifyNoInteractions(analyzer, cache, sources, events);

        when(analyzer.analyze(idea)).thenReturn(new IdeaAnalysis(ItCategory.DEV_TOOLS_INFRA,
                List.of("developer questions")));
        service.update(1L, 2L, new UpdateIdeaRequest("개발 질문 검색", null, null, "질문을 검색할 수 있게 한다.", null, null));

        assertThat(idea.isNeedsFullEvaluation()).isTrue();
        assertThat(idea.getLastTrendId()).isNull();
        verify(analyzer).analyze(idea);
        verify(sources).deleteUnlinkedByIdeaId(2L);
        verify(events).publishEvent(new IdeaEvaluationRequested(2L));
    }
}
