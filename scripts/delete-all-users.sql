-- Run manually against the intended application database only.
-- Permanently removes every account and dependent group, message, device,
-- invitation, session and authenticator enrollment. Preserves schema/migrations.
\set ON_ERROR_STOP on
BEGIN;
SET LOCAL lock_timeout = '10s';
TRUNCATE TABLE public.users CASCADE;
SELECT count(*) AS remaining_users FROM public.users;
COMMIT;
