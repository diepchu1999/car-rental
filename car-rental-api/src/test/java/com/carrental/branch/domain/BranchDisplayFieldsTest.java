package com.carrental.branch.domain;

import com.carrental.branch.application.command.CreateBranchCommand;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Kiểm BR-808 ở aggregate, constructor command và factory, không cần Spring. */
class BranchDisplayFieldsTest {
    private static final BranchLocation LOCATION = new BranchLocation(10.762622, 106.660172);

    /** Tên và địa chỉ hợp lệ giữ nguyên cách viết, không bị cắt khoảng trắng hoặc đổi chỗ. */
    @Test
    void preservesNameAndAddressAcrossConstructionPaths() {
        String name = " Central Branch ";
        String address = " 123 Main Street ";
        Branch branch = new Branch("CN-NAME01", LOCATION, name, address);
        CreateBranchCommand direct = new CreateBranchCommand(LOCATION, name, address);
        CreateBranchCommand factory = CreateBranchCommand.from(LOCATION.latitude(), LOCATION.longitude(), name, address);
        assertEquals(name, branch.name());
        assertEquals(address, branch.address());
        assertEquals(name, direct.name());
        assertEquals(address, direct.address());
        assertEquals(direct, factory);
    }

    /** Từng đường tạo đều từ chối tên thiếu, rỗng hoặc trắng với đúng lỗi đầu vào. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsMissingOrBlankName(String name) {
        String message = name == null ? "name is required." : "name must not be blank.";
        assertInvalid(() -> new Branch("CN-NAME01", LOCATION, name, "123 Main Street"), message);
        assertInvalid(() -> new CreateBranchCommand(LOCATION, name, "123 Main Street"), message);
        assertInvalid(() -> CreateBranchCommand.from(10.762622, 106.660172, name, "123 Main Street"), message);
    }

    /** Từng đường tạo đều từ chối địa chỉ thiếu, rỗng hoặc trắng với đúng lỗi đầu vào. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsMissingOrBlankAddress(String address) {
        String message = address == null ? "address is required." : "address must not be blank.";
        assertInvalid(() -> new Branch("CN-NAME01", LOCATION, "Central Branch", address), message);
        assertInvalid(() -> new CreateBranchCommand(LOCATION, "Central Branch", address), message);
        assertInvalid(() -> CreateBranchCommand.from(10.762622, 106.660172, "Central Branch", address), message);
    }

    /** Không tự thêm độ dài tối đa mà BR-808 chưa quy định. */
    @Test
    void acceptsLongDisplayFields() {
        String name = "N".repeat(1000);
        String address = "A".repeat(1000);
        Branch branch = new Branch("CN-NAME01", LOCATION, name, address);
        CreateBranchCommand command = CreateBranchCommand.from(10.762622, 106.660172, name, address);
        assertEquals(name, branch.name());
        assertEquals(address, branch.address());
        assertEquals(name, command.name());
        assertEquals(address, command.address());
    }

    /** Kiểm exception, nhóm lỗi, mã và thông báo để tránh test xanh do sai nguyên nhân. */
    private static void assertInvalid(Executable action, String message) {
        DomainException failure = assertThrowsExactly(DomainException.class, action);
        assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode());
        assertEquals(DomainException.Category.INVALID_INPUT, failure.category());
        assertEquals(message, failure.getMessage());
    }
}
