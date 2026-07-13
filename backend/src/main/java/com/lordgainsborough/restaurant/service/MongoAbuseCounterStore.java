package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.SignupAbuseLimit;
import com.lordgainsborough.restaurant.model.SignupAbuseLimit.BucketType;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class MongoAbuseCounterStore implements AbuseCounterStore {

    private final MongoTemplate mongoTemplate;

    public MongoAbuseCounterStore(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public long incrementAndGet(
            String id,
            BucketType type,
            String subjectHash,
            Instant windowStart,
            Instant expiresAt) {
        Query query = new Query(Criteria.where("_id").is(id));
        Update update = new Update()
                .inc("count", 1)
                .setOnInsert("bucketType", type)
                .setOnInsert("subjectHash", subjectHash)
                .setOnInsert("windowStart", windowStart)
                .setOnInsert("expiresAt", expiresAt);

        SignupAbuseLimit updated = mongoTemplate.findAndModify(
                query,
                update,
                FindAndModifyOptions.options().upsert(true).returnNew(true),
                SignupAbuseLimit.class);

        return updated != null ? updated.getCount() : 1L;
    }

    @Override
    public long getCount(String id, Instant now) {
        SignupAbuseLimit existing = mongoTemplate.findById(id, SignupAbuseLimit.class);
        if (existing == null || existing.getExpiresAt() == null || !existing.getExpiresAt().isAfter(now)) {
            return 0L;
        }
        return existing.getCount();
    }

    @Override
    public void clear() {
        mongoTemplate.remove(new Query(), SignupAbuseLimit.class);
    }
}
