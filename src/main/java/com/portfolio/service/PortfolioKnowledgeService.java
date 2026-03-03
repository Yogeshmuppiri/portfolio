package com.portfolio.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class PortfolioKnowledgeService {

    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^a-z0-9]+");
    private static final Set<String> STOP_WORDS = Set.of(
        "a", "an", "the", "is", "am", "are", "was", "were", "be", "being", "been",
        "to", "of", "in", "on", "for", "at", "by", "with", "from", "as", "and", "or",
        "what", "who", "how", "when", "where", "why", "which", "tell", "about", "me",
        "you", "your", "his", "her", "their", "this", "that", "it", "please"
    );
    private static final List<String> OVERVIEW_ENTRY_IDS = List.of(
        "summary",
        "amazon_experience",
        "prior_experience",
        "projects_knowva",
        "projects_walmart",
        "skills",
        "creative_pursuits"
    );
    private static final Set<String> PORTFOLIO_HINT_TOKENS = Set.of(
        "yogesh", "portfolio", "resume", "experience", "achievements", "projects",
        "skills", "amazon", "community", "foundation", "linkedin", "certifications",
        "publications", "contact", "about", "hobby", "hobbies", "photography",
        "photo", "photos", "pictures", "creative", "pursuits", "gallery"
    );

    private final List<KnowledgeEntry> entries;

    public PortfolioKnowledgeService(ObjectMapper objectMapper) {
        this.entries = loadKnowledge(objectMapper);
    }

    public String buildContext(String query, int topK) {
        if (entries.isEmpty()) {
            return "No portfolio knowledge is available.";
        }

        Set<String> queryTokens = tokenize(query);
        int effectiveTopK = Math.max(topK, 1);

        if (isOverviewQuery(query, queryTokens)) {
            List<KnowledgeEntry> overviewEntries = entries.stream()
                .filter(entry -> entry.id != null && OVERVIEW_ENTRY_IDS.contains(entry.id))
                .limit(effectiveTopK)
                .collect(Collectors.toList());
            if (!overviewEntries.isEmpty()) {
                return formatEntries(overviewEntries);
            }
        }

        List<ScoredEntry> ranked = entries.stream()
            .map(entry -> new ScoredEntry(entry, scoreEntry(entry, query, queryTokens)))
            .sorted(Comparator.comparingInt(ScoredEntry::score).reversed())
            .collect(Collectors.toList());

        List<KnowledgeEntry> selected = ranked.stream()
            .filter(se -> se.score() > 0)
            .limit(effectiveTopK)
            .map(ScoredEntry::entry)
            .collect(Collectors.toList());

        if (selected.isEmpty()) {
            selected = entries.stream().limit(effectiveTopK).collect(Collectors.toList());
        }

        return formatEntries(selected);
    }

    public boolean isPortfolioQuery(String query) {
        Set<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) {
            return false;
        }
        for (String token : queryTokens) {
            if (PORTFOLIO_HINT_TOKENS.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private boolean isOverviewQuery(String query, Set<String> queryTokens) {
        String queryLower = safeLower(query);
        if (queryLower.contains("about yogesh") || queryLower.contains("about you")) {
            return true;
        }
        return queryTokens.contains("yogesh")
            || queryTokens.contains("profile")
            || queryTokens.contains("introduction")
            || queryTokens.contains("intro")
            || queryTokens.contains("background");
    }

    private String formatEntries(List<KnowledgeEntry> selected) {
        StringBuilder builder = new StringBuilder();
        for (KnowledgeEntry entry : selected) {
            builder.append("[").append(entry.title).append("]\n")
                .append(entry.content)
                .append("\n\n");
        }
        return builder.toString().trim();
    }

    private int scoreEntry(KnowledgeEntry entry, String query, Set<String> queryTokens) {
        int score = 0;

        String titleLower = safeLower(entry.title);
        String contentLower = safeLower(entry.content);
        String queryLower = safeLower(query);

        for (String token : queryTokens) {
            if (titleLower.contains(token)) {
                score += 5;
            }
            if (contentLower.contains(token)) {
                score += 2;
            }
            if (entry.tags != null && entry.tags.stream().map(this::safeLower).anyMatch(tag -> tag.contains(token))) {
                score += 4;
            }
        }

        if (!queryLower.isEmpty()) {
            if (titleLower.contains(queryLower)) {
                score += 10;
            }
            if (contentLower.contains(queryLower)) {
                score += 6;
            }
        }

        return score;
    }

    private Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptySet();
        }
        return Arrays.stream(TOKEN_SPLIT.split(text.toLowerCase(Locale.ROOT)))
            .map(String::trim)
            .filter(token -> token.length() > 1 && !STOP_WORDS.contains(token))
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private List<KnowledgeEntry> loadKnowledge(ObjectMapper objectMapper) {
        try (InputStream stream = new ClassPathResource("portfolio-knowledge.json").getInputStream()) {
            KnowledgeDocument document = objectMapper.readValue(stream, KnowledgeDocument.class);
            if (document.entries == null) {
                return Collections.emptyList();
            }
            return document.entries.stream()
                .filter(entry -> entry != null && entry.title != null && entry.content != null)
                .collect(Collectors.toList());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load portfolio-knowledge.json", e);
        }
    }

    private record ScoredEntry(KnowledgeEntry entry, int score) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class KnowledgeDocument {
        public List<KnowledgeEntry> entries;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class KnowledgeEntry {
        public String id;
        public String title;
        public List<String> tags;
        public String content;
    }
}
