package com.carrental.identity.application.port.in;

import com.carrental.identity.application.command.ResolveAccountCommand;
import com.carrental.identity.application.view.AccountDetail;

/** Cổng đồng bộ tối thiểu danh tính đã xác thực vào tài khoản nghiệp vụ theo BR-120/809. */
public interface ResolveAccountUseCase {
    /** Tạo hoặc lấy cùng tài khoản; đối chiếu danh tính và chỉ bổ sung consent còn thiếu. */
    AccountDetail resolve(ResolveAccountCommand command);
}
