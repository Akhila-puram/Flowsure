package com.aaseya.Flowsure.DTO;

import org.springframework.web.multipart.MultipartFile;

public class DiagramValidationRequest {
    private MultipartFile zipFile;

    // Getters and Setters
    public MultipartFile getZipFile() {
        return zipFile;
    }

    public void setZipFile(MultipartFile zipFile) {
        this.zipFile = zipFile;
    }
}

