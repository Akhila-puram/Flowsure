package com.aaseya.Flowsure.Model;

import java.util.List;
import java.util.ArrayList;

public class DiagramValidationResult {
    private String diagramName;
    private boolean isValid;
    private List<ValidationIssue> issues;

    public DiagramValidationResult() {
        this.issues = new ArrayList<>();
    }

    public DiagramValidationResult(String diagramName, boolean isValid, List<ValidationIssue> issues) {
        this.diagramName = diagramName;
        this.isValid = isValid;
        this.issues = issues;
    }

    // Getters and Setters
    public String getDiagramName() {
        return diagramName;
    }

    public void setDiagramName(String diagramName) {
        this.diagramName = diagramName;
    }

    public boolean isValid() {
        return isValid;
    }

    public void setValid(boolean valid) {
        isValid = valid;
    }

    public List<ValidationIssue> getIssues() {
        return issues;
    }

    public void setIssues(List<ValidationIssue> issues) {
        this.issues = issues;
    }

    public void addIssue(ValidationIssue issue) {
        this.issues.add(issue);
    }
}
