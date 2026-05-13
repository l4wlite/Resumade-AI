package com.resumade.ai.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.resumade.ai.dto.AtsReport;
import com.resumade.ai.entity.AiRequest;
import com.resumade.ai.repository.AiRequestRepository;

import jakarta.servlet.http.HttpServletRequest;
import reactor.core.publisher.Flux;

@Service
public class AiServiceImpl implements AiService {

    private static final Logger log = LoggerFactory.getLogger(AiServiceImpl.class);

    private final AiRequestRepository repository;
    private final WebClient.Builder webClientBuilder;
    private final HttpServletRequest currentRequest;
    private final ObjectMapper objectMapper;

    public AiServiceImpl(AiRequestRepository repository, WebClient.Builder webClientBuilder,
            HttpServletRequest currentRequest, ObjectMapper objectMapper) {
        this.repository = repository;
        this.webClientBuilder = webClientBuilder;
        this.currentRequest = currentRequest;
        this.objectMapper = objectMapper;
    }

    private void deductCredits(Integer userId, Integer amount) {
        // Credit system disabled
        log.info("Skipping credit deduction for user {} (System Disabled)", userId);
    }

    private void enforcePremium(Integer userId, String feature) {
        // Premium enforcement disabled
        log.info("Skipping premium check for feature: {} (System Disabled)", feature);
    }

    @Value("${ai.gemini.api-key}")
    private String geminiKey;

    @Value("${ai.gemini.model:gemini-2.0-flash}")
    private String geminiModel;

    @Value("${ai.gemini.fallback-model:}")
    private String geminiFallbackModel;

    @Value("${ai.gemini.base-url:https://generativelanguage.googleapis.com/v1}")
    private String geminiBaseUrl;

    @Override
    public String generateSummary(Integer userId, Integer resumeId, String jobTitle, int yearsExp) {
        deductCredits(userId, 5);
        String prompt = String.format(
                "Generate 3 diverse, short, impactful professional summary options (max 3-4 lines each) for a %s with %d years of experience. " +
                "Return ONLY a valid JSON object (no markdown, no explanation) with exactly this structure: " +
                "{\"options\": [\"Option 1 text\", \"Option 2 text\", \"Option 3 text\"]}",
                jobTitle, yearsExp);
        String aiResponse = callAiWithFailover(userId, resumeId, AiRequest.RequestType.SUMMARY, prompt);
        return cleanJsonResponse(aiResponse);
    }

    @Override
    public String generateBulletPoints(Integer userId, Integer resumeId, String jobRole, String company) {
        deductCredits(userId, 5);
        String prompt = String.format(
                "Generate 4-5 high-impact bullet points for a %s role at %s, focusing on achievements and quantifiable metrics.",
                jobRole, company);
        return callAiWithFailover(userId, resumeId, AiRequest.RequestType.BULLETS, prompt);
    }

    @Override
    public AtsReport checkAtsCompatibility(Integer userId, Integer resumeId, String resumeContent,
            String jobDescription) {
        deductCredits(userId, 5);
                String structuredResumeText = resumeContent == null ? "" : resumeContent;
                String safeJobDescription = jobDescription == null ? "" : jobDescription;
                String prompt = String.format("""
                                You are a professional ATS (Applicant Tracking System) scoring engine.
                                Evaluate the resume below against the job description provided.

                                Use this EXACT scoring rubric - score each category out of the points shown:
                                - Keyword Match (35 pts): What percent of the job description's required skills and
                                    technologies appear in the resume? 35 * match_rate = keyword score.
                                - Experience Relevance (25 pts): How directly does the candidate's experience
                                    match the role's requirements and seniority level?
                                - Quantified Achievements (20 pts): Does the resume use numbers, percentages,
                                    and measurable outcomes in bullets?
                                - Format & Readability (10 pts): Are sections clearly labelled, bullets concise,
                                    no tables or columns that break parsing?
                                - Summary Alignment (10 pts): Does the summary directly reference the target role
                                    or its core requirements?

                                Total score = sum of all categories (0-100).

                                IMPORTANT: Keep your response compact. Use short keyword strings.
                                Return ONLY a valid, complete JSON object. No markdown, no explanation, no extra text.
                                {
                                    "score": <total 0-100>,
                                    "breakdown": {
                                        "keywordMatch": { "score": <0-35>, "maxScore": 35, "matchRate": <0.0-1.0> },
                                        "experienceRelevance": { "score": <0-25>, "maxScore": 25 },
                                        "quantifiedAchievements": { "score": <0-20>, "maxScore": 20 },
                                        "formatReadability": { "score": <0-10>, "maxScore": 10 },
                                        "summaryAlignment": { "score": <0-10>, "maxScore": 10 }
                                    },
                                    "keywordsFound": ["keyword1", "keyword2"],
                                    "keywordsMissing": ["keyword3", "keyword4"],
                                    "suggestions": [
                                        { "priority": "HIGH", "category": "Keywords", "action": "Specific actionable change" },
                                        { "priority": "MEDIUM", "category": "Experience", "action": "Specific actionable change" },
                                        { "priority": "LOW", "category": "Format", "action": "Specific actionable change" }
                                    ],
                                    "verdict": "One sentence overall assessment"
                                }

                                JOB DESCRIPTION:
                                %s

                                RESUME (structured by section):
                                %s
                                """, safeJobDescription, structuredResumeText);

        int maxRetries = 2;
        Exception lastException = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                String aiResponse = callAiWithFailover(userId, resumeId, AiRequest.RequestType.ATS, prompt);
                log.debug("ATS raw AI response (attempt {}): {}", attempt, aiResponse);

                String cleaned = cleanJsonResponse(aiResponse);
                log.debug("ATS cleaned JSON: {}", cleaned);
                return objectMapper.readValue(cleaned, AtsReport.class);
            } catch (Exception e) {
                lastException = e;
                log.warn("ATS Check attempt {} failed: {}", attempt, e.getMessage());
                if (attempt < maxRetries) {
                    log.info("Retrying ATS check (attempt {}/{})", attempt + 1, maxRetries);
                }
            }
        }

        log.error("ATS Check failed after {} retries: {}", maxRetries + 1, lastException != null ? lastException.getMessage() : "unknown");
        AtsReport.ScoreBreakdown breakdown = new AtsReport.ScoreBreakdown(
            new AtsReport.CategoryScore(0, 35, 0.0),
            new AtsReport.CategoryScore(0, 25, null),
            new AtsReport.CategoryScore(0, 20, null),
            new AtsReport.CategoryScore(0, 10, null),
            new AtsReport.CategoryScore(0, 10, null));
        return AtsReport.builder()
                .score(0)
            .breakdown(breakdown)
            .keywordsFound(new ArrayList<>())
            .keywordsMissing(new ArrayList<>())
            .suggestions(List.of(new AtsReport.SuggestedAction(
                "LOW",
                "System",
                "AI service error. Please try again later.")))
            .verdict("AI service error. Please try again later.")
                .build();
    }

    @Override
    public List<String> suggestSkills(String jobTitle) {
        String prompt = String.format("Suggest the top 10 most important technical skills for a %s. Return only a comma-separated list of skills.", jobTitle);
        try {
            String aiResponse = callGemini(prompt, AiRequest.RequestType.SKILLS);
            return List.of(aiResponse.split(","));
        } catch (Exception e) {
            log.warn("Failed to suggest skills with AI: {}", e.getMessage());
            return List.of("Communication", "Teamwork", "Problem Solving", "Adaptability");
        }
    }

    @Override
    public String generateCoverLetter(Integer userId, Integer resumeId, String jobDescription) {
        enforcePremium(userId, "Cover Letter Generation");
        deductCredits(userId, 5);
        String prompt = "Generate a personalized cover letter based on this job description: " + jobDescription;
        return callAiWithFailover(userId, resumeId, AiRequest.RequestType.COVER_LETTER, prompt);
    }

    @Override
    public String improveSection(Integer userId, String sectionContent, String tone) {
        enforcePremium(userId, "Section Improvement");
        deductCredits(userId, 5);
        String prompt = String.format(
                "Rewrite the following resume section to sound more %s: \"%s\". " +
                "Provide 3 diverse, high-impact, and professional versions. " +
                "Return ONLY a valid JSON object (no markdown, no explanation) with exactly this structure: " +
                "{\"options\": [\"Option 1 text\", \"Option 2 text\", \"Option 3 text\"]}",
                tone, sectionContent);
        String aiResponse = callAiWithFailover(userId, null, AiRequest.RequestType.IMPROVE, prompt);
        return cleanJsonResponse(aiResponse);
    }

    @Override
    public String tailorResumeForJob(Integer userId, Integer resumeId, String resumeContent, String jobDescription) {
        enforcePremium(userId, "Resume Tailoring");
        deductCredits(userId, 5);
        String prompt = String.format("""
                You are an expert ATS-optimized resume writer. Your task is to tailor a resume for a specific job description.

                You will be given:
                1. The candidate's CURRENT RESUME DATA (as JSON)
                2. The TARGET JOB DESCRIPTION

                Your output must be a VALID JSON object only — no markdown, no explanation, no preamble, no backticks.

                Return exactly this structure:
                {
                                    "matchScore": <integer 0-100 — how well the tailored resume now matches the JD>,
                                    "matchExplanation": "One sentence on why this score was given",
                  "summary": "Rewritten professional summary tailored to the job (2-3 sentences max)",
                  "skills": ["skill1", "skill2", "skill3"],
                  "experience": [
                    {
                      "company": "Exact company name from resume",
                      "title": "Job title (update if needed to better match JD)",
                      "bullets": [
                        "Rewritten bullet point 1 — quantified, action verb, relevant to JD",
                        "Rewritten bullet point 2",
                        "Rewritten bullet point 3"
                      ]
                    }
                  ],
                  "changes_made": [
                    "Short description of change 1",
                    "Short description of change 2"
                  ]
                }

                Rules:
                - ONLY use information already present in the resume. Do NOT invent companies, degrees, or technologies not mentioned.
                - Do NOT use placeholder text like [Your Name] or [X years].
                - Every bullet must start with a strong action verb.
                - Prioritize keywords from the job description naturally.
                - Keep bullets concise: max 20 words each.
                - Return ONLY the JSON. Nothing else.

                CURRENT RESUME:
                %s

                JOB DESCRIPTION:
                %s
                """, resumeContent, jobDescription);
        return callAiWithFailover(userId, resumeId, AiRequest.RequestType.TAILOR, prompt);
    }

    @Override
    public String translateResume(Integer userId, Integer resumeId, String targetLanguage) {
        enforcePremium(userId, "Resume Translation");
        deductCredits(userId, 5);
        String prompt = "Translate this resume content into " + targetLanguage;
        return callAiWithFailover(userId, resumeId, AiRequest.RequestType.TRANSLATE, prompt);
    }

    @Override
    public String testPrompt(String prompt) {
        return callGemini(prompt, null);
    }

    @Override
    public Flux<String> streamAiResponse(Integer userId, String prompt, String requestType) {
        // Gemini does not support true streaming in the current integration.
        // Return the response as a single Flux item.
        String response = callAiWithFailover(userId, null, AiRequest.RequestType.valueOf(requestType), prompt);
        return Flux.just(response);
    }




    private String callAiWithFailover(Integer userId, Integer resumeId, AiRequest.RequestType type, String prompt) {
        AiRequest request = AiRequest.builder()
                .userId(userId)
                .resumeId(resumeId)
                .requestType(type)
                .inputPrompt(prompt)
                .status(AiRequest.RequestStatus.QUEUED)
                .model(AiRequest.ModelType.GEMINI)
                .build();
        repository.save(request);

        try {
            String response = callGemini(prompt, type);
            updateRequest(request, response, AiRequest.ModelType.GEMINI, AiRequest.RequestStatus.COMPLETED);
            return response;
        } catch (Exception e) {
            log.error("Gemini API call failed: {}", e.getMessage());
            updateRequest(request, null, AiRequest.ModelType.GEMINI, AiRequest.RequestStatus.FAILED);
            throw new RuntimeException("AI service error: " + e.getMessage());
        }
    }

        private String callGemini(String prompt, AiRequest.RequestType type) {
        if (geminiKey == null || geminiKey.isEmpty() || geminiKey.contains("your_gemini_key")) {
            return "Gemini API key is not configured. Please check your environment variables.";
        }

        double temperature = getTemperatureForType(type);
        String promptText = prompt == null ? "" : prompt;
        try {
            return callGeminiWithModel(geminiModel, promptText, temperature);
        } catch (WebClientResponseException e) {
            if (shouldUseFallback(e) && isFallbackConfigured()) {
                String fallbackModel = geminiFallbackModel.trim();
                log.warn("Primary Gemini model failed ({}). Falling back to {}.", e.getStatusCode(), fallbackModel);
                try {
                    return callGeminiWithModel(fallbackModel, promptText, temperature);
                } catch (Exception fallbackError) {
                    log.error("Gemini fallback call failed: {}", fallbackError.getMessage());
                }
            }
            log.error("Error calling Gemini API: {}", e.getMessage());
            throw new RuntimeException("Gemini API call failed: " + e.getMessage());
        } catch (Exception e) {
            log.error("Error calling Gemini API: {}", e.getMessage());
            throw new RuntimeException("Gemini API call failed: " + e.getMessage());
        }
    }

    private String callGeminiWithModel(String model, String promptText, double temperature) {
        String url = geminiBaseUrl + "/models/" + model + ":generateContent?key=" + geminiKey;

        Map<String, Object> body = Map.of(
                "contents", List.of(
                        Map.of("parts", List.of(
                                Map.of("text", promptText)))),
                "generationConfig", Map.of(
                        "temperature", temperature,
                        "topP", 0.8,
                        "maxOutputTokens", 8192));

        Map response = webClientBuilder.build()
                .post()
                .uri(url)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        if (response != null && response.containsKey("candidates")) {
            List candidates = (List) response.get("candidates");
            if (!candidates.isEmpty()) {
                Map candidate = (Map) candidates.get(0);
                Map content = (Map) candidate.get("content");
                List parts = (List) content.get("parts");
                if (!parts.isEmpty()) {
                    Map part = (Map) parts.get(0);
                    return (String) part.get("text");
                }
            }
        }
        return "No content received from Gemini.";
    }

    private boolean shouldUseFallback(WebClientResponseException e) {
        int status = e.getStatusCode().value();
        return status == 429 || status == 503 || status == 504;
    }

    private boolean isFallbackConfigured() {
        return geminiFallbackModel != null
                && !geminiFallbackModel.isBlank()
                && !geminiFallbackModel.trim().equalsIgnoreCase(geminiModel);
    }

    private double getTemperatureForType(AiRequest.RequestType type) {
        if (type == null) {
            return 0.3;
        }
        return switch (type) {
            case ATS, TAILOR -> 0.2;
            case BULLETS, COVER_LETTER -> 0.7;
            case SUMMARY, IMPROVE, TRANSLATE, SKILLS -> 0.3;
        };
    }

    private String cleanJsonResponse(String response) {
        if (response == null) return "{}";

        String cleaned = response.replaceAll("(?s)```(?:json)?(.*?)```", "$1").trim();
        String extracted = extractFirstJson(cleaned);
        if (extracted == null || extracted.isBlank()) {
            return "{}";
        }

        String normalized = stripTrailingCommas(extracted);
        String sanitized = sanitizeTruncatedJson(normalized);
        return balanceJson(sanitized);
    }

    /**
     * Removes incomplete trailing content from truncated JSON.
     * This handles cases where the AI response was cut off mid-field-name,
     * mid-string-value, or mid-number, leaving invalid JSON fragments.
     * E.g. {"breakdown":{"experienceRelev  →  {"breakdown":{}
     */
    private String sanitizeTruncatedJson(String text) {
        if (text == null || text.isEmpty()) return text;

        // Check if the JSON is already balanced (extractBalancedJson would have returned it)
        String balanced = extractBalancedJson(text);
        if (balanced != null) {
            return balanced; // Already well-formed
        }

        // The JSON is truncated. Walk backwards removing incomplete tokens.
        StringBuilder sb = new StringBuilder(text);

        // Remove trailing whitespace
        while (sb.length() > 0 && Character.isWhitespace(sb.charAt(sb.length() - 1))) {
            sb.deleteCharAt(sb.length() - 1);
        }

        if (sb.length() == 0) return "{}";

        // If we end inside a string (odd number of unescaped quotes), close it
        // But simpler: trim back to the last complete key-value pair
        // Strategy: remove characters from the end until we're at a valid boundary
        // Valid boundaries: after a closing brace/bracket, after a quoted string, after a number, after true/false/null
        while (sb.length() > 1) {
            char last = sb.charAt(sb.length() - 1);

            // If the last char is a valid JSON structural ending, we're good
            if (last == '}' || last == ']' || last == '"') {
                break;
            }
            // If it's a digit, check if we're at the end of a number value (peek before)
            if (Character.isDigit(last) || last == '.') {
                // Walk back to see if this is a complete number after a colon
                int numStart = sb.length() - 1;
                while (numStart > 0 && (Character.isDigit(sb.charAt(numStart - 1)) || sb.charAt(numStart - 1) == '.' || sb.charAt(numStart - 1) == '-')) {
                    numStart--;
                }
                // Check what's before the number
                int beforeNum = numStart - 1;
                while (beforeNum >= 0 && Character.isWhitespace(sb.charAt(beforeNum))) {
                    beforeNum--;
                }
                if (beforeNum >= 0 && (sb.charAt(beforeNum) == ':' || sb.charAt(beforeNum) == ',' || sb.charAt(beforeNum) == '[')) {
                    break; // Complete number value
                }
                // Otherwise it's a partial number; trim it
                sb.setLength(numStart);
                continue;
            }
            // If it's e/E (scientific notation part), a letter in true/false/null, or anything else partial
            // Remove it
            sb.deleteCharAt(sb.length() - 1);
        }

        // Now trim trailing commas and whitespace
        while (sb.length() > 1) {
            char last = sb.charAt(sb.length() - 1);
            if (last == ',' || Character.isWhitespace(last)) {
                sb.deleteCharAt(sb.length() - 1);
            } else {
                break;
            }
        }

        // If we end with an orphan colon (truncated after key name before value), remove the key too
        String current = sb.toString().trim();
        // Remove trailing pattern like: , "someKey": or "someKey":
        current = current.replaceAll(",?\\s*\"[^\"]*\"\\s*:\\s*$", "");
        // Remove trailing incomplete key (no closing quote)
        current = current.replaceAll(",?\\s*\"[^\"]*$", "");

        if (current.isEmpty()) return "{}";

        return current;
    }

    private String extractFirstJson(String text) {
        if (text == null) return null;
        int startObj = text.indexOf('{');
        int startArr = text.indexOf('[');
        int start;
        if (startObj == -1 && startArr == -1) return null;
        if (startObj == -1) {
            start = startArr;
        } else if (startArr == -1) {
            start = startObj;
        } else {
            start = Math.min(startObj, startArr);
        }

        String slice = text.substring(start);
        String extracted = extractBalancedJson(slice);
        return extracted != null ? extracted : slice;
    }

    private String extractBalancedJson(String text) {
        if (text == null || text.isEmpty()) return null;
        char first = text.charAt(0);
        if (first != '{' && first != '[') return null;

        List<Character> stack = new ArrayList<>();
        boolean inString = false;
        boolean escaping = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaping) {
                    escaping = false;
                    continue;
                }
                if (c == '\\') {
                    escaping = true;
                    continue;
                }
                if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (c == '"') {
                inString = true;
                continue;
            }

            if (c == '{' || c == '[') {
                stack.add(c);
            } else if (c == '}' || c == ']') {
                if (!stack.isEmpty()) {
                    char top = stack.get(stack.size() - 1);
                    if ((c == '}' && top == '{') || (c == ']' && top == '[')) {
                        stack.remove(stack.size() - 1);
                        if (stack.isEmpty()) {
                            return text.substring(0, i + 1);
                        }
                    }
                }
            }
        }

        return null;
    }

    private String balanceJson(String text) {
        if (text == null || text.isEmpty()) return "{}";

        StringBuilder builder = new StringBuilder(text);
        List<Character> stack = new ArrayList<>();
        boolean inString = false;
        boolean escaping = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaping) {
                    escaping = false;
                    continue;
                }
                if (c == '\\') {
                    escaping = true;
                    continue;
                }
                if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (c == '"') {
                inString = true;
                continue;
            }

            if (c == '{' || c == '[') {
                stack.add(c);
            } else if (c == '}' || c == ']') {
                if (!stack.isEmpty()) {
                    char top = stack.get(stack.size() - 1);
                    if ((c == '}' && top == '{') || (c == ']' && top == '[')) {
                        stack.remove(stack.size() - 1);
                    }
                }
            }
        }

        for (int i = stack.size() - 1; i >= 0; i--) {
            char open = stack.get(i);
            builder.append(open == '{' ? '}' : ']');
        }

        return builder.toString();
    }

    private String stripTrailingCommas(String text) {
        if (text == null || text.isEmpty()) return text;

        StringBuilder builder = new StringBuilder();
        boolean inString = false;
        boolean escaping = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                builder.append(c);
                if (escaping) {
                    escaping = false;
                } else if (c == '\\') {
                    escaping = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (c == '"') {
                inString = true;
                builder.append(c);
                continue;
            }

            if (c == ',') {
                int j = i + 1;
                while (j < text.length() && Character.isWhitespace(text.charAt(j))) {
                    j++;
                }
                if (j < text.length()) {
                    char next = text.charAt(j);
                    if (next == '}' || next == ']') {
                        continue;
                    }
                }
            }

            builder.append(c);
        }

        return builder.toString();
    }

    @Override
    public List<AiRequest> getUserHistory(Integer userId) {
        if (userId == null) return List.of();
        return repository.findByUserId(userId);
    }

    private void updateRequest(AiRequest request, String response, AiRequest.ModelType model,
            AiRequest.RequestStatus status) {
        request.setAiResponse(response);
        request.setModel(model);
        request.setStatus(status);
        request.setTokensUsed(100); // Dummy count
        request.setCompletedAt(LocalDateTime.now());
        repository.save(request);
    }
}
