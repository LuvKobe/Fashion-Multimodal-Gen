package com.edison.dto.response;

import lombok.Data;

// Python /api/upload-image 的返回体（只用到 success，但保留其他字段便于后续扩展）
@Data
public class PythonUploadImageResponse {

    private Boolean success;
    private String description;
    private Integer embeddingDim;
    private String imageId;
}