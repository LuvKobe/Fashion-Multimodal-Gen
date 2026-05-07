package com.edison.service.impl;

import com.edison.dto.request.EditImageRequest;
import com.edison.dto.request.MergeImageRequest;
import com.edison.dto.request.PythonUploadImageRequest;
import com.edison.dto.request.SearchImageRequest;
import com.edison.dto.response.EditImageResponse;
import com.edison.dto.response.MergeImageResponse;
import com.edison.dto.response.PythonUploadImageResponse;
import com.edison.dto.response.SearchImageResponse;
import com.edison.service.PythonImageService;
import com.edison.util.OssService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpHeaders;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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

    @Override
    public boolean validateImage(Path filePath) {
        try {
            String url = pythonBaseUrl + "/api/validate-image";
            HttpHeaders httpHeaders = new HttpHeaders();
            httpHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new FileSystemResource(filePath));
            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, httpHeaders);
            RestTemplate restTemplate = new RestTemplate();
            ResponseEntity<String> response = restTemplate.postForEntity(url, requestEntity, String.class);
            return objectMapper.readTree(response.getBody()).path("allow").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public List<SearchImageResponse> search(Long userId, SearchImageRequest searchImageRequest) {
        String url = pythonBaseUrl + "/api/search-image";
        String query = searchImageRequest.getQuery();
        MultipartFile file = searchImageRequest.getFile();

        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("userId", userId);
            if (query != null && !query.isBlank()) {
                body.add("query", query);
            }

            if (file != null) {
                byte[] fileBytes = file.getBytes();
                String filename = file.getOriginalFilename();
                ByteArrayResource fileResource = new ByteArrayResource(fileBytes) {
                    @Override
                    public String getFilename() {
                        return filename;
                    }
                };
                body.add("file", fileResource);
            }

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, httpHeaders);
            RestTemplate restTemplate = new RestTemplate();
            ResponseEntity<String> response = restTemplate.postForEntity(url, requestEntity, String.class);
            JsonNode result = objectMapper.readTree(response.getBody())
                    .path("data");
            List<SearchImageResponse> searchImageResponseList = new ArrayList<>();
            for (JsonNode node : result) {
                SearchImageResponse searchImageResponse = new SearchImageResponse();
                searchImageResponse.setFilePath(node.path("filePath").asText(""));
                searchImageResponseList.add(searchImageResponse);
            }
            return searchImageResponseList;

        } catch (Exception e) {
            log.error("调用python服务的搜索图片接口失败{}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public EditImageResponse edit(EditImageRequest editImageRequest) {
        // 1. 考虑转换  oss url -> file资源
        String sourceImageUrl = editImageRequest.getImage();
        String instruction = editImageRequest.getInstruction();
        Path sourceTemp = null;
        Path editTemp = null;

        sourceTemp = downloadToTempFile(sourceImageUrl);
        // 2. 构造请求参数去访问python服务的编辑图片接口
        String url = pythonBaseUrl + "/api/skill/image";
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new FileSystemResource(sourceTemp.toFile()));
        body.add("instruction", instruction);

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, httpHeaders);
        RestTemplate restTemplate = new RestTemplate();

        // 3. 拿到python服务返回的响应之后需要处理数据
        ResponseEntity<String> response = restTemplate.postForEntity(url, requestEntity, String.class);
        try {
            JsonNode result = objectMapper.readTree(response.getBody());
            String pythonUrl = result.path("url").asText("");
            // 4. 图片地址需要二次保存
            editTemp = downloadToTempFile(pythonUrl);
            String contentType = Files.probeContentType(editTemp);
            String extension = ".png";
            String objectKey = "image/edited/" + UUID.randomUUID().toString().replace("-", "") + extension;
            String saveUrl = ossService.upload(objectKey, editTemp.toFile(), contentType);
            // 5. 封装一个返回对象
            EditImageResponse editImageResponse = new EditImageResponse();
            editImageResponse.setUrl(pythonUrl);
            editImageResponse.setSaveUrl(saveUrl);
            return editImageResponse;
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            // 6. 处理无论成功与否，都应该删掉临时
            try {
                Files.deleteIfExists(sourceTemp);
                Files.deleteIfExists(editTemp);
            } catch (IOException e) {
                log.error("删除临时文件失败");
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public MergeImageResponse merge(MergeImageRequest mergeImageRequest) {
        // 1. 文件转换   oss url   -> file 资源
        String source1ImageUrl = mergeImageRequest.getImage1();
        String source2ImageUrl = mergeImageRequest.getImage2();
        String instruction = mergeImageRequest.getInstruction();
        Path source1Temp = downloadToTempFile(source1ImageUrl);
        Path source2Temp = downloadToTempFile(source2ImageUrl);
        Path mergeTemp = null;
        // 2. 构造请求参数去访问python服务的合并图片接口
        String url = pythonBaseUrl + "/api/skill/image";
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file1", new FileSystemResource(source1Temp.toFile()));
        body.add("file2", new FileSystemResource(source2Temp.toFile()));
        body.add("instruction", instruction);

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, httpHeaders);
        RestTemplate restTemplate = new RestTemplate();

        // 3. 拿到python服务返回的响应
        ResponseEntity<String> response = restTemplate.postForEntity(url, requestEntity, String.class);
        try {
            JsonNode result = objectMapper.readTree(response.getBody());
            String pythonUrl = result.path("url").asText("");
            // 4. 拿到的图片进行二次保存到oss
            mergeTemp = downloadToTempFile(pythonUrl);
            String contentType = Files.probeContentType(mergeTemp);
            String extension = ".png";
            String objectKey = "image/merged/" + UUID.randomUUID().toString().replace("-", "") + extension;
            String saveUrl = ossService.upload(objectKey, mergeTemp.toFile(), contentType);
            // 5. 封装一个返回的对象
            MergeImageResponse mergeImageResponse = new MergeImageResponse();
            mergeImageResponse.setUrl(pythonUrl);
            mergeImageResponse.setSaveUrl(saveUrl);
            return mergeImageResponse;

        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            // 6. 删掉临时文件
            try {
                Files.deleteIfExists(source1Temp);
                Files.deleteIfExists(source2Temp);
                Files.deleteIfExists(mergeTemp);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    // 下载远程地址的url文件
    private Path downloadToTempFile(String fileUrl) {
        try {
            URL url = URI.create(fileUrl).toURL();
            Path tempFile = Files.createTempFile("image-edit-", ".tmp");
            InputStream inputStream = url.openStream();
            Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
            return tempFile;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
