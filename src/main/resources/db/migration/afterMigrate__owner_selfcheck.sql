DO $$
DECLARE
    mismatch TEXT := '';
    table_count INTEGER;
    sequence_count INTEGER;
    view_count INTEGER;
    check_mode TEXT;
BEGIN
    PERFORM set_config('aster.owner_check', '${ownerCheck}', true);
    check_mode := current_setting('aster.owner_check', true);
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'aster_migrator') THEN
        RAISE NOTICE 'owner-selfcheck: role aster_migrator missing, skipped';
        RETURN;
    END IF;

    IF (current_user = 'aster_migrator'
        OR EXISTS (SELECT 1 FROM pg_roles WHERE rolname = current_user AND rolsuper))
       AND EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'aster_api_user') THEN
        ALTER DEFAULT PRIVILEGES FOR ROLE aster_migrator IN SCHEMA public
            GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO aster_api_user;
        ALTER DEFAULT PRIVILEGES FOR ROLE aster_migrator IN SCHEMA public
            GRANT USAGE, SELECT ON SEQUENCES TO aster_api_user;
        IF to_regclass('public.audit_logs') IS NOT NULL THEN
            REVOKE UPDATE, DELETE, TRUNCATE ON TABLE public.audit_logs FROM aster_api_user;
        END IF;
        IF to_regclass('public.audit_chain_anchors') IS NOT NULL THEN
            REVOKE UPDATE, DELETE, TRUNCATE ON TABLE public.audit_chain_anchors FROM aster_api_user;
        END IF;
    ELSE
        RAISE NOTICE 'owner-selfcheck: current_user %, maintenance skipped', current_user;
    END IF;

    SELECT count(*) INTO table_count FROM pg_tables WHERE schemaname = 'public';
    SELECT count(*) INTO sequence_count FROM pg_sequences WHERE schemaname = 'public';
    SELECT count(*) INTO view_count FROM pg_views WHERE schemaname = 'public';

    IF NOT EXISTS (SELECT 1 FROM pg_namespace n JOIN pg_roles r ON r.oid = n.nspowner
                   WHERE n.nspname = 'public' AND r.rolname = 'aster_migrator') THEN
        mismatch := mismatch || ' schema public owner is not aster_migrator;';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tableowner <> 'aster_migrator') THEN
        mismatch := mismatch || ' table owner mismatch;';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_sequences WHERE schemaname = 'public' AND sequenceowner <> 'aster_migrator') THEN
        mismatch := mismatch || ' sequence owner mismatch;';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_views WHERE schemaname = 'public' AND viewowner <> 'aster_migrator') THEN
        mismatch := mismatch || ' view owner mismatch;';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'aster_api_user') THEN
        mismatch := mismatch || ' audit_logs ACL is not ar;';
    ELSIF to_regclass('public.audit_logs') IS NULL
       OR NOT has_table_privilege('aster_api_user', 'public.audit_logs', 'SELECT')
       OR NOT has_table_privilege('aster_api_user', 'public.audit_logs', 'INSERT')
       OR has_table_privilege('aster_api_user', 'public.audit_logs', 'UPDATE')
       OR has_table_privilege('aster_api_user', 'public.audit_logs', 'DELETE')
       OR has_table_privilege('aster_api_user', 'public.audit_logs', 'TRUNCATE') THEN
        mismatch := mismatch || ' audit_logs ACL is not ar;';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'aster_api_user') THEN
        mismatch := mismatch || ' policy_versions ACL is not arwd;';
    ELSIF to_regclass('public.policy_versions') IS NULL
       OR NOT has_table_privilege('aster_api_user', 'public.policy_versions', 'SELECT')
       OR NOT has_table_privilege('aster_api_user', 'public.policy_versions', 'INSERT')
       OR NOT has_table_privilege('aster_api_user', 'public.policy_versions', 'UPDATE')
       OR NOT has_table_privilege('aster_api_user', 'public.policy_versions', 'DELETE') THEN
        mismatch := mismatch || ' policy_versions ACL is not arwd;';
    END IF;

    IF mismatch <> '' THEN
        IF check_mode = 'strict' THEN
            RAISE EXCEPTION 'owner-selfcheck: mismatch: %', trim(mismatch);
        END IF;
        RAISE NOTICE 'owner-selfcheck: mismatch (lenient): %', trim(mismatch);
    ELSE
        RAISE NOTICE 'owner-selfcheck: ok (schema=aster_migrator tables=% sequences=% views=% audit=ar business=arwd)',
            table_count, sequence_count, view_count;
    END IF;
END
$$;
