package com.qflow.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Document(collection = "services")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Service {
    @Id
    private String id;

    @Field("placeId")
    @Indexed   // <-- added
    private String placeId;

    @Field("name")
    private String name;

    @Field("description")
    private String description;

    @Field("averageServiceTime")
    private Integer averageServiceTime;

    @Field("supportsGroupToken")
    private Boolean supportsGroupToken = false;

    @Field("emergencySupport")
    private Boolean emergencySupport = false;

    @Field("isActive")
    private Boolean isActive = true;

    // ---- Ownership (null on services created before providers could create services) ----
    @Field("createdBy")
    @Indexed
    private String createdBy;        // user id of the creator (admin or provider)

    @Field("createdByRole")
    private String createdByRole;    // "ADMIN" or "PROVIDER"

    @Field("adminId")
    @Indexed
    private String adminId;          // admin who owns the place this service belongs to
}