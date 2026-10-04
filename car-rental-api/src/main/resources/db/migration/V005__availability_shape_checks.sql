-- Ép cặp loại/trạng thái theo status-flow §2; không sửa V004 đã chạy.
ALTER TABLE availability.reservation
    ADD CONSTRAINT chk_kind_status CHECK (
        (kind = 'RENTAL' AND status IN ('HELD', 'CONFIRMED', 'IN_USE', 'RELEASED', 'COMPLETED'))
        OR (kind <> 'RENTAL' AND status IN ('BLOCKED', 'COMPLETED'))
    ),
    -- BR-015: khóa giấy tờ luôn không chặn trên và luôn BLOCKED.
    -- Cùng chk_unbounded_only_compliance của V004 bảo vệ cả hai chiều.
    ADD CONSTRAINT chk_compliance_open_blocked CHECK (
        kind <> 'COMPLIANCE_HOLD' OR (upper_inf(period) AND status = 'BLOCKED')
    );
