package com.qflow.backend.repository;

import com.qflow.backend.model.Service;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.List;

public interface ServiceRepository extends MongoRepository<Service, String> {
    List<Service> findByPlaceId(String placeId);
}