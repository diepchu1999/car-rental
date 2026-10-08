package com.carrental.vehicle.domain.policy;

/** Chính sách thế chấp đã phân giải theo BR-209; không biết hai trục phân loại. */
@FunctionalInterface
public interface CollateralPolicy {
    /** Cho biết khách có được miễn thế chấp trong trường hợp đã phân giải không. */
    boolean collateralFree();
}
