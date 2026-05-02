package com.parkride.parking.domain;

/** Physical type of a parking slot — determines pricing and vehicle eligibility. */
public enum SlotType {

    /** Standard 4-wheeler car bay. */
    CAR,

    /** Covered or uncovered 2-wheeler bay. */
    BIKE,

    /** EV charging bay — includes a Type-2 or CCS charger. */
    EV,

    /** Accessible bay reserved for differently-abled permit holders. */
    DISABLED
}
