package com.edison.dto.request;

import lombok.Data;

// 调用 Python /api/upload-image 的请求体
@Data
public class PythonUploadImageRequest {

    private String ossUrl;
    private Long userId;
}