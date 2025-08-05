package com.aaseya.Flowsure.Controller;

import java.io.IOException;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.HttpStatus;
import com.aaseya.Flowsure.DTO.DiagramValidationResponse;
import com.aaseya.Flowsure.Model.DiagramValidationResult;
import com.aaseya.Flowsure.Service.DiagramValidationService;
import com.aaseya.Flowsure.Service.DmnValidationService;

@RestController
@RequestMapping("/api/validate")
public class FlowsureController {
	
	@Autowired
	private DiagramValidationService diagramValidationService;
	
	 @Autowired // Autowire the new DMN validation service
	    private DmnValidationService dmnValidationService;
	
	@PostMapping("/upload-zip") // Changed endpoint name for clarity
    public ResponseEntity<DiagramValidationResponse> validateDiagramsInZip(
            @RequestParam("file") MultipartFile file) { // Expecting a ZIP file
        try {
            List<DiagramValidationResult> results = diagramValidationService.validateZip(file);
            DiagramValidationResponse response = new DiagramValidationResponse(
                    "SUCCESS",
                    "Diagram validation completed for files in ZIP.",
                    results
            );
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            // Log the exception for debugging
            System.err.println("Error during ZIP file validation: " + e.getMessage());
            e.printStackTrace();
            DiagramValidationResponse errorResponse = new DiagramValidationResponse(
                    "FAILURE",
                    "An error occurred during ZIP file processing: " + e.getMessage(),
                    null
            );
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }
	
	
	// New endpoint for DMN validation
    @PostMapping("/upload-dmn-zip")
    public ResponseEntity<DiagramValidationResponse> validateDmnFilesInZip(
            @RequestParam("file") MultipartFile file) { // Expecting a ZIP file with DMN files
        
        if (file.isEmpty()) {
             DiagramValidationResponse errorResponse = new DiagramValidationResponse(
                    "FAILURE",
                    "File is empty. Please upload a valid ZIP file.",
                    null
            );
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
        }
        // Basic check for ZIP file type based on original filename
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".zip")) {
             DiagramValidationResponse errorResponse = new DiagramValidationResponse(
                    "FAILURE",
                    "Invalid file type. Please upload a ZIP file.",
                    null
            );
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
        }

        try {
            List<DiagramValidationResult> results = dmnValidationService.validateDmnZip(file); // Call the new service
            DiagramValidationResponse response = new DiagramValidationResponse(
                    "SUCCESS",
                    "DMN validation completed for files in ZIP.",
                    results
            );
            return ResponseEntity.ok(response);
        } catch (IOException ioe) { // Specific exception handling for IO
            System.err.println("IO Error during DMN ZIP file validation: " + ioe.getMessage());
            ioe.printStackTrace();
            DiagramValidationResponse errorResponse = new DiagramValidationResponse(
                    "FAILURE",
                    "An IO error occurred during DMN ZIP file processing: " + ioe.getMessage(),
                    null
            );
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
        catch (Exception e) { // General exception handling
            System.err.println("Error during DMN ZIP file validation: " + e.getMessage());
            e.printStackTrace();
            DiagramValidationResponse errorResponse = new DiagramValidationResponse(
                    "FAILURE",
                    "An error occurred during DMN ZIP file processing: " + e.getMessage(),
                    null
            );
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }
}


	
	
	


