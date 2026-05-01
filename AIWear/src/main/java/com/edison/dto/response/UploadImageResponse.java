package com.edison.dto.response;

import lombok.Data;

// 上传图片响应实体类
@Data
public class UploadImageResponse {

    private String url;

    private String fileName;

    private Long fileSize;
}
