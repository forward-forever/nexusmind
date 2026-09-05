package com.wude.nexusmind.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.util.Objects;

@ConfigurationProperties("nexusmind.storage")
public record StorageProperties(Path root, DataSize maxFileSize) {

    public StorageProperties {
        Objects.requireNonNull(root, "Storage root is required");
        Objects.requireNonNull(maxFileSize, "Maximum file size is required");
        if (maxFileSize.toBytes() <= 0) {
            throw new IllegalArgumentException("Maximum file size must be positive");
        }
    }
}
