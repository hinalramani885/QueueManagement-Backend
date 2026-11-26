package com.qflow.backend.repository;

import com.qflow.backend.model.OtpDocument;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.Optional;

public interface OtpRepository extends MongoRepository<OtpDocument, String> {
    Optional<OtpDocument> findByEmail(String email);
    void deleteByEmail(String email);
}
