package com.edison.service.impl;

import com.edison.dto.request.PythonUploadImageRequest;
import com.edison.dto.response.PythonUploadImageResponse;
import com.edison.service.PythonImageService;
import com.edison.util.OssService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Slf4j
@Service
public class PythonImageServiceImpl implements PythonImageService {

    @Value("${python.service.base-url:http://8.156.77.78:5000}")
    private String pythonBaseUrl;

    // 这里使用 Java 内置 HttpClient，避免额外依赖
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Autowired
    private OssService ossService;

    @Autowired
    public PythonImageServiceImpl(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public void uploadImage(Long userId, String ossUrl) {
        if (userId == null) {
            throw new RuntimeException("缺少 userId");
        }
        if (ossUrl == null || ossUrl.isBlank()) {
            throw new RuntimeException("缺少 ossUrl");
        }

        PythonUploadImageRequest requestBody = new PythonUploadImageRequest();
        requestBody.setUserId(userId);
        requestBody.setOssUrl(ossUrl);

        String requestJson;
        try {
            requestJson = objectMapper.writeValueAsString(requestBody);
        } catch (Exception e) {
            throw new RuntimeException("构造 Python 请求体失败");
        }

        String url = pythonBaseUrl + "/api/upload-image";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("调用 Python /api/upload-image 失败：{}", e.getMessage());
            throw new RuntimeException("调用 Python 服务失败");
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.error("Python 服务返回非 2xx：status={}, body={}", response.statusCode(), response.body());
            throw new RuntimeException("Python 服务返回错误");
        }

        try {
            PythonUploadImageResponse pythonResponse =
                    objectMapper.readValue(response.body(), PythonUploadImageResponse.class);
            if (pythonResponse.getSuccess() == null || !pythonResponse.getSuccess()) {
                throw new RuntimeException("Python 上传失败");
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("解析 Python 响应失败：{}", e.getMessage());
            throw new RuntimeException("解析 Python 响应失败");
        }
    }
}
