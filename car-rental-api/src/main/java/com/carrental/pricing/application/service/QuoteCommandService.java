package com.carrental.pricing.application.service;

import com.carrental.booking.api.RentalTermsDirectory;
import com.carrental.pricing.application.command.CreateQuoteCommand;
import com.carrental.pricing.application.port.in.CreateQuoteUseCase;
import com.carrental.pricing.application.port.out.ReadPricingPolicyPort;
import com.carrental.pricing.application.port.out.ReadQuotePort;
import com.carrental.pricing.application.port.out.WriteQuotePort;
import com.carrental.pricing.application.view.QuoteDetail;
import com.carrental.pricing.domain.Quote;
import com.carrental.pricing.domain.QuoteSnapshot;
import com.carrental.shared.code.BusinessCodeGenerator;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.validation.Validations;
import com.carrental.vehicle.api.VehiclePricingDirectory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/** Điều phối BR-122/125, đóng băng dữ liệu ADR-0014; không kiểm hoặc ghi availability. */
@Service
class QuoteCommandService implements CreateQuoteUseCase {
    private static final int MAX_CODE_ATTEMPTS = 5; // Giới hạn kỹ thuật, cùng cơ chế sinh mã xe/chi nhánh.
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyMM", Locale.ROOT);
    private final RentalPricingPolicyResolver resolver;
    private final RentalTermsDirectory terms;
    private final ReadPricingPolicyPort parameters;
    private final VehiclePricingDirectory vehicles;
    private final WriteQuotePort writes;
    private final ReadQuotePort reads;
    private final BusinessCodeGenerator codes;
    private final Clock clock;

    /** Chỉ phụ thuộc các port và API module, không biết SQL hoặc adapter cấu hình. */
    QuoteCommandService(RentalPricingPolicyResolver resolver, RentalTermsDirectory terms,
            ReadPricingPolicyPort parameters, VehiclePricingDirectory vehicles,
            WriteQuotePort writes, ReadQuotePort reads, BusinessCodeGenerator codes, Clock clock) {
        this.resolver = resolver; this.terms = terms; this.parameters = parameters; this.vehicles = vehicles;
        this.writes = writes; this.reads = reads; this.codes = codes; this.clock = clock;
    }

    /** Thứ tự: hỗ trợ, điều kiện thuê, lịch lễ, xe ACTIVE, tính giá, ghi rồi reload; lỗi sau ghi rollback. */
    @Override
    @Transactional
    public QuoteDetail create(CreateQuoteCommand command) {
        Validations.required(command, "command");
        var policy = resolver.resolve(command.rentalType(), command.driveMode(), command.pickupMethod());
        terms.getTerms(command.rentalType(), command.pickupMethod(), command.startAt(), command.endAt());
        // PostgreSQL lưu timestamp tới micro giây; chụp mốc tạo ở cùng độ chính xác.
        Instant at = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var settings = parameters.parametersAt(at);
        var schedule = policy.prepare(command.startAt(), command.endAt(), settings, clock.getZone());
        var vehicle = vehicles.findActiveByCode(command.vehicleCode())
                .orElseThrow(() -> DomainException.notFound(ErrorCode.VEHICLE_NOT_FOUND));
        var price = schedule.calculate(vehicle.dailyRate(), settings.insurance());
        var snapshot = QuoteSnapshot.from(vehicle.dailyRate(), settings, schedule.insuranceBillingDays());
        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
            String code = "BG-" + MONTH.format(at.atZone(clock.getZone())) + "-" + codes.generate("BG").substring(3);
            var quote = Quote.create(code, vehicle.code(), command.startAt(), command.endAt(),
                    command.rentalType(), command.driveMode(), command.pickupMethod(), snapshot, price, at);
            if (writes.insert(quote)) {
                return reads.findByCode(code).orElseThrow(() ->
                        new IllegalStateException("Inserted quote could not be reloaded."));
            }
        }
        throw new IllegalStateException("Quote code generation exhausted its retry limit.");
    }
}
