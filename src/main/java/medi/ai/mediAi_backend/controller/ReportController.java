package medi.ai.mediAi_backend.controller;


import lombok.RequiredArgsConstructor;
import medi.ai.mediAi_backend.service.OpenAiService;
import medi.ai.mediAi_backend.util.PromptGenerator;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.nio.charset.StandardCharsets;
import java.util.*;

@CrossOrigin(origins = {
        "http://localhost:3000",
        "https://tiwarivarun28.github.io"
})
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/reports")
public class ReportController {
    private final OpenAiService openAiService;
    private final PromptGenerator promptGenerator = new PromptGenerator();

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public String uploadReport(@RequestPart("file") MultipartFile file,
                               @RequestParam("userId") String userId) throws Exception {
        System.out.println("-----------------uploadReport method is executed!!---------------------");
        if (file == null || file.isEmpty()) {
            return "{\"error\":\"file missing\"}";
        }
        if (!StringUtils.hasText(userId)) userId = UUID.randomUUID().toString();
        // 3) fallback pipeline: chunk, embeddings, upsert to Pinecone under namespace=userId
        String namespace = userId;
        // If this is an image, use the OpenAI Vision chat flow (send image directly).
        String contentType = file.getContentType();
        if (contentType != null && contentType.startsWith("image/")) {

            String systemPrompt = promptGenerator.loadPromptText("/medicalReportPrompt.txt");
            // concise user instruction that complements the system prompt
            String userPrompt = "This is an image of a medical report. Extract the single JSON object exactly as required by the system prompt. Return only JSON.";

            // call the vision-capable method (base64 image in message content)
            String completion = openAiService.visionChatCompletion(systemPrompt, userPrompt, file, 8000);

            // return model output (expected to be JSON per system prompt)
            String cleanJson = completion.replaceAll("```json", "")
                    .replaceAll("```", "")
                    .trim();
            return cleanJson;
        }
        System.out.println("contentType : " + contentType);
        if ((contentType != null && contentType.toLowerCase().contains("pdf"))
                || (file.getOriginalFilename() != null && file.getOriginalFilename().toLowerCase().endsWith(".pdf"))) {
            String systemPrompt = promptGenerator.loadPromptText("/medicalReportPrompt.txt");
            String userPrompt = "This is a PDF medical report. Extract the single JSON object exactly as required by the system prompt. Return only JSON.";

            // Convert PDF pages to images (PNG), loop them through visionChatCompletion
            String completion = openAiService.pdfToImageAndProcess(file, systemPrompt, userPrompt);

            String cleanJson = completion.replaceAll("```json", "")
                    .replaceAll("```", "")
                    .trim();
            return cleanJson;
        }
        return "response not found..... bhai api shi kro yar fir down time hoga !!" ;
    }
}

