package medi.ai.mediAi_backend.util;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class PromptGenerator {
    public String loadPromptText(String path) {
        try (var is = this.getClass().getResourceAsStream(path)) {
            if (is == null) return "";
            byte[] bs = is.readAllBytes();
            return new String(bs, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return "";
        }
    }
}
