package com.carrental.branch.adapter.in.internal;

import com.carrental.PostgresTestConfiguration;
import com.carrental.branch.api.BranchDirectory;
import com.carrental.branch.api.BranchRef;
import com.carrental.branch.application.command.CreateBranchCommand;
import com.carrental.branch.application.port.in.CreateBranchUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

/** Trace branch.api → use case → port → SQL/PostgreSQL thật; fixture rollback sau test. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
@Transactional
class BranchIdLookupIntegrationTest {
    @Autowired private BranchDirectory directory;
    @Autowired private CreateBranchUseCase create;

    /** Hai chi nhánh khác nhau trả đúng mã riêng theo ID và khớp đường tra theo mã. */
    @Test
    void resolvesStoredIdsWithoutMixingBranches() {
        var first = create.create(CreateBranchCommand.from(10.0, 106.0, "First", "First address"));
        var second = create.create(CreateBranchCommand.from(11.0, 107.0, "Second", "Second address"));
        assertNotEquals(first.id(), second.id());
        assertEquals(Optional.of(new BranchRef(first.id(), first.code())), directory.findById(first.id()));
        assertEquals(Optional.of(new BranchRef(second.id(), second.code())), directory.findById(second.id()));
        assertEquals(directory.findByCode(first.code()), directory.findById(first.id()));
    }

    /** ID dương không tồn tại không bị ghép với chi nhánh bất kỳ. */
    @Test
    void missingIdReturnsEmpty() {
        assertTrue(directory.findById(Long.MAX_VALUE).isEmpty());
    }
}
