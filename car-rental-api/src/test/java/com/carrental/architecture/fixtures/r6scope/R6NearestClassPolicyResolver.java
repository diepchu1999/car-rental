package com.carrental.architecture.fixtures.r6scope;

enum OwnershipType {
    COMPANY,
    PARTNER
}

enum RentalType {
    HOURLY,
    DAILY
}

final class R6NearestClassPolicyResolver {

    static final class NestedHelper {

        boolean branchesOnOwnershipType(
                OwnershipType ownershipType
        ) {
            return ownershipType == OwnershipType.COMPANY;
        }
    }

    Runnable anonymousBranch(
            RentalType rentalType
    ) {
        return new Runnable() {
            @Override
            public void run() {
                String ignored = switch (rentalType) {
                    case HOURLY -> "hourly";
                    case DAILY -> "daily";
                };
            }
        };
    }
}