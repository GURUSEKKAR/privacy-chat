#!/bin/sh
# Isolated recovery rehearsal for the localhost acceptance deployment. Does not replace live data.
set -eu
umask 077
container=secretchat-acceptance-postgres-1
restore_db=secretchat_restore_check
mkdir -p .local/restore-check
docker exec "$container" pg_dump -U secretchat --format=custom secretchat > .local/restore-check/snapshot.dump
docker exec "$container" createdb -U secretchat "$restore_db"
trap 'docker exec "$container" dropdb -U secretchat "$restore_db" >/dev/null; rm -f .local/restore-check/snapshot.dump' EXIT
docker exec -i "$container" pg_restore -U secretchat -d "$restore_db" < .local/restore-check/snapshot.dump
# Seed expired ciphertext only in the isolated copy, then round-trip that copy through pg_dump.
docker exec "$container" psql -U secretchat -d "$restore_db" -v ON_ERROR_STOP=1 -c "INSERT INTO messages (id,room_id,generation,sender_user_id,sender_device_id,epoch,ciphertext,idempotency_key,created_at,expires_at) SELECT gen_random_uuid(),id,generation,owner_user_id,owner_device_id,mls_epoch,decode('0001000265787069726564','hex'),'restore-expired-fixture',CURRENT_TIMESTAMP-INTERVAL '2 hours',CURRENT_TIMESTAMP-INTERVAL '1 hour' FROM rooms LIMIT 1;" >/dev/null
docker exec "$container" pg_dump -U secretchat --format=custom "$restore_db" > .local/restore-check/snapshot.dump
docker exec "$container" dropdb -U secretchat "$restore_db"
docker exec "$container" createdb -U secretchat "$restore_db"
docker exec -i "$container" pg_restore -U secretchat -d "$restore_db" < .local/restore-check/snapshot.dump
fixture=$(docker exec "$container" psql -U secretchat -d "$restore_db" -Atc "SELECT count(*) FROM messages WHERE idempotency_key='restore-expired-fixture' AND ciphertext IS NOT NULL")
test "$fixture" = 1
visible=$(docker exec "$container" psql -U secretchat -d "$restore_db" -Atc "SELECT count(*) FROM messages WHERE idempotency_key='restore-expired-fixture' AND deleted_at IS NULL AND expires_at > CURRENT_TIMESTAMP")
test "$visible" = 0
# Re-apply deletion to restored, expired ciphertext. Current reads must always use expires_at > now.
docker exec "$container" psql -U secretchat -d "$restore_db" -v ON_ERROR_STOP=1 -c "UPDATE messages SET ciphertext=NULL, deleted_at=CURRENT_TIMESTAMP, delete_reason='EXPIRED' WHERE expires_at <= CURRENT_TIMESTAMP AND deleted_at IS NULL; DELETE FROM messages WHERE deleted_at IS NOT NULL;" >/dev/null
remaining=$(docker exec "$container" psql -U secretchat -d "$restore_db" -Atc "SELECT count(*) FROM messages WHERE expires_at <= CURRENT_TIMESTAMP AND ciphertext IS NOT NULL")
test "$remaining" = 0
echo 'Isolated database dump, restore and expired ciphertext purge passed.'
