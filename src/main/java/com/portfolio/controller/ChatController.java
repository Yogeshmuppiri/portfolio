package com.portfolio.controller;

import com.portfolio.service.DocumentTextExtractorService;
import com.portfolio.service.GeminiService;
import com.portfolio.service.PortfolioKnowledgeService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final PortfolioKnowledgeService knowledgeService;
    private final GeminiService geminiService;
    private final DocumentTextExtractorService extractorService;

    public ChatController(
        PortfolioKnowledgeService knowledgeService,
        GeminiService geminiService,
        DocumentTextExtractorService extractorService
    ) {
        this.knowledgeService = knowledgeService;
        this.geminiService = geminiService;
        this.extractorService = extractorService;
    }

    private static final int MAX_SUMMARY_SOURCE_CHARS = 30000;
    private static final long MAX_UPLOAD_BYTES = 10L * 1024L * 1024L;

    private final String SYSTEM_PROMPT = """
        You are an AI assistant for Yogesh Muppiri's portfolio.
        Use only the provided "Portfolio Knowledge" context for factual claims.
        If information is not present in the context, clearly say it is not listed in Yogesh's portfolio.
        Keep answers concise, professional, and friendly.
        Prioritize quantified impact, scale, and business outcomes.
        For broad "about Yogesh" or intro questions, structure the answer as:
        1) Who he is now: current role at Amazon and core strengths,
        2) Previous role: AI/ML Engineer experience and key outcomes,
        3) Projects: mention at least 2 major projects with outcomes,
        4) Skills: short stack summary,
        5) Close with a professional networking line.
        Do not invent information.
        """;

    private final String GENERAL_PROMPT = """
        You are a helpful AI assistant.
        Answer general knowledge and technical questions clearly and accurately.
        Keep responses concise, practical, and easy to understand.
        """;

    @PostMapping("/chat")
    public ResponseEntity<String> chat(@RequestBody Map<String, String> payload) {
        String userMessage = payload.get("message");
        if (userMessage == null || userMessage.trim().isEmpty()) {
            return ResponseEntity.badRequest().body("AI Error: message cannot be empty.");
        }

        boolean portfolioQuery = knowledgeService.isPortfolioQuery(userMessage);
        String prompt;
        if (portfolioQuery) {
            String retrievedKnowledge = knowledgeService.buildContext(userMessage, 8);
            prompt = SYSTEM_PROMPT
                + "\n\nPortfolio Knowledge:\n"
                + retrievedKnowledge
                + "\n\nUser Question: "
                + userMessage
                + "\n\nAnswer:";
        } else {
            prompt = GENERAL_PROMPT
                + "\n\nUser Question: "
                + userMessage
                + "\n\nAnswer:";
        }

        try {
            return ResponseEntity.ok(geminiService.generate(prompt));
        } catch (HttpClientErrorException e) {
            return ResponseEntity.status(e.getStatusCode()).body(formatAiError(e));
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Error connecting to AI brain: " + e.getMessage());
        }
    }

    @PostMapping(value = "/summarize", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> summarizeDocument(
        @RequestParam("file") MultipartFile file,
        @RequestParam(value = "mode", defaultValue = "medium") String mode
    ) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body("AI Error: Please upload a file.");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body("AI Error: File is too large. Max size is 10 MB.");
        }

        try {
            String extracted = extractorService.extractText(file);
            if (extracted == null || extracted.trim().isEmpty()) {
                return ResponseEntity.badRequest().body("AI Error: No readable text found in the uploaded file.");
            }

            String clipped = extracted.length() > MAX_SUMMARY_SOURCE_CHARS
                ? extracted.substring(0, MAX_SUMMARY_SOURCE_CHARS)
                : extracted;

            String summaryStyle = switch (mode.toLowerCase()) {
                case "short" -> "Provide a short summary in 4-6 bullet points.";
                case "detailed" -> "Provide a detailed summary with key themes, decisions, and action items.";
                default -> "Provide a medium-length summary with key points and a brief conclusion.";
            };

            String prompt = """
                You are a professional document summarization assistant.
                Summarize the uploaded content accurately without hallucinating missing facts.
                Keep language clear and scannable.
                """ + "\n\nInstruction: " + summaryStyle
                + "\n\nDocument Content:\n" + clipped
                + "\n\nSummary:";

            return ResponseEntity.ok(geminiService.generate(prompt));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("AI Error: " + e.getMessage());
        } catch (HttpClientErrorException e) {
            return ResponseEntity.status(e.getStatusCode()).body(formatAiError(e));
        } catch (Exception e) {
            return ResponseEntity.status(500).body("AI Error: Unable to summarize document. " + e.getMessage());
        }
    }

    private String formatAiError(HttpClientErrorException e) {
        String raw = e.getResponseBodyAsString();
        String lower = raw == null ? "" : raw.toLowerCase();

        boolean isQuotaOrRateLimited =
            e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS
                || lower.contains("resource_exhausted")
                || lower.contains("quota exceeded")
                || lower.contains("rate limit");

        if (isQuotaOrRateLimited) {
            String retryIn = extractRetryDelay(raw);
            if (retryIn != null) {
                return "Traffic is high right now, please retry in ~" + retryIn + ".";
            }
            return "Traffic is high right now, please retry in ~15s.";
        }

        if (e.getStatusCode() == HttpStatus.UNAUTHORIZED || e.getStatusCode() == HttpStatus.FORBIDDEN) {
            return "AI configuration issue. Please verify API key and project permissions.";
        }

        return "AI service is temporarily unavailable. Please try again.";
    }

    private String extractRetryDelay(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        Matcher retryInfoMatcher = Pattern.compile("\"retryDelay\"\\s*:\\s*\"([^\"]+)\"").matcher(raw);
        if (retryInfoMatcher.find()) {
            return retryInfoMatcher.group(1).trim();
        }

        Matcher messageMatcher = Pattern.compile("retry in\\s+([0-9]+(?:\\.[0-9]+)?)s?", Pattern.CASE_INSENSITIVE).matcher(raw);
        if (messageMatcher.find()) {
            double seconds = Double.parseDouble(messageMatcher.group(1));
            return Math.max(1, (int) Math.ceil(seconds)) + "s";
        }

        return null;
    }
}
