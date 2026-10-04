package com.carrental.availability.application.query;

import com.carrental.availability.domain.ReservationPeriod;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Kiểm đầu vào tra cứu và việc giữ bản sao bất biến của tập xe ứng viên. */
class ListBusyVehiclesQueryTest {

    private static final Instant START = Instant.parse("2030-10-01T03:00:00Z");
    private static final Instant END = START.plusSeconds(7200);
    private static final ReservationPeriod PERIOD = ReservationPeriod.finite(START, END);

    /** Kiểm factory giữ khoảng chưa cộng đệm, loại ID trùng và không giữ danh sách có thể sửa. */
    @Test
    void copiesCandidatesWithoutApplyingBuffer() {
        var candidates = new ArrayList<>(List.of(41L, 42L, 41L));
        var query = ListBusyVehiclesQuery.from(START, END, Duration.ofHours(2), candidates);
        candidates.clear();
        assertEquals(PERIOD, query.rentalPeriod());
        assertEquals(Duration.ofHours(2), query.buffer());
        assertEquals(Set.of(41L, 42L), query.candidateIds());
        assertThrows(UnsupportedOperationException.class, () -> query.candidateIds().add(43L));
    }

    /** Kiểm tập rỗng và đệm bằng không là đầu vào hợp lệ. */
    @Test
    void acceptsEmptyCandidatesAndZeroBuffer() {
        var query = new ListBusyVehiclesQuery(PERIOD, Duration.ZERO, List.of());
        assertTrue(query.candidateIds().isEmpty());
        assertEquals(Duration.ZERO, query.buffer());
    }

    /** Kiểm đầu vào thiếu, khoảng vô hạn hoặc đảo, đệm âm và ID sai đều trả INVALID_REQUEST. */
    @ParameterizedTest
    @MethodSource("invalidInputs")
    void rejectsInvalidInputs(Runnable creation) {
        DomainException failure = assertThrows(DomainException.class, creation::run);
        assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode());
        assertEquals(DomainException.Category.INVALID_INPUT, failure.category());
    }

    /** Cung cấp cả lối constructor và factory để tránh bỏ lọt validation ở một lối vào. */
    private static Stream<Runnable> invalidInputs() {
        return Stream.of(
                () -> new ListBusyVehiclesQuery(null, Duration.ZERO, List.of(1L)),
                () -> new ListBusyVehiclesQuery(ReservationPeriod.unboundedFrom(START), Duration.ZERO, List.of(1L)),
                () -> new ListBusyVehiclesQuery(PERIOD, null, List.of(1L)),
                () -> new ListBusyVehiclesQuery(PERIOD, Duration.ofNanos(-1), List.of(1L)),
                () -> new ListBusyVehiclesQuery(PERIOD, Duration.ZERO, null),
                () -> new ListBusyVehiclesQuery(PERIOD, Duration.ZERO, Arrays.asList(1L, null)),
                () -> new ListBusyVehiclesQuery(PERIOD, Duration.ZERO, List.of(0L)),
                () -> new ListBusyVehiclesQuery(PERIOD, Duration.ZERO, List.of(-1L)),
                () -> ListBusyVehiclesQuery.from(null, END, Duration.ZERO, List.of()),
                () -> ListBusyVehiclesQuery.from(START, null, Duration.ZERO, List.of()),
                () -> ListBusyVehiclesQuery.from(START, START, Duration.ZERO, List.of()),
                () -> ListBusyVehiclesQuery.from(END, START, Duration.ZERO, List.of())
        );
    }
}
