package com.parkride.parking.domain;

/** Operational status of an individual parking slot. */
public enum SlotStatus {

    /** Ready to accept a booking. */
    AVAILABLE,

    /** A confirmed booking holds this slot for a future time window. */
    RESERVED,

    /** A vehicle is currently parked here (checked-in state). */
    OCCUPIED,

    /** Temporarily out of service (maintenance, damaged, cleaning). */
    MAINTENANCE
}
