-- 아이디어 원문(조회·수정용, 평가에는 쓰지 않는다). 이전에 저장한 아이디어는 NULL.
ALTER TABLE ideas ADD COLUMN raw_text TEXT;
-- 즐겨찾기 (목록 필터용)
ALTER TABLE ideas ADD COLUMN favorite BOOLEAN NOT NULL DEFAULT FALSE;
-- AI 평가를 마지막으로 끝까지 마친 시각. 하루 1회 제한을 껐을 때 수동 재평가 대기 시간의 기준.
ALTER TABLE ideas ADD COLUMN evaluation_completed_at TIMESTAMP;
