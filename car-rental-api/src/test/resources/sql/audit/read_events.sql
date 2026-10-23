SELECT actor_account_code, action, subject_account_code, resource_code, occurred_at, request_id
FROM audit.audit_event WHERE request_id = :requestId ORDER BY id;
