-- 가중치 변경은 즉시 적용하지 않고 다음 평가 턴부터 적용하기 위해 예약 값을 저장한다.
ALTER TABLE scores ADD COLUMN pending_weight DECIMAL(4, 3);
