package com.cue.idea.service;

import com.cue.evaluation.repository.EvaluationRepository;
import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import com.cue.idea.dto.CreateIdeaRequest;
import com.cue.idea.dto.IdeaResponse;
import com.cue.idea.dto.ScoreResponse;
import com.cue.idea.entity.Idea;
import com.cue.idea.entity.IdeaType;
import com.cue.idea.repository.IdeaRepository;
import com.cue.user.repository.UserRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdeaService {
    private final IdeaRepository ideas;
    private final UserRepository users;
    private final EvaluationRepository evaluations;

    public IdeaService(IdeaRepository ideas, UserRepository users, EvaluationRepository evaluations) {
        this.ideas = ideas;
        this.users = users;
        this.evaluations = evaluations;
    }

    /** 검증을 마친 구조화 결과를 저장한다. 점수는 첫 AI 평가 전까지 0. */
    @Transactional
    public IdeaResponse create(Long userId, CreateIdeaRequest req) {
        var user = users.findById(userId).orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        IdeaType type = req.type() != null ? req.type() : IdeaType.IT;
        Idea saved = ideas.save(new Idea(user, type, req.problem(), req.target(), req.solution(), req.expectation()));
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public IdeaResponse get(Long userId, Long ideaId) {
        Idea idea = ideas.findById(ideaId).orElseThrow(() -> new CustomException(ErrorCode.IDEA_NOT_FOUND));
        if (!idea.getUser().getId().equals(userId)) {
            throw new CustomException(ErrorCode.IDEA_ACCESS_DENIED);
        }
        return toResponse(idea);
    }

    /** 리더보드: 로그인한 사용자의 아이디어를 총점 순으로 반환. */
    @Transactional(readOnly = true)
    public List<IdeaResponse> leaderboard(Long userId) {
        return ideas.findByUserIdOrderByTotalScoreDesc(userId).stream().map(this::toResponse).toList();
    }

    private IdeaResponse toResponse(Idea idea) {
        List<ScoreResponse> scores = idea.getScores().stream()
                .map(s -> new ScoreResponse(
                        s.getType(), s.getWeight(), s.getScore(),
                        evaluations.findFirstByScoreIdOrderByCreatedAtDesc(s.getId())
                                .map(e -> e.getDifference()).orElse(null)))
                .toList();
        return new IdeaResponse(idea.getId(), idea.getType(), idea.getProblem(), idea.getTarget(),
                idea.getSolution(), idea.getExpectation(), idea.getTotalScore(), idea.getCreatedAt(), scores);
    }
}
