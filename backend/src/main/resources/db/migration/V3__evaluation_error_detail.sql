-- 마지막 평가가 실패한 위치와, 외부 API를 쓰다 실패했으면 그 API와 원인. FAILED가 아니면 NULL
ALTER TABLE ideas ADD COLUMN last_evaluation_error_location VARCHAR(100);
ALTER TABLE ideas ADD COLUMN last_evaluation_error_api VARCHAR(100);
ALTER TABLE ideas ADD COLUMN last_evaluation_error_reason VARCHAR(255);
