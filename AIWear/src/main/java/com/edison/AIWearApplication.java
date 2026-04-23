package com.edison;

// 服务启动类

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@Slf4j
public class AIWearApplication {
    public static void main(String[] args) {
        log.info("Fashion-Multimodal-Gen项目启动成功");
        SpringApplication.run(AIWearApplication.class, args);
    }
}
