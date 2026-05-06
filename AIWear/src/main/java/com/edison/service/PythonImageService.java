package com.edison.service;

// Python 图片向量化服务（后续可能会复用在搜索等功能）
public interface PythonImageService {

    // 上传图片后的 OSS URL 与 userId 发给 Python /api/upload-image
    void uploadImage(Long userId, String ossUrl);
}