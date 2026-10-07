package com.carrental.vehicle.application.view;

import com.carrental.vehicle.domain.FuelType;
import com.carrental.vehicle.domain.OwnershipType;
import com.carrental.vehicle.domain.Transmission;

/**
 * Ứng viên đọc nội bộ để phân giải thế chấp BR-209.
 * Loại sở hữu chỉ đi từ persistence tới resolver, không được xuất qua vehicle.api (BR-112).
 */
public record VehicleSearchCandidate(long id, String code, long branchId,
        OwnershipType ownershipType, int seats, Transmission transmission,
        FuelType fuelType, String make, String model) {
}
