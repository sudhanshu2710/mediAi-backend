package medi.ai.mediAi_backend.service;

import lombok.RequiredArgsConstructor;
import medi.ai.mediAi_backend.util.PromptGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Background worker that performs the sequential pipeline:
 * 1) extraction (vision/pdf)
 * 2) push partial JSON (patient + test_summary)
 * 3) enrich statuses (Java)
 * 4) generate overall_finding (OpenAI)
 * 5) push final JSON
 */
@Service
@RequiredArgsConstructor
public class ReportProcessingService {

    private static final Logger log = LoggerFactory.getLogger(ReportProcessingService.class);

    private final OpenAiService openAiService;
    private final LabStatusService labStatusService;
    private final PromptGenerator promptGenerator;
    private final SimpMessagingTemplate messagingTemplate;


    public void processReportAsync(MultipartFile file, String userId) {
        final String destination = "/medicalReportTopic/" + userId;
        try {
            String systemPrompt = promptGenerator.loadPromptText("/medicalReportPrompt.txt");
            String userPrompt = "This is a medical report. Extract the single JSON object exactly as required by the system prompt. Return only JSON.";

            // 1) Extract (vision or PDF)
            String contentType = file.getContentType();
            String cleanJson;
            if (contentType != null && contentType.startsWith("image/")) {
                cleanJson = openAiService.visionChatCompletion(systemPrompt, userPrompt, file, 8000);
            } else {
                cleanJson = openAiService.pdfToImageAndProcess(file, systemPrompt, userPrompt);
            }

            if (cleanJson == null || cleanJson.isBlank()) {
                throw new IllegalStateException("Extraction returned empty result");
            }

            // Remove markdown fences if model wrapped the JSON
            cleanJson = cleanJson.replaceAll("```json", "").replaceAll("```", "").trim();


            // 3) Enrich statuses (local deterministic Java)
            String enrichedJson = labStatusService.enrichWithStatuses(cleanJson);
            if (enrichedJson == null || enrichedJson.isBlank()) {
                enrichedJson = cleanJson; // safe fallback
            }
            messagingTemplate.convertAndSend(destination, enrichedJson);
            log.info("Pushed partial report to {} (userId={})", destination, userId);
            // 4) Generate overall_finding via OpenAI
            String overallPrompt = promptGenerator.loadPromptText("/overallFindingPrompt.txt");
            String finalJson = openAiService.generateOverallFinding(enrichedJson, overallPrompt);
            if (finalJson == null || finalJson.isBlank()) {
                finalJson = enrichedJson; // fallback
            }

            // Clean fences again
            finalJson = finalJson.replaceAll("```json", "").replaceAll("```", "").trim();

            // 5) Push final result
            messagingTemplate.convertAndSend(destination, finalJson);
            log.info("Pushed final report to {} (userId={})", destination, userId);

        } catch (IOException ioEx) {
            log.error("I/O error during report processing for userId={}", userId, ioEx);
            sendError(destination, "io_error", ioEx.getMessage());
        } catch (Exception ex) {
            log.error("Report processing failed for userId={}", userId, ex);
            sendError(destination, "processing_failed", ex.getMessage());
        }
    }

    private void sendError(String destination, String code, String message) {
        // Minimal error object — frontend can detect "error" key
        String sanitized = (message == null) ? "" : message.replace("\"", "'");
        String errJson = String.format("{\"error\":\"%s\",\"message\":\"%s\"}", code, sanitized);
        try {
            messagingTemplate.convertAndSend(destination, errJson);
        } catch (Exception e) {
            log.error("Failed to send error message to {}: {}", destination, e.getMessage());
        }
    }
}
