package com.carrental.pricing.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

/** Kiểm bảo hiểm tách riêng, số nguyên hoặc tỷ lệ > 0 theo BR-231 và chốt Tech Owner. */
class InsuranceTermsTest {
    /** Phí cố định nhân số ngày rồi làm tròn một dòng; không làm tròn từng ngày bảo hiểm. */
    @Test
    void fixedInsuranceRoundsOneCombinedLine() {
        var terms = new InsuranceTerms(InsuranceCalculation.PER_BILLING_DAY, new BigDecimal("100500"));
        assertEquals(302_000, terms.fee(3_000_000, 3));
    }

    /** Tỷ lệ nhân tiền thuê đã làm tròn, không nhân tổng gồm bảo hiểm và không đặt trần 100%. */
    @Test
    void percentageInsuranceUsesRoundedRentalAmount() {
        var terms = new InsuranceTerms(InsuranceCalculation.PERCENT_OF_RENT, new BigDecimal("0.1"));
        assertEquals(69_000, terms.fee(685_000, 1));
        assertEquals(154_000, new InsuranceTerms(InsuranceCalculation.PERCENT_OF_RENT,
                new BigDecimal("2.2")).fee(70_000, 1));
    }

    /** Không cho giá trị 0 hoặc âm ở cả hai cách tính. */
    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "-0.1"})
    void rejectsNonpositiveValue(String raw) {
        for (var calculation : InsuranceCalculation.values()) {
            assertThrows(IllegalArgumentException.class,
                    () -> new InsuranceTerms(calculation, new BigDecimal(raw)));
        }
    }

    /** Phí cố định phải nguyên VND, tỷ lệ được có phần lẻ; không ép cố định thành số nguyên. */
    @Test
    void rejectsFractionalFixedFeeButAcceptsFractionalRate() {
        assertThrows(ArithmeticException.class, () -> new InsuranceTerms(
                InsuranceCalculation.PER_BILLING_DAY, new BigDecimal("100000.1")));
        assertEquals(new BigDecimal("0.12345"), new InsuranceTerms(
                InsuranceCalculation.PERCENT_OF_RENT, new BigDecimal("0.12345")).value());
    }
}
