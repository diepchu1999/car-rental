-- Chứng minh Flyway áp dụng V004 bằng tài khoản ứng dụng không phải superuser.
SELECT history.version, history.success, history.installed_by,
       current_user AS database_user, role.rolsuper AS superuser
FROM public.flyway_schema_history AS history
JOIN pg_catalog.pg_roles AS role ON role.rolname = current_user
WHERE history.script = 'V004__availability_tables.sql';
