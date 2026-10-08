package com.carrental.architecture.fixtures.r10contracts.vehicle.api;

import com.carrental.architecture.fixtures.r10contracts.vehicle.domain.OwnershipType;
import java.util.List;
import java.util.Optional;

/** Các dạng hợp đồng thử R10; không được nhập vào phạm vi quét production. */
public final class Contracts {
    /** Không cần tạo đối tượng chứa các kiểu fixture. */
    private Contracts() {
    }

    /** Field có tên trung tính vẫn lộ loại sở hữu. */
    public static final class FieldView {
        private OwnershipType classification;
    }

    /** Thành phần record sinh cả field lẫn accessor nhưng chỉ báo một vi phạm cho hợp đồng. */
    public record RecordView(OwnershipType classification) {
    }

    /** Getter/interface không có field vẫn lộ sở hữu qua kiểu trả về. */
    public interface MethodView {
        /** Chữ ký cố ý vi phạm mà không cần thân method. */
        OwnershipType classification();
    }

    /** Generic không được che OwnershipType bên trong List/Optional. */
    public interface GenericView {
        /** Trả nhiều lớp generic để chứng minh rule không chỉ nhìn raw type. */
        List<Optional<OwnershipType>> classifications();
    }

    /** Mảng không được che kiểu phần tử sở hữu. */
    public interface ArrayView {
        /** Trả mảng vi phạm R10. */
        OwnershipType[] classifications();
    }

    /** Directory trông sạch ở lớp ngoài nhưng trả view chứa sở hữu. */
    public interface NestedDirectory {
        /** Rule phải đi từ List tới RecordView rồi tới OwnershipType. */
        List<RecordView> list();
    }

    /** Kiểu con kế thừa method vi phạm, không tự khai báo method nào. */
    public interface InheritedView extends MethodView {
    }

    /** Directory không được mở đường nhận VehicleRef dù search chưa gọi phương thức đó. */
    public interface RefDirectory {
        /** Trả kiểu cấm qua generic. */
        Optional<VehicleRef> find();
    }

    /** View an toàn: chỉ mã xe và tính chất khách quan tâm. */
    public record SafeView(String code, boolean collateralFree) {
    }

    /** Hợp đồng hợp lệ có generic và thông tin thế chấp, không có loại sở hữu. */
    public interface SafeDirectory {
        /** Trả view sạch; generic không phải lý do tự động đánh lỗi. */
        List<SafeView> list();
    }

    /** Vòng tham chiếu hợp lệ phải kết thúc duyệt, không tràn stack. */
    public interface CycleOne {
        /** Đi tới phía còn lại của vòng. */
        CycleTwo next();
    }

    /** Nửa còn lại không mang loại sở hữu. */
    public interface CycleTwo {
        /** Quay về kiểu đã duyệt. */
        CycleOne previous();
    }
}
