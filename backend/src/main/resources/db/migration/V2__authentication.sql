-- A username is stored in lower case and is its own key: the check makes the unique constraint blind to case. An
-- account has no username between the confirmation of its code and the choice of its username.
-- public_id: the identifier of an account for its own browser, random, so that it tells neither the email nor the
-- order of creation, as the internal identifier does.
-- last_seen_at: the last opening of a session for the account, never each request.
CREATE TABLE auth_accounts (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username VARCHAR(32),
    email_key VARCHAR(320) NOT NULL UNIQUE,
    password_hash VARCHAR(512),
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    role VARCHAR(16) NOT NULL DEFAULT 'JEUNE' CHECK (role IN ('JEUNE', 'ADMIN')),
    credential_version BIGINT NOT NULL DEFAULT 0,
    terms_accepted_at TIMESTAMPTZ,
    public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ,
    CONSTRAINT auth_accounts_public_id_key UNIQUE (public_id),
    CONSTRAINT auth_accounts_username_key UNIQUE (username),
    CONSTRAINT auth_accounts_username_lower_check CHECK (username = lower(username))
);

-- rotated_at: date of issue of the current secret, its creation then each rotation. It tells a replay of the previous
-- secret that follows a lost response (tolerated) from a reuse of a stolen cookie (revokes the family).
CREATE TABLE auth_reconnections (
    id UUID PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES auth_accounts(id) ON DELETE CASCADE,
    credential_version BIGINT NOT NULL,
    secret_hash VARCHAR(64) NOT NULL,
    previous_hash VARCHAR(64),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    rotated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX auth_reconnections_account_idx ON auth_reconnections(account_id);
CREATE INDEX auth_reconnections_expiry_idx ON auth_reconnections(expires_at);

-- confirmation_key_hash: the hash of the key that the page of the request holds. A code confirms the registration only
-- for the page that sends this key.
-- code_failures, codes_blocked_until: the failed attempts of the registration, over all its codes, and the end of the
-- hour without any code after every 15 of them.
CREATE TABLE auth_registrations (
    id UUID PRIMARY KEY,
    email_key VARCHAR(320),
    password_hash VARCHAR(512),
    expires_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'CONFIRMED', 'EXPIRED')),
    account_id BIGINT REFERENCES auth_accounts(id) ON DELETE SET NULL,
    confirmation_key_hash VARCHAR(64),
    code_failures INTEGER NOT NULL DEFAULT 0 CHECK (code_failures >= 0),
    codes_blocked_until TIMESTAMPTZ,
    CONSTRAINT auth_registrations_pending_check
        CHECK (status <> 'PENDING' OR (email_key IS NOT NULL AND password_hash IS NOT NULL)),
    CONSTRAINT auth_registrations_erased_check
        CHECK (status = 'PENDING' OR (email_key IS NULL AND password_hash IS NULL))
);
CREATE UNIQUE INDEX auth_registrations_email_idx ON auth_registrations(email_key) WHERE status = 'PENDING';
CREATE INDEX auth_registrations_expiry_idx ON auth_registrations(expires_at) WHERE status = 'PENDING';
-- The purge deletes the registrations no longer pending: the index of the expiry of the pending ones cannot serve it.
CREATE INDEX auth_registrations_retention_idx ON auth_registrations(expires_at) WHERE status <> 'PENDING';

-- Confirmation codes retain only an operation-scoped bearer hash. failed_attempts: the failed attempts of one code.
CREATE TABLE auth_verifications (
    id UUID PRIMARY KEY,
    registration_id UUID NOT NULL REFERENCES auth_registrations(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL,
    key_id VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'REPLACED', 'USED')),
    created_at TIMESTAMPTZ NOT NULL,
    failed_attempts SMALLINT NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0)
);
CREATE UNIQUE INDEX auth_verifications_active_idx ON auth_verifications(registration_id) WHERE status = 'PENDING';
-- Deleting a registration deletes its codes by the foreign key: without this index, each deletion reads the whole table.
CREATE INDEX auth_verifications_registration_idx ON auth_verifications(registration_id);

CREATE TABLE auth_password_resets (
    id UUID PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES auth_accounts(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL,
    key_id VARCHAR(64) NOT NULL,
    credential_version BIGINT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'REPLACED', 'USED')),
    consumed_at TIMESTAMPTZ,
    CONSTRAINT auth_password_resets_consumed_check CHECK ((status = 'USED') = (consumed_at IS NOT NULL))
);
CREATE UNIQUE INDEX auth_password_resets_pending_idx ON auth_password_resets(account_id) WHERE status = 'PENDING';
CREATE INDEX auth_password_resets_expiry_idx ON auth_password_resets(expires_at);

CREATE TABLE auth_google_identities (
    subject VARCHAR(255) PRIMARY KEY,
    account_id BIGINT NOT NULL UNIQUE REFERENCES auth_accounts(id) ON DELETE CASCADE
);

-- Durable consumption prevents concurrent callbacks and late JDBC session saves from replaying state.
CREATE TABLE auth_google_flows (
    state_hash VARCHAR(64) PRIMARY KEY,
    browser_hash VARCHAR(64) NOT NULL,
    account_id BIGINT REFERENCES auth_accounts(id) ON DELETE CASCADE,
    credential_version BIGINT,
    family_id UUID,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed BOOLEAN NOT NULL DEFAULT FALSE,
    superseded BOOLEAN NOT NULL DEFAULT FALSE,
    issued_family_id UUID,
    CONSTRAINT auth_google_flows_scope_check
        CHECK ((account_id IS NULL AND credential_version IS NULL AND family_id IS NULL)
            OR (account_id IS NOT NULL AND credential_version IS NOT NULL AND family_id IS NOT NULL))
);
CREATE INDEX auth_google_flows_expiry_idx ON auth_google_flows(expires_at);
CREATE UNIQUE INDEX auth_google_flows_browser_idx ON auth_google_flows(browser_hash) WHERE NOT superseded;

CREATE TABLE auth_google_pending (
    id UUID PRIMARY KEY,
    browser_hash VARCHAR(64) NOT NULL,
    flow_hash VARCHAR(64) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    email_key VARCHAR(320) NOT NULL,
    authoritative_email BOOLEAN NOT NULL,
    access_verified BOOLEAN NOT NULL DEFAULT FALSE,
    account_id BIGINT REFERENCES auth_accounts(id) ON DELETE CASCADE,
    credential_version BIGINT,
    family_id UUID,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    CONSTRAINT auth_google_pending_scope_check
        CHECK ((account_id IS NULL AND credential_version IS NULL AND family_id IS NULL)
            OR (account_id IS NOT NULL AND credential_version IS NOT NULL AND family_id IS NOT NULL))
);
CREATE INDEX auth_google_pending_expiry_idx ON auth_google_pending(expires_at);

CREATE TABLE auth_google_verifications (
    id UUID PRIMARY KEY,
    subject VARCHAR(255) NOT NULL,
    email_key VARCHAR(320) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    key_id VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'USED', 'REPLACED')),
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX auth_google_verifications_pending_idx ON auth_google_verifications(subject, email_key) WHERE status = 'PENDING';
CREATE INDEX auth_google_verifications_identity_idx ON auth_google_verifications(subject, email_key, expires_at);
-- The index of the identity starts with the subject, so it cannot serve the purge by expiry.
CREATE INDEX auth_google_verifications_expiry_idx ON auth_google_verifications(expires_at);
