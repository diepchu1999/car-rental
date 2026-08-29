package com.carrental.architecture.fixtures.r4.application.port.out;

import com.carrental.architecture.fixtures.r4.application.view.BookingDetail;
import com.carrental.architecture.fixtures.r4.application.view.BookingListItem;
import com.carrental.architecture.fixtures.r4.application.view.BookingSummary;

import java.util.List;
import java.util.Map;

public interface WriteBookingPort {

    void save(
            Map<
                    BookingDetail,
                    Map<
                            BookingListItem,
                            List<? super BookingSummary[]>
                            >
                    > input
    );
}