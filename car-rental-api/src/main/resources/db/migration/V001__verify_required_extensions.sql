DO
$$
    DECLARE
        missing_extensions text;
    BEGIN
        SELECT string_agg(
                       required_extension.name,
                       ', ' ORDER BY required_extension.name
               )
        INTO missing_extensions
        FROM (
                 VALUES
                     ('btree_gist'),
                     ('pgcrypto'),
                     ('postgis')
             ) AS required_extension(name)
        WHERE NOT EXISTS (
            SELECT 1
            FROM pg_extension AS installed_extension
            WHERE installed_extension.extname = required_extension.name
        );

        IF missing_extensions IS NOT NULL THEN
            RAISE EXCEPTION USING
                MESSAGE = format(
                        'Required PostgreSQL extensions are missing: %s',
                        missing_extensions
                          ),
                HINT = 'Initialize PostgreSQL with infra/postgres/init/20-create-databases.sh.';
        END IF;
    END
$$;