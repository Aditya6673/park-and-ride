package com.parkride.parking.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.parkride.parking.domain.ParkingLot;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Builder
public class ParkingLotResponse {

    @JsonProperty("id")           private UUID    id;
    @JsonProperty("name")         private String  name;
    @JsonProperty("address")      private String  address;
    @JsonProperty("city")         private String  city;
    @JsonProperty("state")        private String  state;
    @JsonProperty("latitude")     private Double  latitude;
    @JsonProperty("longitude")    private Double  longitude;
    @JsonProperty("totalSlots")   private int     totalSlots;
    @JsonProperty("contactPhone") private String  contactPhone;
    @JsonProperty("description")  private String  description;
    @JsonProperty("imageUrl")     private String  imageUrl;
    @JsonProperty("active")       private boolean active;
    @JsonProperty("createdAt")    private Instant createdAt;

    /** Populated at query time from Redis — real-time available slot count. */
    @JsonProperty("availableSlots") private long availableSlots;

    public static ParkingLotResponse from(ParkingLot lot, long availableSlots) {
        return ParkingLotResponse.builder()
                .id(lot.getId())
                .name(lot.getName())
                .address(lot.getAddress())
                .city(lot.getCity())
                .state(lot.getState())
                .latitude(lot.getLatitude())
                .longitude(lot.getLongitude())
                .totalSlots(lot.getTotalSlots())
                .contactPhone(lot.getContactPhone())
                .description(lot.getDescription())
                .imageUrl(lot.getImageUrl())
                .active(lot.isActive())
                .createdAt(lot.getCreatedAt())
                .availableSlots(availableSlots)
                .build();
    }
}
