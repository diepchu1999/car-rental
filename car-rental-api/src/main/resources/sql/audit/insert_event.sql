-- BR-130: không chứa số giấy tờ, ảnh, object key hoặc URL ký.
INSERT INTO audit.audit_event
    (actor_account_code, action, subject_account_code, resource_code, occurred_at, request_id)
VALUES (:actor, :action, :subject, :resource, :at, :requestId);
