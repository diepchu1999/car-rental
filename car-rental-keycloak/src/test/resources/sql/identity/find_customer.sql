-- Đọc bằng connection độc lập sau request: chứng minh tài khoản/đồng ý đã commit thật.
SELECT u.id,
       (SELECT a.value FROM user_attribute a WHERE a.user_id = u.id AND a.name = 'terms_version') AS terms_version,
       (SELECT a.value FROM user_attribute a WHERE a.user_id = u.id AND a.name = 'consented_at') AS consented_at,
       EXISTS (SELECT 1 FROM user_required_action a WHERE a.user_id = u.id AND a.required_action = 'UPDATE_PASSWORD') AS password_update_required
FROM user_entity u
JOIN realm r ON r.id = u.realm_id
WHERE r.name = 'car-rental' AND u.username = ?;
