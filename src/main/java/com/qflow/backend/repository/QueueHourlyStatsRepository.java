// src/main/java/com/qflow/backend/repository/QueueHourlyStatsRepository.java
package com.qflow.backend.repository;

import com.qflow.backend.model.QueueHourlyStats;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.time.LocalDateTime;
import java.util.List;

public interface QueueHourlyStatsRepository extends MongoRepository<QueueHourlyStats, String> {
    List<QueueHourlyStats> findByQueueIdAndHourBetween(String queueId, LocalDateTime start, LocalDateTime end);
    void deleteByHourBefore(LocalDateTime cutoff);
    List<QueueHourlyStats> findByQueueIdInAndHourBetween(List<String> queueIds, LocalDateTime start, LocalDateTime end);
}