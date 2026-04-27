package com.edison.service;

import com.edison.dto.request.AuthRequest;
import com.edison.dto.request.SendVerificationCodeRequest;
import com.edison.dto.response.AuthResponse;
import jakarta.validation.Valid;

// 用户模块服务接口
public interface UserService {

    // 发送验证码
    boolean sendVerificationCode(SendVerificationCodeRequest request);

    // 认证注册/登录
    AuthResponse auth(@Valid AuthRequest request);
}
