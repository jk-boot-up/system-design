package com.jkbootup.ecommerce.inventory.domain;

import java.util.EnumSet;
import java.util.Set;

/** Lifecycle of a stock reservation (State pattern on an enum). */
public enum ReservationStatus {
    RESERVED, COMMITTED, RELEASED, EXPIRED, RETURNED;

    private Set<ReservationStatus> next() {
        return switch (this) {
            case RESERVED -> EnumSet.of(COMMITTED, RELEASED, EXPIRED);
            case COMMITTED -> EnumSet.of(RETURNED);
            case RELEASED, EXPIRED, RETURNED -> EnumSet.noneOf(ReservationStatus.class);
        };
    }

    public boolean canMoveTo(ReservationStatus target) {
        return next().contains(target);
    }
}
