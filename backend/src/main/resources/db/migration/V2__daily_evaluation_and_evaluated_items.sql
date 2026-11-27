-- 평가를 마지막으로 끝까지 마친 날짜 (하루 한 번 제한). 점수가 안 바뀐 평가도 기록한다
ALTER TABLE ideas ADD COLUMN evaluated_on DATE;

-- 점수에 마지막으로 반영한 항목 [{"url","title"}] JSON. 다음 조회와 비교해 추가·삭제를 가려낸다. NULL이면 아직 기록 없음
ALTER TABLE sources ADD COLUMN evaluated_items TEXT;
