package com.edison.controller;

import com.edison.common.Result;
import com.edison.dto.response.UploadImageResponse;
import com.edison.entity.ImageFile;
import com.edison.log.ApiLog;
import com.edison.service.FileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

// 文件模块控制器
@RestController
@Slf4j
@RequestMapping("/api/file")
public class FileController {

    @Autowired
    private FileService fileService;

    // 上传图片（系统本地存储 -> OSS同步 -> files表落库）
    @ApiLog
    @PostMapping("/upload/image")
    public Result<UploadImageResponse> uploadImage(
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "Authorization") String authorization
    ) {
        UploadImageResponse response = fileService.uploadImage(file, authorization);
        return Result.success("图片上传成功", response);
    }

    // 我的图片列表（用来展示当前用户上传的图片）
    @ApiLog
    @GetMapping("/my-images")
    public Result<List<ImageFile>> myImages(
            @RequestHeader(value = "Authorization") String authorization
    ) {
        List<ImageFile> imageFiles = fileService.myImages(authorization);
        return Result.success("查询成功", imageFiles);
    }
}
