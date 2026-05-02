package com.parkride.parking.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.parkride.parking.domain.Booking;
import com.parkride.parking.domain.BookingStatus;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Builder
public class BookingResponse {

    @JsonProperty("id")          private UUID          id;
    @JsonProperty("userId")      private UUID          userId;
    @JsonProperty("slotId")      private UUID          slotId;
    @JsonProperty("slotNumber")  private String        slotNumber;
    @JsonProperty("lotId")       private UUID          lotId;
    @JsonProperty("lotName")     private String        lotName;
    @JsonProperty("startTime")   private Instant       startTime;
    @JsonProperty("endTime")     private Instant       endTime;
    @JsonProperty("status")      private BookingStatus status;
    @JsonProperty("totalAmount") private BigDecimal    totalAmount;
    @JsonProperty("qrToken")     private String        qrToken;
    @JsonProperty("checkInTime") private Instant       checkInTime;
    @JsonProperty("createdAt")   private Instant       createdAt;

    public static BookingResponse from(Booking b) {
        return BookingResponse.builder()
                .id(b.getId())
                .userId(b.getUserId())
                .slotId(b.getSlot().getId())
                .slotNumber(b.getSlot().getSlotNumber())
                .lotId(b.getSlot().getLot().getId())
                .lotName(b.getSlot().getLot().getName())
                .startTime(b.getStartTime())
                .endTime(b.getEndTime())
                .status(b.getStatus())
                .totalAmount(b.getTotalAmount())
                .qrToken(b.getQrToken())
                .checkInTime(b.getCheckInTime())
                .createdAt(b.getCreatedAt())
                .build();
    }
}
