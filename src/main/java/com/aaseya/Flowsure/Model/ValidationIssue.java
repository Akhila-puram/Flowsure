package com.aaseya.Flowsure.Model;

public class ValidationIssue {
    public enum IssueType {
        ERROR, WARNING, INFO
    }

    private IssueType type;
    private String message;
    private String elementId; 
    private String elementName; 
    public ValidationIssue() {}

    public ValidationIssue(IssueType type, String message) {
        this.type = type;
        this.message = message;
    }

    public ValidationIssue(IssueType type, String message, String elementId, String elementName) {
        this.type = type;
        this.message = message;
        this.elementId = elementId;
        this.elementName = elementName;
    }

    // Getters and Setters
    public IssueType getType() {
        return type;
    }

    public void setType(IssueType type) {
        this.type = type;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getElementId() {
        return elementId;
    }

    public void setElementId(String elementId) {
        this.elementId = elementId;
    }

    public String getElementName() {
        return elementName;
    }

    public void setElementName(String elementName) {
        this.elementName = elementName;
    }
}

