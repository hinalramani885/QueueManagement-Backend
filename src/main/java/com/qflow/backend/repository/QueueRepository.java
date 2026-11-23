package com.qflow.backend.repository;

import com.qflow.backend.model.Queue;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.List;

public interface QueueRepository extends MongoRepository<Queue, String> {
    List<Queue> findByProviderId(String providerId);
    List<Queue> findByPlaceId(String placeId);
    List<Queue> findByServiceId(String serviceId);
    List<Queue> findByIsActive(boolean isActive);
    List<Queue> findByPlaceIdIn(List<String> placeIds);
}