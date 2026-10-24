CREATE TABLE policy_version_validation (
    version_id bigint NOT NULL,
    toolchain_id text NOT NULL,
    status text NOT NULL CHECK (status IN ('valid', 'invalid')),
    diagnostics jsonb,
    diagnostics_schema int NOT NULL DEFAULT 1,
    run_id bigint,
    checked_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (version_id, toolchain_id)
);

CREATE TABLE policy_revalidation_run (
    id bigserial PRIMARY KEY,
    toolchain_id text NOT NULL,
    scope text NOT NULL CHECK (scope IN ('all', 'tenant')),
    scope_tenant text,
    status text NOT NULL CHECK (status IN ('queued', 'running', 'done', 'failed')),
    cursor bigint NOT NULL DEFAULT 0,
    scope_max_version_id bigint NOT NULL,
    heartbeat_at timestamptz,
    total int NOT NULL DEFAULT 0,
    checked int NOT NULL DEFAULT 0,
    invalid_count int NOT NULL DEFAULT 0,
    started_at timestamptz,
    finished_at timestamptz,
    error text
);

CREATE UNIQUE INDEX uq_policy_revalidation_active_scope
    ON policy_revalidation_run (toolchain_id, scope, coalesce(scope_tenant, ''))
    WHERE status IN ('queued', 'running');
