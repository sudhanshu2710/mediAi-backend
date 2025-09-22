package medi.ai.mediAi_backend.controller;

import lombok.RequiredArgsConstructor;
import medi.ai.mediAi_backend.service.ReportProcessingService;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.UUID;

/**
 * Upload endpoint: returns immediate ack and starts background processing.
 */

@CrossOrigin(origins = {
        "http://localhost:3000",
        "https://tiwarivarun28.github.io"
})
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportProcessingService reportProcessingService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, String> uploadReport(@RequestPart("file") MultipartFile file,
                                            @RequestParam(value = "userId", required = false) String userId) {
        if (file == null || file.isEmpty()) {
            return Map.of("error", "file missing");
        }

        if (!StringUtils.hasText(userId)) {
            userId = UUID.randomUUID().toString();
        }

        reportProcessingService.processReportAsync(file, userId);

        // immediate ACK to frontend (includes userId so frontend can subscribe)
        return Map.of("message", "Processing completed", "userId", userId);
    }
}
