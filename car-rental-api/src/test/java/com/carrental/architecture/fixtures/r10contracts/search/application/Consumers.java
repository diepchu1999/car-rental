package com.carrental.architecture.fixtures.r10contracts.search.application;

import com.carrental.architecture.fixtures.r10contracts.vehicle.api.Contracts;

/** Các lớp search chỉ giữ hợp đồng, không gọi getter: biết được kiểu đã đủ vi phạm BR-112. */
public final class Consumers {
    /** Không cần tạo lớp bao fixture. */
    private Consumers() {
    }

    /** Nhận field chứa sở hữu. */
    public static final class FieldLeak {
        private Contracts.FieldView view;
    }

    /** Nhận record chứa sở hữu. */
    public static final class RecordLeak {
        private Contracts.RecordView view;
    }

    /** Nhận interface trả sở hữu. */
    public static final class MethodLeak {
        private Contracts.MethodView view;
    }

    /** Nhận chữ ký generic chứa sở hữu. */
    public static final class GenericLeak {
        private Contracts.GenericView view;
    }

    /** Nhận chữ ký mảng chứa sở hữu. */
    public static final class ArrayLeak {
        private Contracts.ArrayView view;
    }

    /** Chỉ nhận directory nhưng đường trả về vẫn lộ sở hữu. */
    public static final class NestedLeak {
        private Contracts.NestedDirectory directory;
    }

    /** Nhận kiểu kế thừa chữ ký vi phạm. */
    public static final class InheritedLeak {
        private Contracts.InheritedView view;
    }

    /** Nhận directory trả VehicleRef, không trực tiếp import VehicleRef. */
    public static final class RefLeak {
        private Contracts.RefDirectory directory;
    }

    /** Trường hợp được phép, cũng dùng làm phép thử đảo bằng cách thêm field vi phạm vào lớp này. */
    public static final class Allowed {
        private Contracts.SafeDirectory directory;
    }

    /** Trường hợp được phép với đồ thị kiểu có chu trình. */
    public static final class CycleAllowed {
        private Contracts.CycleOne cycle;
    }
}
