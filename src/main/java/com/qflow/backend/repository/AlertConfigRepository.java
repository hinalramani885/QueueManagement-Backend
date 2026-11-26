// src/main/java/com/qflow/backend/repository/AlertConfigRepository.java
package com.qflow.backend.repository;

import com.qflow.backend.model.AlertConfig;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.Optional;

public interface AlertConfigRepository extends MongoRepository<AlertConfig, String> {
    Optional<AlertConfig> findByAdminId(String adminId);
}