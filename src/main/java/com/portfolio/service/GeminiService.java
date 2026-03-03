package com.portfolio.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class GeminiService {

    @Value("${GEMINI_API_KEY:}")
    private String apiKey;

    @Value("${GEMINI_MODELS:gemini-2.5-flash-lite,gemini-2.0-flash-lite,gemini-2.5-flash,gemini-2.0-flash}")
    private String geminiModels;

    private static final String GEMINI_URL_TEMPLATE =
        "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s";

    public String generate(String prompt) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException("AI configuration error: GEMINI_API_KEY is not set.");
        }

        Map<String, Object> requestBody = new HashMap<>();
        Map<String, Object> content = new HashMap<>();
        Map<String, String> part = new HashMap<>();
        part.put("text", prompt);
        content.put("parts", Collections.singletonList(part));
        requestBody.put("contents", Collections.singletonList(content));

        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        List<String> modelCandidates = Arrays.stream(geminiModels.split(","))
            .map(String::trim)
            .filter(model -> !model.isEmpty())
            .collect(Collectors.toList());

        if (modelCandidates.isEmpty()) {
            throw new IllegalStateException("AI configuration error: GEMINI_MODELS is empty.");
        }

        HttpClientErrorException lastModelError = null;

        for (String model : modelCandidates) {
            String url = String.format(GEMINI_URL_TEMPLATE, model, apiKey);
            try {
                ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);
                return parseText(response.getBody());
            } catch (HttpClientErrorException e) {
                if (shouldTryNextModel(e)) {
                    lastModelError = e;
                    continue;
                }
                throw e;
            }
        }

        if (lastModelError != null) {
            throw lastModelError;
        }
        throw new IllegalStateException("AI Error: Failed to get response from Gemini.");
    }

    private String parseText(Map<String, Object> body) {
        if (body == null || !body.containsKey("candidates")) {
            return "I'm sorry, I couldn't process that request.";
        }

        List<Map<String, Object>> candidates = (List<Map<String, Object>>) body.get("candidates");
        if (candidates == null || candidates.isEmpty()) {
            return "I apologize, but I cannot answer that question due to safety guidelines.";
        }

        Map<String, Object> firstCandidate = candidates.get(0);
        Map<String, Object> contentPart = (Map<String, Object>) firstCandidate.get("content");
        if (contentPart == null || !contentPart.containsKey("parts")) {
            return "I'm sorry, I couldn't process that request.";
        }

        List<Map<String, Object>> parts = (List<Map<String, Object>>) contentPart.get("parts");
        if (parts == null || parts.isEmpty() || !parts.get(0).containsKey("text")) {
            return "I'm sorry, I couldn't process that request.";
        }

        return (String) parts.get(0).get("text");
    }

    private boolean shouldTryNextModel(HttpClientErrorException e) {
        if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
            return true;
        }
        String body = e.getResponseBodyAsString();
        if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
            return true;
        }
        if (e.getStatusCode() != HttpStatus.BAD_REQUEST) {
            return false;
        }
        return body != null && (
            body.contains("not supported for generateContent")
            || body.contains("RESOURCE_EXHAUSTED")
            || body.toLowerCase(Locale.ROOT).contains("quota exceeded")
        );
    }
}
