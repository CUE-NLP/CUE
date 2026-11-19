package com.cue.idea.event;

/** ACTIVE 아이디어가 새로 저장되거나 내용이 바뀐 직후 발행한다. 트랜잭션이 커밋된 뒤에 평가를 큐에 넣는 쪽이 받는다. */
public record IdeaEvaluationRequested(Long ideaId) {}
