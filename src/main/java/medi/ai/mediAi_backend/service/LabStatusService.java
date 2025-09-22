package medi.ai.mediAi_backend.service;

import medi.ai.mediAi_backend.util.LabStatusCalculator;
import org.springframework.stereotype.Service;

@Service
public class LabStatusService {
    private final LabStatusCalculator calculator = new LabStatusCalculator();

    public String enrichWithStatuses(String json) {
        try {
            return calculator.addStatuses(json);
        } catch (Exception e) {
            e.printStackTrace();
            return json; // fallback: return original
        }
    }
}
