package com.edison.service.impl;

import com.edison.dto.response.UploadImageResponse;
import com.edison.entity.ImageFile;
import com.edison.mapper.ImageFileMapper;
import com.edison.service.FileService;
import com.edison.util.JwtUtil;
import com.edison.util.OssService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.UUID;

// 文件上传实现类
@Slf4j
@Service
public class FileServiceImpl implements FileService {

    @Autowired
    private ImageFileMapper imageFileMapper;

    @Autowired
    private OssService ossService;

    @Value("${file.upload.dir:uploads}")
    private String uploadBaseDir;

    @Autowired
    private JwtUtil jwtUtil;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UploadImageResponse uploadImage(MultipartFile file, String authorization) {
        if (file == null) {
            throw new RuntimeException("缺少上传文件");
        }
        if (file.isEmpty()) {
            throw new RuntimeException("上传文件为空");
        }
        if (file.getContentType() == null || !file.getContentType().startsWith("image/")) {
            throw new RuntimeException("仅支持图片上传");
        }
        long fileSize = file.getSize();
        if (fileSize > ossService.getMaxSize()) {
            throw new RuntimeException("图片大小超过限制");
        }

        String token = jwtUtil.parseToken(authorization);
        Long userId = jwtUtil.getUserId(token);

        String originalFileName = file.getOriginalFilename();
        if (originalFileName == null || originalFileName.isBlank()) {
            originalFileName = "image";
        }

        String extension = getFileExtension(originalFileName, file.getContentType());
        String uuid = UUID.randomUUID().toString().replace("-", "");

        // 本地“系统”存储路径：uploads/images/{uuid}.ext
        Path localDir = Paths.get(uploadBaseDir, "images");
        try {
            Files.createDirectories(localDir);
        } catch (Exception e) {
            throw new RuntimeException("创建本地存储目录失败");
        }

        String finalLocalFileName = uuid + extension;
        Path localFilePath = localDir.resolve(finalLocalFileName);

        boolean uploaded = false;
        try {
            // 1) 上传到系统（本地存储）
            file.transferTo(localFilePath);
//            if (!pythonImageService.validateImage(localFilePath)) {
//                throw new RuntimeException("图片审核不通过！");
//            }
            // 2) 同步到OSS
            String objectKey = "images/" + finalLocalFileName;
            File localFile = localFilePath.toFile();
            String url = ossService.upload(objectKey, localFile, file.getContentType());

            // 2.5) 上传到 Python（用于后续搜索向量化）
            //pythonImageService.uploadImage(userId, url);

            // 3) 落库
            ImageFile record = new ImageFile();
            record.setUserId(userId);
            record.setFileName(originalFileName);
            record.setFileSize(fileSize);
            record.setOssUrl(url);
            imageFileMapper.insert(record);

            UploadImageResponse response = new UploadImageResponse();
            response.setUrl(url);
            response.setFileName(originalFileName);
            response.setFileSize(fileSize);
            uploaded = true;
            return response;
        } catch (Exception e) {
            log.error("图片上传失败，原因：{}", e.getMessage());
            throw new RuntimeException(e.getMessage());
        } finally {
            // OSS成功后可删除；失败时尽量保留方便排查
            try {
                if (uploaded) {
                    Files.deleteIfExists(localFilePath);
                }
            } catch (Exception ignore) {
            }
        }
    }

    private String getFileExtension(String originalFileName, String contentType) {
        if (originalFileName != null && originalFileName.contains(".")) {
            String ext = originalFileName.substring(originalFileName.lastIndexOf(".")).toLowerCase(Locale.ROOT);
            if (ext.length() <= 10) {
                return ext;
            }
        }
        if (contentType == null) {
            return ".png";
        }
        if (contentType.equalsIgnoreCase("image/png")) {
            return ".png";
        }
        if (contentType.equalsIgnoreCase("image/jpeg") || contentType.equalsIgnoreCase("image/jpg")) {
            return ".jpg";
        }
        if (contentType.equalsIgnoreCase("image/gif")) {
            return ".gif";
        }
        if (contentType.equalsIgnoreCase("image/webp")) {
            return ".webp";
        }
        return ".png";
    }
}
