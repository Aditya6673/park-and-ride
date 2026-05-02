package com.parkride.parking.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.parkride.parking.domain.ParkingSlot;
import com.parkride.parking.domain.SlotStatus;
import com.parkride.parking.domain.SlotType;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Builder
public class ParkingSlotResponse {

    @JsonProperty("id")            private UUID       id;
    @JsonProperty("lotId")         private UUID       lotId;
    @JsonProperty("slotNumber")    private String     slotNumber;
    @JsonProperty("slotType")      private SlotType   slotType;
    @JsonProperty("status")        private SlotStatus status;
    @JsonProperty("pricePerHour")  private BigDecimal pricePerHour;
    @JsonProperty("floor")         private String     floor;
    @JsonProperty("active")        private boolean    active;

    public static ParkingSlotResponse from(ParkingSlot slot) {
        return ParkingSlotResponse.builder()
                .id(slot.getId())
                .lotId(slot.getLot().getId())
                .slotNumber(slot.getSlotNumber())
                .slotType(slot.getSlotType())
                .status(slot.getStatus())
                .pricePerHour(slot.getPricePerHour())
                .floor(slot.getFloor())
                .active(slot.isActive())
                .build();
    }
}
