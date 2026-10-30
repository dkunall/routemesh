package com.routemesh.idempotency.adapter;

import com.routemesh.idempotency.application.IdempotencyStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
@Transactional(propagation = Propagation.REQUIRES_NEW)
class JdbcIdempotencyStore implements IdempotencyStore {

    private final JdbcClient db;

    JdbcIdempotencyStore(JdbcClient db) {
        this.db = db;
    }

    @Override
    public boolean claim(String scope, String subject, String key, String requestHash, Instant now) {
        return db.sql("""
                        insert into idempotency_record (scope, subject, key, request_hash, status, created_at)
                        values (?, ?, ?, ?, 'IN_PROGRESS', ?) on conflict do nothing""")
                .params(scope, subject, key, requestHash, Timestamp.from(now)).update() == 1;
    }

    @Override
    public Optional<Existing> find(String scope, String subject, String key) {
        return db.sql("""
                        select request_hash, status, response_body, created_at from idempotency_record
                        where scope = ? and subject = ? and key = ?""")
                .params(scope, subject, key)
                .query((rs, i) -> new Existing(rs.getString(1), "DONE".equals(rs.getString(2)), rs.getString(3),
                        rs.getTimestamp(4).toInstant()))
                .optional();
    }

    @Override
    public boolean takeOver(String scope, String subject, String key, String requestHash, Instant staleBefore,
                            Instant now) {
        return db.sql("""
                        update idempotency_record set created_at = ?
                        where scope = ? and subject = ? and key = ? and request_hash = ?
                          and status = 'IN_PROGRESS' and created_at < ?""")
                .params(Timestamp.from(now), scope, subject, key, requestHash, Timestamp.from(staleBefore))
                .update() == 1;
    }

    @Override
    public void complete(String scope, String subject, String key, String responseBody) {
        db.sql("""
                        update idempotency_record set status = 'DONE', response_status = 200, response_body = ?
                        where scope = ? and subject = ? and key = ?""")
                .params(responseBody, scope, subject, key).update();
    }

    @Override
    public void release(String scope, String subject, String key) {
        db.sql("delete from idempotency_record where scope = ? and subject = ? and key = ? and status = 'IN_PROGRESS'")
                .params(scope, subject, key).update();
    }
}
