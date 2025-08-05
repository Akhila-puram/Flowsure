package com.aaseya.Flowsure.Service;

import org.springframework.stereotype.Service;
import com.aaseya.Flowsure.Model.DiagramValidationResult;
import com.aaseya.Flowsure.Model.ValidationIssue;
import com.aaseya.Flowsure.Model.ValidationIssue.IssueType;

import org.springframework.web.multipart.MultipartFile;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class DiagramValidationService {

    /**
     * Validates a ZIP file containing BPMN or DMN diagram XML files.
     * Extracts files from the ZIP and validates each supported diagram.
     *
     * @param zipFile The MultipartFile containing the ZIP archive.
     * @return A list of DiagramValidationResult, one for each diagram found and validated.
     */
    public List<DiagramValidationResult> validateZip(MultipartFile zipFile) {
        List<DiagramValidationResult> allResults = new ArrayList<>();

        if (zipFile.isEmpty()) {
            DiagramValidationResult emptyZipResult = new DiagramValidationResult();
            emptyZipResult.setDiagramName("ZIP File");
            emptyZipResult.addIssue(new ValidationIssue(IssueType.ERROR, "Uploaded ZIP file is empty."));
            emptyZipResult.setValid(false);
            allResults.add(emptyZipResult);
            return allResults;
        }

        try (ZipInputStream zis = new ZipInputStream(zipFile.getInputStream())) {
            ZipEntry zipEntry;
            while ((zipEntry = zis.getNextEntry()) != null) {
                if (!zipEntry.isDirectory() && (zipEntry.getName().endsWith(".bpmn") || zipEntry.getName().endsWith(".xml") || zipEntry.getName().endsWith(".dmn"))) {
                    // Read the entry content into a byte array
                    byte[] buffer = new byte[1024];
                    int len;
                    try (java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
                        while ((len = zis.read(buffer)) > 0) {
                            bos.write(buffer, 0, len);
                        }
                        try (InputStream diagramInputStream = new java.io.ByteArrayInputStream(bos.toByteArray())) {
                            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
                            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
                            Document doc = dBuilder.parse(diagramInputStream);
                            doc.getDocumentElement().normalize();

                            String diagramName = zipEntry.getName();
                            String diagramType = determineDiagramType(doc, diagramName);

                            allResults.add(validateSingleDiagram(doc, diagramName, diagramType));
                        }
                    }
                }
                zis.closeEntry();
            }
        } catch (IOException | ParserConfigurationException | SAXException e) {
            DiagramValidationResult errorResult = new DiagramValidationResult();
            errorResult.setDiagramName(zipFile.getOriginalFilename());
            errorResult.addIssue(new ValidationIssue(IssueType.ERROR, "Error processing ZIP file or its contents: " + e.getMessage()));
            errorResult.setValid(false);
            allResults.add(errorResult);
        }
        return allResults;
    }

    /**
     * Determines the type of the diagram (BPMN or DMN) based on the root element.
     *
     * @param doc The parsed XML Document.
     * @param fileName The name of the file (for fallback if root element is generic).
     * @return "BPMN", "DMN", or "UNKNOWN".
     */
    private String determineDiagramType(Document doc, String fileName) {
        String rootElementName = doc.getDocumentElement().getTagName();
        if ("bpmn:definitions".equalsIgnoreCase(rootElementName) || fileName.toLowerCase().endsWith(".bpmn")) {
            return "BPMN";
        } else if ("dmn:definitions".equalsIgnoreCase(rootElementName) || fileName.toLowerCase().endsWith(".dmn")) {
            return "DMN";
        }
        return "UNKNOWN";
    }

    /**
     * Validates a single BPMN or DMN diagram XML document.
     *
     * @param doc The parsed XML Document of the diagram.
     * @param diagramName The name of the diagram file.
     * @param diagramType The determined type of the diagram (e.g., "BPMN", "DMN").
     * @return A DiagramValidationResult containing validation status and issues for this single diagram.
     */
    private DiagramValidationResult validateSingleDiagram(Document doc, String diagramName, String diagramType) {
        DiagramValidationResult result = new DiagramValidationResult();
        result.setDiagramName(diagramName);
        boolean overallValid = true;

        // Determine validation logic based on diagram type
        if ("BPMN".equalsIgnoreCase(diagramType)) {
            overallValid &= validateBpmnStructuralIssues(doc, result);
            overallValid &= validateBpmnNamingConventions(doc, result);
            // Removed: overallValid &= validateBpmnDocumentation(doc, result);
            // Add more BPMN specific validations here
        } else if ("DMN".equalsIgnoreCase(diagramType)) {
            overallValid &= validateDmnStructuralIssues(doc, result);
            overallValid &= validateDmnNamingConventions(doc, result);
            overallValid &= validateDmnDocumentation(doc, result);
            // Add more DMN specific validations here
        } else {
            result.addIssue(new ValidationIssue(IssueType.ERROR, "Unsupported or unrecognized diagram type for file: " + diagramName));
            overallValid = false;
        }

        result.setValid(overallValid && result.getIssues().stream().noneMatch(issue -> issue.getType() == IssueType.ERROR));
        return result;
    }

    /**
     * Validates structural issues for BPMN diagrams.
     * Checks for presence of start and end events, and valid sequence flows.
     *
     * @param doc The parsed XML Document.
     * @param result The DiagramValidationResult to add issues to.
     * @return true if no critical structural errors found, false otherwise.
     */
    private boolean validateBpmnStructuralIssues(Document doc, DiagramValidationResult result) {
        boolean valid = true;
        NodeList startEvents = doc.getElementsByTagName("bpmn:startEvent");
        if (startEvents.getLength() == 0) {
            result.addIssue(new ValidationIssue(IssueType.ERROR, "BPMN diagram must have at least one start event."));
            valid = false;
        }

        NodeList endEvents = doc.getElementsByTagName("bpmn:endEvent");
        if (endEvents.getLength() == 0) {
            result.addIssue(new ValidationIssue(IssueType.ERROR, "BPMN diagram must have at least one end event."));
            valid = false;
        }

        // Example: Check for sequence flows without source/target
        NodeList sequenceFlows = doc.getElementsByTagName("bpmn:sequenceFlow");
        for (int i = 0; i < sequenceFlows.getLength(); i++) {
            Element flow = (Element) sequenceFlows.item(i);
            if (!flow.hasAttribute("sourceRef") || !flow.hasAttribute("targetRef")) {
                result.addIssue(new ValidationIssue(IssueType.ERROR, "Sequence flow '" + flow.getAttribute("id") + "' is missing sourceRef or targetRef.", flow.getAttribute("id"), flow.getAttribute("name")));
                valid = false;
            }
        }
        return valid;
    }

    /**
     * Validates naming conventions for BPMN elements.
     * Example: Checks if task names are capitalized.
     *
     * @param doc The parsed XML Document.
     * @param result The DiagramValidationResult to add issues to.
     * @return true if no critical naming convention errors found.
     */
    private boolean validateBpmnNamingConventions(Document doc, DiagramValidationResult result) {
        boolean valid = true;
        // Example: All tasks should start with an uppercase letter
        NodeList tasks = doc.getElementsByTagName("bpmn:task");
        for (int i = 0; i < tasks.getLength(); i++) {
            Element task = (Element) tasks.item(i);
            String taskName = task.getAttribute("name");
            if (taskName != null && !taskName.isEmpty() && !Character.isUpperCase(taskName.charAt(0))) {
                result.addIssue(new ValidationIssue(IssueType.WARNING, "BPMN Task name '" + taskName + "' should start with an uppercase letter.", task.getAttribute("id"), taskName));
            }
        }

        // Example: Event names should follow a specific pattern (e.g., "Start <Action>")
        NodeList events = doc.getElementsByTagName("bpmn:event"); // General event tag
        for (int i = 0; i < events.getLength(); i++) {
            Element event = (Element) events.item(i);
            String eventName = event.getAttribute("name");
            if (eventName != null && !eventName.isEmpty()) {
                // Regex: Starts with "Start ", "End ", or "Intermediate " followed by any characters
                Pattern pattern = Pattern.compile("^(Start|End|Intermediate) .*");
                Matcher matcher = pattern.matcher(eventName);
                if (!matcher.matches()) {
                    result.addIssue(new ValidationIssue(IssueType.WARNING, "BPMN Event name '" + eventName + "' does not follow recommended pattern (e.g., 'Start <Action>').", event.getAttribute("id"), eventName));
                }
            }
        }
        return valid;
    }

    /**
     * This method is no longer called as per the user's request to remove documentation name checks for BPMN.
     * Validates required documentation/descriptions for BPMN elements.
     * Example: Checks if process or task elements have documentation.
     *
     * @param doc The parsed XML Document.
     * @param result The DiagramValidationResult to add issues to.
     * @return true if no critical documentation errors found.
     */
    // private boolean validateBpmnDocumentation(Document doc, DiagramValidationResult result) {
    //     boolean valid = true;
    //     // Check for documentation on the main process
    //     NodeList processes = doc.getElementsByTagName("bpmn:process");
    //     for (int i = 0; i < processes.getLength(); i++) {
    //         Element process = (Element) processes.item(i);
    //         NodeList documentationNodes = process.getElementsByTagName("bpmn:documentation");
    //         if (documentationNodes.getLength() == 0 || documentationNodes.item(0).getTextContent().trim().isEmpty()) {
    //             result.addIssue(new ValidationIssue(IssueType.WARNING, "BPMN Process '" + process.getAttribute("name") + "' should have documentation.", process.getAttribute("id"), process.getAttribute("name")));
    //         }
    //     }

    //     // Check for documentation on tasks
    //     NodeList tasks = doc.getElementsByTagName("bpmn:task");
    //     for (int i = 0; i < tasks.getLength(); i++) {
    //         Element task = (Element) tasks.item(i);
    //         NodeList documentationNodes = task.getElementsByTagName("bpmn:documentation");
    //         if (documentationNodes.getLength() == 0 || documentationNodes.item(0).getTextContent().trim().isEmpty()) {
    //             result.addIssue(new ValidationIssue(IssueType.WARNING, "BPMN Task '" + task.getAttribute("name") + "' should have documentation.", task.getAttribute("id"), task.getAttribute("name")));
    //         }
    //     }
    //     return valid;
    // }

    /**
     * Placeholder for DMN structural validation.
     *
     * @param doc The parsed XML Document.
     * @param result The DiagramValidationResult to add issues to.
     * @return true if no critical structural errors found.
     */
    private boolean validateDmnStructuralIssues(Document doc, DiagramValidationResult result) {
        boolean valid = true;
        // Example: Check for presence of at least one DMN decision
        NodeList decisions = doc.getElementsByTagName("dmn:decision");
        if (decisions.getLength() == 0) {
            result.addIssue(new ValidationIssue(IssueType.ERROR, "DMN diagram must contain at least one decision."));
            valid = false;
        }

        // Example: Check if decision tables have input and output entries
        NodeList decisionTables = doc.getElementsByTagName("dmn:decisionTable");
        for (int i = 0; i < decisionTables.getLength(); i++) {
            Element table = (Element) decisionTables.item(i);
            NodeList inputs = table.getElementsByTagName("dmn:input");
            NodeList outputs = table.getElementsByTagName("dmn:output");
            if (inputs.getLength() == 0 || outputs.getLength() == 0) {
                Element parentDecision = (Element) table.getParentNode(); // Assuming parent is the decision
                String decisionId = parentDecision != null ? parentDecision.getAttribute("id") : "N/A";
                String decisionName = parentDecision != null ? parentDecision.getAttribute("name") : "N/A";
                result.addIssue(new ValidationIssue(IssueType.ERROR, "DMN Decision Table for decision '" + decisionName + "' must have at least one input and one output.", decisionId, decisionName));
                valid = false;
            }
        }
        return valid;
    }

    /**
     * Placeholder for DMN naming convention validation.
     *
     * @param doc The parsed XML Document.
     * @param result The DiagramValidationResult to add issues to.
     * @return true if no critical naming convention errors found.
     */
    private boolean validateDmnNamingConventions(Document doc, DiagramValidationResult result) {
        boolean valid = true;
        // Example: Decision names should be descriptive
        NodeList decisions = doc.getElementsByTagName("dmn:decision");
        for (int i = 0; i < decisions.getLength(); i++) {
            Element decision = (Element) decisions.item(i);
            String decisionName = decision.getAttribute("name");
            if (decisionName == null || decisionName.trim().isEmpty() || decisionName.length() < 5) {
                result.addIssue(new ValidationIssue(IssueType.WARNING, "DMN Decision name '" + decisionName + "' is too short or empty.", decision.getAttribute("id"), decisionName));
            }
        }
        return valid;
    }

    /**
     * Placeholder for DMN documentation validation.
     *
     * @param doc The parsed XML Document.
     * @param result The DiagramValidationResult to add issues to.
     * @return true if no critical documentation errors found.
     */
    private boolean validateDmnDocumentation(Document doc, DiagramValidationResult result) {
        boolean valid = true;
        // Example: Check if DMN definitions or decisions have documentation
        NodeList definitions = doc.getElementsByTagName("dmn:definitions");
        if (definitions.getLength() > 0) {
            Element definition = (Element) definitions.item(0);
            NodeList documentationNodes = definition.getElementsByTagName("dmn:documentation");
            if (documentationNodes.getLength() == 0 || documentationNodes.item(0).getTextContent().trim().isEmpty()) {
                result.addIssue(new ValidationIssue(IssueType.WARNING, "DMN Definition should have documentation.", definition.getAttribute("id"), definition.getAttribute("name")));
            }
        }

        NodeList decisions = doc.getElementsByTagName("dmn:decision");
        for (int i = 0; i < decisions.getLength(); i++) {
            Element decision = (Element) decisions.item(i);
            NodeList documentationNodes = decision.getElementsByTagName("dmn:documentation");
            if (documentationNodes.getLength() == 0 || documentationNodes.item(0).getTextContent().trim().isEmpty()) {
                result.addIssue(new ValidationIssue(IssueType.WARNING, "DMN Decision '" + decision.getAttribute("name") + "' should have documentation.", decision.getAttribute("id"), decision.getAttribute("name")));
            }
        }
        return valid;
    }
}