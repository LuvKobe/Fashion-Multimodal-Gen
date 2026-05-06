package com.edison.service;

import java.nio.file.Path;

// Python 图片向量化服务（后续可能会复用在搜索等功能）
public interface PythonImageService {

    // 上传图片后的 OSS URL 与 userId 发给 Python /api/upload-image
    void uploadImage(Long userId, String ossUrl);

    // 审核图片 （上传之前先添加审核）
    boolean validateImage(Path filePath);
}