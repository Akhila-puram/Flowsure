package com.aaseya.Flowsure.DTO;

import java.util.List;

import com.aaseya.Flowsure.Model.DiagramValidationResult;

public class DiagramValidationResponse {
    private String status; // e.g., "SUCCESS", "FAILURE"
    private String message;
    private List<DiagramValidationResult> results; // Changed to a list for multiple diagrams

    public DiagramValidationResponse() {}

    public DiagramValidationResponse(String status, String message, List<DiagramValidationResult> results) {
        this.status = status;
        this.message = message;
        this.results = results;
    }

    // Getters and Setters
    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public List<DiagramValidationResult> getResults() {
        return results;
    }

    public void setResults(List<DiagramValidationResult> results) {
        this.results = results;
    }
}
