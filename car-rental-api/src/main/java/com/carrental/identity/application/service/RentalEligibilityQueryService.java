package com.carrental.identity.application.service;

import com.carrental.identity.application.port.in.CheckRentalEligibilityUseCase;
import com.carrental.identity.application.port.out.ReadDocumentSubmissionPort;
import com.carrental.identity.application.port.out.ReadLicenseClassPolicyPort;
import com.carrental.identity.application.query.CheckRentalEligibilityQuery;
import com.carrental.identity.application.view.ActiveDocumentDetail;
import com.carrental.identity.domain.DocumentType;
import com.carrental.identity.domain.RentalDocuments;
import com.carrental.shared.error.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Objects;

/** BR-106/106b/128/130: đọc một snapshot metadata active và bảng hạng, giao quy tắc cho domain. */
@Service
class RentalEligibilityQueryService implements CheckRentalEligibilityUseCase {
    private final ReadDocumentSubmissionPort documents;
    private final ReadLicenseClassPolicyPort policies;
    private final Clock clock;

    /** Chỉ nhận read port và đồng hồ ứng dụng; không đọc ảnh, giải mã hoặc tự truy cập cấu hình. */
    RentalEligibilityQueryService(ReadDocumentSubmissionPort documents, ReadLicenseClassPolicyPort policies, Clock clock) {
        this.documents = documents; this.policies = policies; this.clock = clock;
    }

    /** Mỗi lần gọi đọc lại cả hai bản active; không suy từ latest, không lọc mất bản đã hết hạn. */
    @Override @Transactional(readOnly = true)
    public void check(CheckRentalEligibilityQuery query) {
        Objects.requireNonNull(query, "query");
        LocalDate tripEndDate;
        try {
            tripEndDate = query.tripEnd().atZone(clock.getZone()).toLocalDate();
        } catch (DateTimeException failure) {
            throw DomainException.invalidInput("Trip end is outside the supported calendar range.");
        }
        var evaluatedAt = clock.instant();
        var selected = new EnumMap<DocumentType, ActiveDocumentDetail>(DocumentType.class);
        for (var row : documents.listActiveByOwner(query.accountCode())) {
            if (row == null || row.documentType() == null || row.expiresOn() == null
                    || selected.putIfAbsent(row.documentType(), row) != null) {
                throw new IllegalStateException("Active document lookup is inconsistent.");
            }
        }
        var card = selected.get(DocumentType.IDENTITY_CARD);
        var license = selected.get(DocumentType.DRIVER_LICENSE);
        var evidence = new RentalDocuments(card == null ? null : card.expiresOn(),
                license == null ? null : license.expiresOn(), license == null ? null : license.licenseClass(),
                license == null ? null : license.automaticOnly(), license == null ? null : license.issuedOn());
        evidence.requireSelfDriveEligible(tripEndDate, query.seats(), "MANUAL".equals(query.transmission()),
                policies.policyAt(evaluatedAt));
    }
}
