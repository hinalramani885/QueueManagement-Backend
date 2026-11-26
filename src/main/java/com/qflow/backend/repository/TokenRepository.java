package com.qflow.backend.repository;

import com.qflow.backend.enums.Role;
import com.qflow.backend.model.Token;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface TokenRepository extends MongoRepository<Token, String> {
    Optional<Token> findByTokenValue(String tokenValue);
    // In TokenRepository.java
    List<Token> findByCreatedByAdminIdAndRole(String createdByAdminId, Role role);
}