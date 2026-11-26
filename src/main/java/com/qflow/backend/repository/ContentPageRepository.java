package com.qflow.backend.repository;

import com.qflow.backend.model.ContentPage;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface ContentPageRepository extends MongoRepository<ContentPage, String> {
    Optional<ContentPage> findBySlug(String slug);

    void deleteBySlug(String slug);
}
