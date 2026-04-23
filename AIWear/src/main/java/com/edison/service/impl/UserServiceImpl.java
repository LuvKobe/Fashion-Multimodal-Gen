package com.edison.service.impl;

import com.edison.dto.request.SendVerificationCodeRequest;
import com.edison.service.UserService;
import com.edison.util.EmailService;
import com.edison.util.VerificationCodeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

// 用户模块服务接口的实现类
@Slf4j
@Service
public class UserServiceImpl implements UserService {

    @Autowired
    private EmailService emailService;

    @Autowired
    private VerificationCodeService verificationCodeService;

    // 发送邮箱验证码的方法
    @Override
    public boolean sendVerificationCode(SendVerificationCodeRequest request) {
        String email = request.getEmail();
        if (verificationCodeService.hasCode(email)) {
            throw new RuntimeException("验证码尚未过期，请勿重复发送");
        }
        String code = verificationCodeService.generateCode();
        verificationCodeService.saveCode(email, code);

        return emailService.sendVerificationCode(email, code);
    }
}
