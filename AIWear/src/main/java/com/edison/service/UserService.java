package com.edison.service;

import com.edison.dto.request.SendVerificationCodeRequest;

// 用户模块服务接口
public interface UserService {
    // 发送验证码
    boolean sendVerificationCode(SendVerificationCodeRequest request);

}
