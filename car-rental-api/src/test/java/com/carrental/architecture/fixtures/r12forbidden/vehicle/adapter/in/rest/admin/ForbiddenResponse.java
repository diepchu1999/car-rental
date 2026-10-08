package com.carrental.architecture.fixtures.r12forbidden.vehicle.adapter.in.rest.admin;

import java.math.BigDecimal;
import java.math.BigInteger;

/** Fixture R12 cố ý rò ID số: phủ sáu primitive, sáu wrapper và các kiểu Number. */
public record ForbiddenResponse(byte id, short shortId, int vehicleId, long branchId,
        float floatId, double doubleId, Byte byteId, Short boxedShortId, Integer integerId,
        Long longId, Float boxedFloatId, Double boxedDoubleId,
        BigInteger bigIntegerId, BigDecimal bigDecimalId, Number numberId) {
    /** Record lồng vẫn là response dù tên không kết thúc bằng Response. */
    public record Detail(long nestedId) {
    }
}
