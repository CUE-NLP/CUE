-- 아이디어 원문(조회·수정용, 평가에는 쓰지 않는다). 이전에 저장한 아이디어는 NULL.
ALTER TABLE ideas ADD COLUMN raw_text TEXT;
-- 즐겨찾기 (목록 필터용)
ALTER TABLE ideas ADD COLUMN favorite BOOLEAN NOT NULL DEFAULT FALSE;
