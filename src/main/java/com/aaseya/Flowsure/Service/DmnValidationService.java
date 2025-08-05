package com.aaseya.Flowsure.Service;

import com.aaseya.Flowsure.Model.DiagramValidationResult;
import com.aaseya.Flowsure.Model.ValidationIssue;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.w3c.dom.Node;

import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;
import org.xml.sax.SAXException; // Import the SAXException

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class DmnValidationService {

	public List<DiagramValidationResult> validateDmnZip(MultipartFile zipFile) throws IOException {
		List<DiagramValidationResult> allResults = new ArrayList<>();
		try (ZipInputStream zis = new ZipInputStream(zipFile.getInputStream())) {
			ZipEntry zipEntry = zis.getNextEntry();
			while (zipEntry != null) {
				if (!zipEntry.isDirectory() && zipEntry.getName().toLowerCase().endsWith(".dmn")) {
					String fileName = zipEntry.getName();
					byte[] dmnBytes = zis.readAllBytes();

					DiagramValidationResult result = new DiagramValidationResult(fileName, true, new ArrayList<>());
					List<ValidationIssue> issues = new ArrayList<>();

					// 1. Validate XML Syntax/Structure
					validateXmlSyntax(new ByteArrayInputStream(dmnBytes), issues, result, fileName);

					// 2. Check for Rule Overlaps if XML is valid so far
					if (result.isValid()) {
						try (InputStream dmnInputStreamForOverlap = new ByteArrayInputStream(dmnBytes)) {
							checkForRuleOverlapsInDmnFile(dmnInputStreamForOverlap, issues, fileName, result);
						} catch (Exception e) {
							result.setValid(false);
							issues.add(new ValidationIssue(ValidationIssue.IssueType.ERROR,
									"Error during rule overlap analysis for " + fileName + ": " + e.getMessage()));
							System.err.println(
									"Error during rule overlap analysis for " + fileName + ": " + e.getMessage());
							e.printStackTrace();
						}
					}

					// 3. Check for Missing Descriptions
					if (result.isValid()) {
						try (InputStream dmnInputStreamForDescription = new ByteArrayInputStream(dmnBytes)) {
							checkForMissingDescriptions(dmnInputStreamForDescription, issues, fileName, result);
						} catch (Exception e) {
							result.setValid(false);
							issues.add(new ValidationIssue(ValidationIssue.IssueType.ERROR,
									"Error during missing description analysis for " + fileName + ": "
											+ e.getMessage()));
							System.err.println("Error during missing description analysis for " + fileName + ": "
									+ e.getMessage());
							e.printStackTrace();
						}
					}

					// 4. Check for Type Consistency
					if (result.isValid()) { // Or based on your logic for when to run this check
						try (InputStream dmnInputStreamForTypeConsistency = new ByteArrayInputStream(dmnBytes)) {
							checkForTypeConsistency(dmnInputStreamForTypeConsistency, issues, fileName, result);
						} catch (Exception e) {
							// Potentially mark result as invalid or add a general error for this check
							issues.add(new ValidationIssue(ValidationIssue.IssueType.ERROR,
									"Error during type consistency analysis for " + fileName + ": " + e.getMessage()));
							System.err.println(
									"Error during type consistency analysis for " + fileName + ": " + e.getMessage());
							e.printStackTrace();
						}
					}

					// 5. Check Hit Policy Compatibility
					if (result.isValid()) {
						try (InputStream dmnInputStreamForHitPolicy = new ByteArrayInputStream(dmnBytes)) {
							checkHitPolicyCompatibility(dmnInputStreamForHitPolicy, issues, fileName, result);
						} catch (Exception e) {
							// Potentially mark result as invalid
							issues.add(new ValidationIssue(ValidationIssue.IssueType.ERROR,
									"Error during hit policy compatibility analysis: " + e.getMessage()));
							System.err.println("Error during hit policy compatibility analysis: " + e.getMessage());
							e.printStackTrace();
						}
					}

					// 6.RuleGap

					if (result.isValid()) {
						try (InputStream dmnInputStreamForRuleGap = new ByteArrayInputStream(dmnBytes)) {
							checkRuleGaps(dmnInputStreamForRuleGap, issues, fileName, result);
						} catch (Exception e) {
							issues.add(new ValidationIssue(ValidationIssue.IssueType.ERROR,
									"Error during rule gap analysis for " + fileName + ": " + e.getMessage()));
							System.err
									.println("Error during rule gap analysis for " + fileName + ": " + e.getMessage());
							e.printStackTrace();
						}
					}

					////// add from here/////

					result.setIssues(issues);
					allResults.add(result);
				}
				zis.closeEntry();
				zipEntry = zis.getNextEntry();
			}
		}
		return allResults;
	}

	private void checkRuleGaps(InputStream dmnInputStream, List<ValidationIssue> issues, String fileName,
			DiagramValidationResult result) throws Exception {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		DocumentBuilder builder = factory.newDocumentBuilder();
		Document document = builder.parse(dmnInputStream);
		NodeList ruleList = document.getElementsByTagName("rule");

		List<Double[]> ranges = new ArrayList<>();

		for (int i = 0; i < ruleList.getLength(); i++) {
			Element rule = (Element) ruleList.item(i);
			NodeList inputEntries = rule.getElementsByTagName("inputEntry");
			if (inputEntries.getLength() == 0)
				continue;

			// Only checking the first input column for numeric ranges
			String inputText = inputEntries.item(0).getTextContent().replaceAll("[\\[\\]]", "").trim();
			String[] bounds = inputText.split("\\.\\.");
			if (bounds.length == 2) {
				try {
					double lower = Double.parseDouble(bounds[0].trim());
					double upper = Double.parseDouble(bounds[1].trim());
					ranges.add(new Double[] { lower, upper });
				} catch (NumberFormatException e) {
					// skip non-numeric ranges
				}
			}
		}

		// Sort ranges by lower bound
		ranges.sort((a, b) -> Double.compare(a[0], b[0]));

		for (int i = 1; i < ranges.size(); i++) {
			Double[] prev = ranges.get(i - 1);
			Double[] curr = ranges.get(i);
			if (curr[0] > prev[1]) {
				issues.add(new ValidationIssue(ValidationIssue.IssueType.WARNING,
						"Potential rule gap detected between " + prev[1] + " and " + curr[0] + " in " + fileName));
			}
		}
	}

	private void validateXmlSyntax(InputStream dmnInputStream, List<ValidationIssue> issues,
			DiagramValidationResult result, String fileName) {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(true);
			DocumentBuilder builder = factory.newDocumentBuilder();

			builder.setErrorHandler(new DefaultHandler() {
				private void addIssue(SAXParseException e, ValidationIssue.IssueType type, String severity) {
					String message = String.format("XML %s in %s: %s (Line: %d, Column: %d)", severity, fileName,
							e.getMessage(), e.getLineNumber(), e.getColumnNumber());
					issues.add(new ValidationIssue(type, message, e.getSystemId(), null));
					if (type == ValidationIssue.IssueType.ERROR) {
						result.setValid(false);
					}
				}

				@Override
				public void warning(SAXParseException e) {
					addIssue(e, ValidationIssue.IssueType.WARNING, "Warning");
				}

				@Override
				public void error(SAXParseException e) {
					addIssue(e, ValidationIssue.IssueType.ERROR, "Error");
				}

				@Override
				public void fatalError(SAXParseException e) {
					addIssue(e, ValidationIssue.IssueType.ERROR, "Fatal Error");
				}
			});

			builder.parse(dmnInputStream); // This checks for well-formedness.
			if (result.isValid()
					&& issues.stream().noneMatch(issue -> issue.getType() == ValidationIssue.IssueType.ERROR)) {
				issues.add(new ValidationIssue(ValidationIssue.IssueType.INFO,
						"XML is well-formed for " + fileName + "."));
			}

		} catch (SAXException e) {
			result.setValid(false);
			String message = (e instanceof SAXParseException)
					? String.format("XML Parsing Error in %s: %s at line %d, column %d", fileName, e.getMessage(),
							((SAXParseException) e).getLineNumber(), ((SAXParseException) e).getColumnNumber())
					: "XML Parsing SAXException for " + fileName + ": " + e.getMessage();
			issues.add(new ValidationIssue(ValidationIssue.IssueType.ERROR, message));
		} catch (ParserConfigurationException e) {
			result.setValid(false);
			issues.add(new ValidationIssue(ValidationIssue.IssueType.ERROR,
					"XML Parser Configuration Error for " + fileName + ": " + e.getMessage()));
		} catch (IOException e) {
			result.setValid(false);
			issues.add(new ValidationIssue(ValidationIssue.IssueType.ERROR,
					"IO Error during XML parsing for " + fileName + ": " + e.getMessage()));
		}
	}

	private static class ParsedDmnRule {
		String id;
		int originalIndex; // 0-based index in the table
		List<String> inputEntryTexts = new ArrayList<>();
		List<String> outputEntryTexts = new ArrayList<>();

		ParsedDmnRule(String id, int originalIndex) {
			this.id = id;
			this.originalIndex = originalIndex;
		}
	}

	private void checkForRuleOverlapsInDmnFile(InputStream dmnInputStream, List<ValidationIssue> issues,
			String fileName, DiagramValidationResult overallResult)
			throws ParserConfigurationException, SAXException, IOException {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(true);
		DocumentBuilder builder = factory.newDocumentBuilder();
		Document doc = builder.parse(dmnInputStream);

		NodeList decisionTableNodes = doc.getElementsByTagNameNS("*", "decisionTable");
		if (decisionTableNodes.getLength() == 0) {
			decisionTableNodes = doc.getElementsByTagName("decisionTable");
		}

		for (int i = 0; i < decisionTableNodes.getLength(); i++) {
			Node decisionTableNode = decisionTableNodes.item(i);
			if (decisionTableNode.getNodeType() == Node.ELEMENT_NODE) {
				Element decisionTableElement = (Element) decisionTableNode;
				String tableId = decisionTableElement.getAttribute("id");
				String hitPolicy = decisionTableElement.getAttribute("hitPolicy");
				if (hitPolicy == null || hitPolicy.isEmpty()) {
					hitPolicy = "UNIQUE"; // Default hit policy [2]
				}

				List<ParsedDmnRule> rules = new ArrayList<>();
				NodeList ruleNodes = decisionTableElement.getElementsByTagNameNS("*", "rule");
				if (ruleNodes.getLength() == 0) {
					ruleNodes = decisionTableElement.getElementsByTagName("rule");
				}

				for (int r = 0; r < ruleNodes.getLength(); r++) {
					Node ruleNode = ruleNodes.item(r);
					if (ruleNode.getNodeType() == Node.ELEMENT_NODE) {
						Element ruleElement = (Element) ruleNode;
						ParsedDmnRule parsedRule = new ParsedDmnRule(ruleElement.getAttribute("id"), r);

						NodeList inputEntryNodes = ruleElement.getElementsByTagNameNS("*", "inputEntry");
						if (inputEntryNodes.getLength() == 0) {
							inputEntryNodes = ruleElement.getElementsByTagName("inputEntry");
						}
						for (int ie = 0; ie < inputEntryNodes.getLength(); ie++) {
							Element inputEntryElement = (Element) inputEntryNodes.item(ie);
							Node textNode = inputEntryElement.getElementsByTagNameNS("*", "text").item(0);
							if (textNode == null)
								textNode = inputEntryElement.getElementsByTagName("text").item(0);
							parsedRule.inputEntryTexts.add(textNode != null ? textNode.getTextContent() : "");
						}

						NodeList outputEntryNodes = ruleElement.getElementsByTagNameNS("*", "outputEntry");
						if (outputEntryNodes.getLength() == 0) {
							outputEntryNodes = ruleElement.getElementsByTagName("outputEntry");
						}
						for (int oe = 0; oe < outputEntryNodes.getLength(); oe++) {
							Element outputEntryElement = (Element) outputEntryNodes.item(oe);
							Node textNode = outputEntryElement.getElementsByTagNameNS("*", "text").item(0);
							if (textNode == null)
								textNode = outputEntryElement.getElementsByTagName("text").item(0);
							parsedRule.outputEntryTexts.add(textNode != null ? textNode.getTextContent() : "");
						}
						rules.add(parsedRule);
					}
				}
				analyzeOverlapsForTable(rules, hitPolicy, tableId, issues, fileName, overallResult);
			}
		}
	}

	private void analyzeOverlapsForTable(List<ParsedDmnRule> rules, String hitPolicy, String tableId,
			List<ValidationIssue> issues, String fileName, DiagramValidationResult overallResult) {
		if (rules.size() < 2)
			return; // No overlaps possible with less than 2 rules

		for (int i = 0; i < rules.size(); i++) {
			for (int j = i + 1; j < rules.size(); j++) {
				ParsedDmnRule rule1 = rules.get(i);
				ParsedDmnRule rule2 = rules.get(j);

				if (rulesMightOverlap(rule1, rule2)) {
					// Determine if outputs are the same
					boolean outputsAreSame = compareOutputs(rule1.outputEntryTexts, rule2.outputEntryTexts);
					String rule1Desc = "Rule " + (rule1.originalIndex + 1)
							+ (rule1.id != null && !rule1.id.isEmpty() ? " (ID: " + rule1.id + ")" : "") + ")";
					String rule2Desc = "Rule " + (rule2.originalIndex + 1)
							+ (rule2.id != null && !rule2.id.isEmpty() ? " (ID: " + rule2.id + ")" : "") + ")";
					String overlapMessage = String.format("%s and %s in table '%s' (file: %s) overlap.", rule1Desc,
							rule2Desc, tableId, fileName);

					if ("UNIQUE".equalsIgnoreCase(hitPolicy)) {
						issues.add(new ValidationIssue(ValidationIssue.IssueType.ERROR,
								overlapMessage + " This violates UNIQUE hit policy."));
						overallResult.setValid(false);
					} else if ("ANY".equalsIgnoreCase(hitPolicy) && !outputsAreSame) {
						issues.add(new ValidationIssue(ValidationIssue.IssueType.ERROR,
								overlapMessage + " They have different outputs, which violates ANY hit policy."));
						overallResult.setValid(false);
					} else {
						// For other hit policies (FIRST, PRIORITY, COLLECT etc.), an overlap isn't
						// necessarily an error.
						// It could be an INFO or WARNING based on specific requirements.
						issues.add(new ValidationIssue(ValidationIssue.IssueType.INFO, overlapMessage
								+ " Hit policy is '" + hitPolicy + "'. Check if this overlap is intended."));
					}
				}
			}
		}
	}

	private boolean rulesMightOverlap(ParsedDmnRule rule1, ParsedDmnRule rule2) {
		if (rule1.inputEntryTexts.size() != rule2.inputEntryTexts.size()) {
			return false; // Should not happen in a well-formed table
		}
		for (int k = 0; k < rule1.inputEntryTexts.size(); k++) {
			String entry1Text = rule1.inputEntryTexts.get(k).trim();
			String entry2Text = rule2.inputEntryTexts.get(k).trim();

			boolean isEntry1Wildcard = entry1Text.isEmpty() || entry1Text.equals("-");
			boolean isEntry2Wildcard = entry2Text.isEmpty() || entry2Text.equals("-");

			if (isEntry1Wildcard || isEntry2Wildcard) {
				continue; // This input condition overlaps
			}
			if (!entry1Text.equals(entry2Text)) {
				return false;
			}
		}
		return true; // All input entries (conditions) found to be compatible/overlapping.
	}

	private boolean compareOutputs(List<String> outputs1, List<String> outputs2) {
		if (outputs1.size() != outputs2.size())
			return false; // Different number of output columns
		for (int i = 0; i < outputs1.size(); i++) {
			if (!Objects.equals(outputs1.get(i), outputs2.get(i))) {
				return false;
			}
		}
		return true;
	}

	private void checkForMissingDescriptions(InputStream dmnInputStream, List<ValidationIssue> issues, String fileName,
			DiagramValidationResult overallResult) throws ParserConfigurationException, SAXException, IOException {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(true);
		DocumentBuilder builder = factory.newDocumentBuilder();
		Document doc = builder.parse(dmnInputStream);

		// Define the DMN elements that should have descriptions
		String[] elementsToCheck = { "decision", "inputData", "businessKnowledgeModel" };

		for (String elementName : elementsToCheck) {
			NodeList nodeList = doc.getElementsByTagNameNS("*", elementName);
			if (nodeList.getLength() == 0) {
				nodeList = doc.getElementsByTagName(elementName);
			}
			for (int i = 0; i < nodeList.getLength(); i++) {
				Node node = nodeList.item(i);
				if (node.getNodeType() == Node.ELEMENT_NODE) {
					Element element = (Element) node;
					String elementId = element.getAttribute("id");
					String elementNameValue = element.getAttribute("name"); // Or fetch from <name> tag if it exists

					// Check for description element
					NodeList descriptionNodes = element.getElementsByTagNameNS("*", "description");
					if (descriptionNodes.getLength() == 0) {
						descriptionNodes = element.getElementsByTagName("description");
					}

					if (descriptionNodes.getLength() == 0) {
						// No <description> tag. Report issue.
						String message = String.format(
								"DMN Element '%s' (ID: %s, Name: '%s') in file '%s' is missing a description.",
								elementName, elementId, elementNameValue, fileName);
						issues.add(new ValidationIssue(ValidationIssue.IssueType.WARNING, message));
					} else {
						// Description tag exists, check if it's empty.
						Node descriptionNode = descriptionNodes.item(0);
						if (descriptionNode.getTextContent() == null
								|| descriptionNode.getTextContent().trim().isEmpty()) {
							String message = String.format(
									"DMN Element '%s' (ID: %s, Name: '%s') in file '%s' has an empty description.",
									elementName, elementId, elementNameValue, fileName);
							issues.add(new ValidationIssue(ValidationIssue.IssueType.WARNING, message));
						}
					}
				}
			}
		}
	}

	private void checkForTypeConsistency(InputStream dmnInputStream, List<ValidationIssue> issues, String fileName,
			DiagramValidationResult overallResult) throws ParserConfigurationException, SAXException, IOException {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(true);
		DocumentBuilder builder = factory.newDocumentBuilder();
		Document doc = builder.parse(dmnInputStream);

		NodeList decisionTableNodes = doc.getElementsByTagNameNS("*", "decisionTable");
		if (decisionTableNodes.getLength() == 0) {
			decisionTableNodes = doc.getElementsByTagName("decisionTable"); // Fallback
		}

		for (int i = 0; i < decisionTableNodes.getLength(); i++) {
			Node decisionTableNode = decisionTableNodes.item(i);
			if (decisionTableNode.getNodeType() == Node.ELEMENT_NODE) {
				Element decisionTableElement = (Element) decisionTableNode;
				String tableId = decisionTableElement.getAttribute("id");
				if (tableId == null || tableId.isEmpty()) {
					tableId = "UnnamedTable" + i;
				}

				// Extract input typeRefs
				List<String> inputTypeRefs = new ArrayList<>();
				NodeList inputNodes = decisionTableElement.getElementsByTagNameNS("*", "input");
				if (inputNodes.getLength() == 0) {
					inputNodes = decisionTableElement.getElementsByTagName("input");
				}
				for (int j = 0; j < inputNodes.getLength(); j++) {
					Element inputElement = (Element) inputNodes.item(j);
					NodeList inputExpressionNodes = inputElement.getElementsByTagNameNS("*", "inputExpression");
					if (inputExpressionNodes.getLength() == 0) {
						inputExpressionNodes = inputElement.getElementsByTagName("inputExpression");
					}
					if (inputExpressionNodes.getLength() > 0) {
						Element inputExpressionElement = (Element) inputExpressionNodes.item(0);
						inputTypeRefs.add(inputExpressionElement.getAttribute("typeRef"));
					} else {
						inputTypeRefs.add(null); // No typeRef specified
					}
				}

				// Extract output typeRefs
				List<String> outputTypeRefs = new ArrayList<>();
				NodeList outputNodes = decisionTableElement.getElementsByTagNameNS("*", "output");
				if (outputNodes.getLength() == 0) {
					outputNodes = decisionTableElement.getElementsByTagName("output");
				}
				for (int j = 0; j < outputNodes.getLength(); j++) {
					Element outputElement = (Element) outputNodes.item(j);
					outputTypeRefs.add(outputElement.getAttribute("typeRef"));
				}

				// Check rules
				NodeList ruleNodes = decisionTableElement.getElementsByTagNameNS("*", "rule");
				if (ruleNodes.getLength() == 0) {
					ruleNodes = decisionTableElement.getElementsByTagName("rule");
				}
				for (int r = 0; r < ruleNodes.getLength(); r++) {
					Node ruleNode = ruleNodes.item(r);
					if (ruleNode.getNodeType() == Node.ELEMENT_NODE) {
						Element ruleElement = (Element) ruleNode;
						String ruleId = ruleElement.getAttribute("id");
						if (ruleId == null || ruleId.isEmpty()) {
							ruleId = "UnnamedRule" + r;
						}

						// Check input entries
						NodeList inputEntryNodes = ruleElement.getElementsByTagNameNS("*", "inputEntry");
						if (inputEntryNodes.getLength() == 0) {
							inputEntryNodes = ruleElement.getElementsByTagName("inputEntry");
						}
						for (int ie = 0; ie < inputEntryNodes.getLength(); ie++) {
							if (ie < inputTypeRefs.size()) {
								String expectedType = inputTypeRefs.get(ie);
								Element inputEntryElement = (Element) inputEntryNodes.item(ie);
								NodeList textNodes = inputEntryElement.getElementsByTagNameNS("*", "text");
								if (textNodes.getLength() == 0) {
									textNodes = inputEntryElement.getElementsByTagName("text");
								}
								if (textNodes.getLength() > 0) {
									String actualText = textNodes.item(0).getTextContent();
									if (expectedType != null && !expectedType.trim().isEmpty() && actualText != null
											&& !actualText.trim().isEmpty() && !actualText.trim().equals("-")) { // Skip
																													// wildcards
										if (!isLiteralTypeConsistent(actualText.trim(), expectedType.trim())) {
											String message = String.format(
													"Type inconsistency in Table '%s', Rule '%s' (File: %s): Input Entry %d expected type '%s' but found literal '%s' which appears to be of a different type.",
													tableId, ruleId, fileName, ie + 1, expectedType, actualText);
											issues.add(new ValidationIssue(ValidationIssue.IssueType.WARNING, message,
													ruleId, "Input Entry " + (ie + 1)));
										}
									}
								}
							}
						}

						// Check output entries
						NodeList outputEntryNodes = ruleElement.getElementsByTagNameNS("*", "outputEntry");
						if (outputEntryNodes.getLength() == 0) {
							outputEntryNodes = ruleElement.getElementsByTagName("outputEntry");
						}
						for (int oe = 0; oe < outputEntryNodes.getLength(); oe++) {
							if (oe < outputTypeRefs.size()) {
								String expectedType = outputTypeRefs.get(oe);
								Element outputEntryElement = (Element) outputEntryNodes.item(oe);
								NodeList textNodes = outputEntryElement.getElementsByTagNameNS("*", "text");
								if (textNodes.getLength() == 0) {
									textNodes = outputEntryElement.getElementsByTagName("text");
								}
								if (textNodes.getLength() > 0) {
									String actualText = textNodes.item(0).getTextContent();
									if (expectedType != null && !expectedType.trim().isEmpty() && actualText != null
											&& !actualText.trim().isEmpty() && !actualText.trim().equals("-")) { // Skip
																													// wildcards
										if (!isLiteralTypeConsistent(actualText.trim(), expectedType.trim())) {
											String message = String.format(
													"Type inconsistency in Table '%s', Rule '%s' (File: %s): Output Entry %d expected type '%s' but found literal '%s' which appears to be of a different type.",
													tableId, ruleId, fileName, oe + 1, expectedType, actualText);
											issues.add(new ValidationIssue(ValidationIssue.IssueType.WARNING, message,
													ruleId, "Output Entry " + (oe + 1)));
										}
									}
								}
							}
						}
					}
				}
			}
		}
	}

	private boolean isLiteralTypeConsistent(String textContent, String typeRef) {
		// Normalize typeRef for common FEEL types
		String normalizedTypeRef = typeRef.toLowerCase();
		boolean isQuoted = textContent.startsWith("\"") && textContent.endsWith("\"");

		switch (normalizedTypeRef) {
		case "number":
		case "integer": // Common alias or specific number type
		case "long":
		case "double":
			if (isQuoted)
				return false; // "123" is a string, not a number literal
			if (textContent.equalsIgnoreCase("true") || textContent.equalsIgnoreCase("false"))
				return false; // Booleans are not numbers
			try {
				Double.parseDouble(textContent);
				return true;
			} catch (NumberFormatException e) {
				return false;
			}
		case "boolean":
			if (isQuoted)
				return false; // "true" is a string, not a boolean literal
			return textContent.equalsIgnoreCase("true") || textContent.equalsIgnoreCase("false");
		case "string":
			// A string literal in FEEL is typically quoted.
			// If it's not quoted, it might be a variable name or an unquoted string (less
			// common for literals).
			// For strict literal checking as per your examples:
			// "18" is a string. 18 is a number. "true" is a string. true is a boolean.
			// If typeRef is string, an unquoted number or boolean literal is an
			// inconsistency.
			if (isQuoted)
				return true; // Correctly quoted string
			// Check if it looks like an unquoted boolean
			if (textContent.equalsIgnoreCase("true") || textContent.equalsIgnoreCase("false"))
				return false;
			// Check if it looks like an unquoted number
			try {
				Double.parseDouble(textContent);
				return false; // It's a number literal, not a string literal as per strict interpretation
			} catch (NumberFormatException e) {
				// Not a number, not a boolean, not quoted. Can be considered an unquoted
				// string.
				return true;
			}
		case "date":
			// Example: 2024-01-01 (unquoted) or date("2024-01-01") (FEEL function)
			if (isQuoted)
				return false; // "2024-01-01" is a string
			// Basic check for YYYY-MM-DD format or FEEL date function
			return textContent.matches("^\\d{4}-\\d{2}-\\d{2}$")
					|| textContent.matches("^date\\s*\\(\\s*\".*\"\\s*\\)$");
		case "time":
			if (isQuoted)
				return false;
			return textContent.matches("^\\d{2}:\\d{2}:\\d{2}(Z|([+-]\\d{2}:\\d{2}))?$") || // Basic time format
					textContent.matches("^time\\s*\\(\\s*\".*\"\\s*\\)$"); // FEEL time function
		case "datetime": // DMN often uses "dateTime"
		case "date and time":
			if (isQuoted)
				return false;
			return textContent.matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(Z|([+-]\\d{2}:\\d{2}))?$") || // Basic
																													// dateTime
					textContent.matches("^(dateTime|date and time)\\s*\\(\\s*\".*\"\\s*\\)$"); // FEEL dateTime function
		// Duration is more complex due to various PnYnMnDTnHnMnS formats.
		// case "duration":
		// return textContent.matches("^duration\\s*\\(\\s*\".*\"\\s*\\)$") ||
		// textContent.startsWith("P");
		default:
			// For unknown or complex types (like custom itemDefinitions, or durations not
			// simply checked),
			// this basic literal checker doesn't validate. We assume consistency or issue
			// INFO if stricter.
			// System.out.println("INFO: Type consistency check not performed for typeRef: "
			// + typeRef + " and literal: " + textContent);
			return true; // Pass for types not explicitly handled by this simple checker.
		}
	}

	private void checkHitPolicyCompatibility(InputStream dmnInputStream, List<ValidationIssue> issues, String fileName,
			DiagramValidationResult overallResult) throws ParserConfigurationException, SAXException, IOException {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(true);
		DocumentBuilder builder = factory.newDocumentBuilder();
		Document doc = builder.parse(dmnInputStream);

		NodeList decisionTableNodes = doc.getElementsByTagNameNS("*", "decisionTable");
		if (decisionTableNodes.getLength() == 0) {
			decisionTableNodes = doc.getElementsByTagName("decisionTable");
		}

		for (int i = 0; i < decisionTableNodes.getLength(); i++) {
			Node decisionTableNode = decisionTableNodes.item(i);
			if (decisionTableNode.getNodeType() == Node.ELEMENT_NODE) {
				Element decisionTableElement = (Element) decisionTableNode;
				String tableId = decisionTableElement.getAttribute("id");
				String hitPolicy = decisionTableElement.getAttribute("hitPolicy");
				if (hitPolicy == null || hitPolicy.isEmpty()) {
					hitPolicy = "UNIQUE"; // Default hit policy
				}
				List<ParsedDmnRule> rules = new ArrayList<>();
				NodeList ruleNodes = decisionTableElement.getElementsByTagNameNS("*", "rule");
				if (ruleNodes.getLength() == 0) {
					ruleNodes = decisionTableElement.getElementsByTagName("rule");
				}

				for (int r = 0; r < ruleNodes.getLength(); r++) {
					Node ruleNode = ruleNodes.item(r);
					if (ruleNode.getNodeType() == Node.ELEMENT_NODE) {
						Element ruleElement = (Element) ruleNode;
						ParsedDmnRule parsedRule = new ParsedDmnRule(ruleElement.getAttribute("id"), r);

						NodeList inputEntryNodes = ruleElement.getElementsByTagNameNS("*", "inputEntry");
						if (inputEntryNodes.getLength() == 0) {
							inputEntryNodes = ruleElement.getElementsByTagName("inputEntry");
						}
						for (int ie = 0; ie < inputEntryNodes.getLength(); ie++) {
							Element inputEntryElement = (Element) inputEntryNodes.item(ie);
							Node textNode = inputEntryElement.getElementsByTagNameNS("*", "text").item(0);
							if (textNode == null)
								textNode = inputEntryElement.getElementsByTagName("text").item(0);
							parsedRule.inputEntryTexts.add(textNode != null ? textNode.getTextContent() : "");
						}

						NodeList outputEntryNodes = ruleElement.getElementsByTagNameNS("*", "outputEntry");
						if (outputEntryNodes.getLength() == 0) {
							outputEntryNodes = ruleElement.getElementsByTagName("outputEntry");
						}
						for (int oe = 0; oe < outputEntryNodes.getLength(); oe++) {
							Element outputEntryElement = (Element) outputEntryNodes.item(oe);
							Node textNode = outputEntryElement.getElementsByTagNameNS("*", "text").item(0);
							if (textNode == null)
								textNode = outputEntryElement.getElementsByTagName("text").item(0);
							parsedRule.outputEntryTexts.add(textNode != null ? textNode.getTextContent() : "");
						}
						rules.add(parsedRule);
					}
				}

				switch (hitPolicy.toUpperCase()) {
				case "UNIQUE":
					checkUniqueHitPolicy(rules, tableId, issues, fileName);
					break;
				case "ANY":
					checkAnyHitPolicy(rules, tableId, issues, fileName);
					break;
				// Implement other hit policy checks here: FIRST, PRIORITY, COLLECT, etc.
				default:
					// If the hit policy is not supported or recognized, provide a warning.
					issues.add(new ValidationIssue(ValidationIssue.IssueType.INFO,
							"Hit policy '" + hitPolicy
									+ "' is not explicitly validated, manual review recommended for table '" + tableId
									+ "' in file '" + fileName + "'."));
					break;
				}
			}
		}
	}

	private void checkUniqueHitPolicy(List<ParsedDmnRule> rules, String tableId, List<ValidationIssue> issues,
			String fileName) {
		if (rules.size() < 2)
			return; // No conflicts possible with fewer than 2 rules

		for (int i = 0; i < rules.size(); i++) {
			for (int j = i + 1; j < rules.size(); j++) {
				ParsedDmnRule rule1 = rules.get(i);
				ParsedDmnRule rule2 = rules.get(j);

				if (rulesMightOverlap(rule1, rule2)) {
					String rule1Desc = "Rule " + (rule1.originalIndex + 1)
							+ (rule1.id != null && !rule1.id.isEmpty() ? " (ID: " + rule1.id + ")" : "");
					String rule2Desc = "Rule " + (rule2.originalIndex + 1)
							+ (rule2.id != null && !rule2.id.isEmpty() ? " (ID: " + rule2.id + ")" : "");
					String overlapMessage = String.format(
							"%s and %s in table '%s' (file: %s) overlap, which violates UNIQUE hit policy.", rule1Desc,
							rule2Desc, tableId, fileName);
					issues.add(new ValidationIssue(ValidationIssue.IssueType.ERROR, overlapMessage));
				}
			}
		}
	}

	private void checkAnyHitPolicy(List<ParsedDmnRule> rules, String tableId, List<ValidationIssue> issues,
			String fileName) {
		if (rules.size() < 2)
			return; // Need at least two rules to potentially violate ANY

		for (int i = 0; i < rules.size(); i++) {
			for (int j = i + 1; j < rules.size(); j++) {
				ParsedDmnRule rule1 = rules.get(i);
				ParsedDmnRule rule2 = rules.get(j);

				if (rulesMightOverlap(rule1, rule2)
						&& !compareOutputs(rule1.outputEntryTexts, rule2.outputEntryTexts)) {
					String rule1Desc = "Rule " + (rule1.originalIndex + 1)
							+ (rule1.id != null && !rule1.id.isEmpty() ? " (ID: " + rule1.id + ")" : "");
					String rule2Desc = "Rule " + (rule2.originalIndex + 1)
							+ (rule2.id != null && !rule2.id.isEmpty() ? " (ID: " + rule2.id + ")" : "");
					String overlapMessage = String.format(
							"%s and %s in table '%s' (file: %s) overlap but have different outputs, which violates ANY hit policy.",
							rule1Desc, rule2Desc, tableId, fileName);
					issues.add(new ValidationIssue(ValidationIssue.IssueType.ERROR, overlapMessage));
				}
			}
		}
	}

}
