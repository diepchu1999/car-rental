package com.carrental.architecture.fixtures.r6;

enum OwnershipType {
    COMPANY,
    PARTNER
}

enum RentalType {
    HOURLY,
    DAILY
}

final class R6PolicyBranchViolation {

    private final OwnershipType storedOwnershipType;

    R6PolicyBranchViolation(OwnershipType storedOwnershipType) {
        this.storedOwnershipType = storedOwnershipType;
    }

    boolean branchesOnOwnershipType(OwnershipType ownershipType) {
        if (ownershipType == OwnershipType.COMPANY) {
            return true;
        }

        return false;
    }

    String branchesOnRentalType(RentalType rentalType) {
        return switch (rentalType) {
            case HOURLY -> "hourly";
            case DAILY -> "daily";
        };
    }
}