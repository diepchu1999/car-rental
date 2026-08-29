package com.carrental.architecture.fixtures.r6allowed;

enum OwnershipType {
    COMPANY,
    PARTNER
}

enum RentalType {
    HOURLY,
    DAILY
}

final class R6AllowedPolicyResolver {

    boolean ownershipUsesEquality(OwnershipType ownershipType) {
        return ownershipType == OwnershipType.COMPANY;
    }

    boolean rentalUsesEquality(RentalType rentalType) {
        return rentalType == RentalType.DAILY;
    }

    String ownershipUsesSwitch(OwnershipType ownershipType) {
        return switch (ownershipType) {
            case COMPANY -> "company";
            case PARTNER -> "partner";
        };
    }

    String rentalUsesSwitch(RentalType rentalType) {
        return switch (rentalType) {
            case HOURLY -> "hourly";
            case DAILY -> "daily";
        };
    }
}