package com.edison.util;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;

// 阿里云OSS服务类
@Service
@Slf4j
public class OssService {

    @Value("${aliyun.oss.endpoint}")
    private String endpoint;

    @Value("${aliyun.oss.access-key-id}")
    private String accessKeyId;

    @Value("${aliyun.oss.access-key-secret}")
    private String accessKeySecret;

    @Value("${aliyun.oss.bucket-name}")
    private String bucketName;

    @Value("${aliyun.oss.max-size}")
    private Long maxSize;

    public Long getMaxSize() {
        return maxSize;
    }

    // 上传文件到OSS，并返回访问URL
    public String upload(String objectKey, File localFile, String contentType) {
        OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
        try {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(localFile.length());
            if (contentType != null && contentType.startsWith("image/")) {
                metadata.setContentType(contentType);
            }

            ossClient.putObject(bucketName, objectKey, localFile, metadata);
            return buildUrl(objectKey);
        } finally {
            ossClient.shutdown();
        }
    }

    private String buildUrl(String objectKey) {
        // OSS访问地址： https://{bucket}.{endpoint}/{objectKey}
        return "https://" + bucketName + "." + endpoint + "/" + objectKey;
    }
}
