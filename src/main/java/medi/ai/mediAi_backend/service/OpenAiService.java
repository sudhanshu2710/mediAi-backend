package medi.ai.mediAi_backend.service;

import org.springframework.web.multipart.MultipartFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;


import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.*;
import java.util.Base64;

@Service
@RequiredArgsConstructor
public class OpenAiService {
    private final WebClient openAiWebClient;

    @Value("${app.openai.api-key}")
    private String openAiApiKey;

    @Value("${app.openai.chat-model}")
    private String chatModel;

    // --- Centralized helper with retry + exponential backoff ---
    private ChatResponse makeChatCompletionCall(Map<String, Object> payload) {
        return openAiWebClient.post()
                .uri("/chat/completions")
                .headers(h -> h.setBearerAuth(openAiApiKey))
                .bodyValue(payload)
                .retrieve()
                .onStatus(HttpStatus.TOO_MANY_REQUESTS::equals, resp ->
                        resp.bodyToMono(String.class)
                                .flatMap(body -> {
                                    return reactor.core.publisher.Mono.error(
                                            new RuntimeException("429 Too Many Requests: " + body)
                                    );
                                })
                )
                .bodyToMono(ChatResponse.class)
                .retryWhen(
                        Retry.backoff(3, Duration.ofSeconds(2)) // 3 retries: 2s, 4s, 8s
                                .maxBackoff(Duration.ofSeconds(10))
                                .filter(ex -> ex.getMessage() != null &&
                                        (ex.getMessage().contains("429") ||
                                                ex.getMessage().contains("5xx") ||
                                                ex.getMessage().contains("502")))
                )
                .block();
    }

    public String generateOverallFinding(String enrichedJson, String systemPrompt) {
        Map<String,Object> userMessage = Map.of(
                "role", "user",
                "content", "Here is the JSON report:\n" + enrichedJson + "\n\nAdd overall_finding as per instructions."
        );

        Map<String,Object> payload = new HashMap<>();
        payload.put("model", chatModel);
        payload.put("messages", List.of(
                Map.of("role","system","content", systemPrompt),
                userMessage
        ));
        payload.put("max_tokens", 1500);

        ChatResponse resp = makeChatCompletionCall(payload);

        if (resp != null && resp.choices != null && !resp.choices.isEmpty() && resp.choices.get(0).message != null) {
            return resp.choices.get(0).message.content;
        }
        return enrichedJson; // fallback
    }

    // --- Vision: image ---
    public String visionChatCompletion(String systemPrompt,
                                       String userTextPrompt,
                                       MultipartFile imageFile,
                                       int maxTokens) throws IOException {
        byte[] bytes = imageFile.getBytes();
        String base64 = Base64.getEncoder().encodeToString(bytes);
        String mime = Optional.ofNullable(imageFile.getContentType()).orElse("image/png");
        String dataUrl = "data:" + mime + ";base64," + base64;

        List<Object> contentBlocks = new ArrayList<>();
        contentBlocks.add(Map.of("type", "text", "text", userTextPrompt));
        contentBlocks.add(Map.of("type", "image_url", "image_url", Map.of("url", dataUrl)));

        List<Map<String,Object>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role","system","content", systemPrompt));
        }
        messages.add(Map.of("role","user","content", contentBlocks));

        Map<String,Object> payload = new HashMap<>();
        payload.put("model", chatModel);
        payload.put("messages", messages);
        payload.put("max_tokens", Math.min(maxTokens, 2000));

        ChatResponse resp = makeChatCompletionCall(payload);

        if (resp != null && resp.choices != null && !resp.choices.isEmpty() && resp.choices.get(0).message != null) {
            return resp.choices.get(0).message.content;
        }
        return null;
    }

    public String pdfToImageAndProcess(MultipartFile pdfFile, String systemPrompt, String userPrompt) throws Exception {
        PDDocument doc = PDDocument.load(pdfFile.getInputStream());
        PDFRenderer renderer = new PDFRenderer(doc);

        // Build content blocks for ALL pages
        List<Object> contentBlocks = new ArrayList<>();
        contentBlocks.add(Map.of("type", "text", "text", userPrompt));

        for (int i = 0; i < doc.getNumberOfPages(); i++) {
            BufferedImage pageImage = renderer.renderImageWithDPI(i, 72); // reduced DPI for smaller payload
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
//            ImageIO.write(pageImage, "png", baos);
            ImageIO.write(pageImage, "jpg", baos); // smaller than png

            String base64 = Base64.getEncoder().encodeToString(baos.toByteArray());
            String dataUrl = "data:image/png;base64," + base64;

            contentBlocks.add(Map.of("type", "image_url", "image_url", Map.of("url", dataUrl)));
        }
        doc.close();

        List<Map<String,Object>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role","system","content", systemPrompt));
        }
        messages.add(Map.of("role","user","content", contentBlocks));

        Map<String,Object> payload = new HashMap<>();
        payload.put("model", chatModel);
        payload.put("messages", messages);
        payload.put("max_tokens", 2000);

        ChatResponse resp = makeChatCompletionCall(payload);

        if (resp != null && resp.choices != null && !resp.choices.isEmpty() && resp.choices.get(0).message != null) {
            return resp.choices.get(0).message.content;
        }
        return null;
    }
    public String pdfToTextAndProcess(MultipartFile pdfFile, String systemPrompt, String userPrompt) throws Exception {
        PDDocument doc = PDDocument.load(pdfFile.getInputStream());
        PDFTextStripper stripper = new PDFTextStripper();
        String text = stripper.getText(doc);
        doc.close();

        // If no text found, fallback to vision (scanned PDF)
        if (text == null || text.trim().isEmpty()) {
            return pdfToImageAndProcess(pdfFile, systemPrompt, userPrompt);
        }

        // Build full chat request using text only
        List<Map<String, Object>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.add(Map.of("role", "user", "content", userPrompt + "\n\n" + text));

        Map<String, Object> payload = new HashMap<>();
        payload.put("model", chatModel);
        payload.put("messages", messages);
        payload.put("max_tokens", 2000);

        ChatResponse resp = makeChatCompletionCall(payload);

        if (resp != null && resp.choices != null && !resp.choices.isEmpty() && resp.choices.get(0).message != null) {
            return resp.choices.get(0).message.content;
        }
        return null;
    }
    // --- DTOs ---
    @Data
    private static class EmbeddingResponse {
        public String object;
        public List<EmbeddingData> data;
    }
    @Data
    private static class EmbeddingData {
        public List<Double> embedding;
        public int index;
    }
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ChatResponse {
        public String id;
        public List<Choice> choices;
        public Usage usage;
    }
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class Choice {
        public int index;
        public Message message;
    }
    @Data
    private static class Message {
        public String role;
        public String content;
    }
    @Data
    private static class Usage {
        public Integer prompt_tokens;
        public Integer completion_tokens;
    }
}
