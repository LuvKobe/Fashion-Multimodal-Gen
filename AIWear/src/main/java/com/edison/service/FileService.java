package com.edison.service;

import com.edison.dto.response.UploadImageResponse;
import com.edison.entity.ImageFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

// 文件服务接口
public interface FileService {

    // 上传图片：先保存到系统，再同步到OSS，并落库files表
    UploadImageResponse uploadImage(MultipartFile file, String authorization);

    // 查询当前用户上传的图片列表
    List<ImageFile> myImages(String authorization);
}
