package com.resumade.jobmatch.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resumade.jobmatch.entity.JobMatch;
import com.resumade.jobmatch.repository.JobMatchRepository;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;

@Service
public class JobMatchServiceImpl implements JobMatchService {

    private static final Logger log = LoggerFactory.getLogger(JobMatchServiceImpl.class);

    private final JobMatchRepository repository;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    @Value("${jooble.api-key}")
    private String joobleApiKey;

    @Value("${ai.gemini.api-key}")
    private String geminiKey;

    @Value("${services.resume-url:http://localhost:9090}")
    private String resumeServiceUrl;

    @Value("${ai.gemini.model:gemini-2.5-flash}")
    private String geminiModel;

    public JobMatchServiceImpl(JobMatchRepository repository,
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.webClientBuilder = webClientBuilder;
        this.objectMapper = objectMapper;
    }

    @Override
    @CircuitBreaker(name = "jooble", fallbackMethod = "fetchJobsFallback")
    public List<JobMatch> fetchJobsFromLinkedIn(Integer userId, String title, String location) {
        return fetchJobsFromJooble(userId, title, location, 1);
    }

    @Override
    @CircuitBreaker(name = "jooble", fallbackMethod = "fetchJobsFallback")
    public List<JobMatch> fetchJobsFromNaukri(Integer userId, String title, String location) {
        return fetchJobsFromJooble(userId, title, location, 1);
    }

    @Override
    public List<JobMatch> searchJobs(Integer userId, String title, String location, String country, Integer page) {
        return fetchJobsFromJooble(userId, title, location, page != null ? page : 1);
    }

    public List<JobMatch> fetchJobsFromJooble(Integer userId, String title, String location, int page) {
        log.info("Fetching jobs from Jooble for {}: {} in {} (page {})", userId, title, location, page);

        String url = "https://jooble.org/api/" + joobleApiKey;

        Map<String, Object> body = Map.of(
                "keywords", title != null ? title : "",
                "location", location != null ? location : "",
                "page", String.valueOf(page));

        try {
            Map response = webClientBuilder.build()
                    .post()
                    .uri(url)
                    .header("Content-Type", "application/json")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            List<JobMatch> jobs = new ArrayList<>();
            if (response != null && response.containsKey("jobs")) {
                List<Map> results = (List<Map>) response.get("jobs");
                for (Map result : results) {
                    JobMatch job = new JobMatch();
                    job.setUserId(userId);
                    job.setJobTitle((String) result.get("title"));
                    job.setCompany((String) result.get("company"));
                    job.setLocation((String) result.get("location"));

                    // Clean snippet: remove &nbsp;, leading/trailing dots/spaces
                    String snippet = (String) result.get("snippet");
                    if (snippet != null) {
                        snippet = snippet.replaceAll("&nbsp;", " ")
                                .replaceAll("(?i)<br\\s*/?>", "\n")
                                .replaceAll("^\\s*[\\s\\.*\\&nbsp;]+", "")
                                .replaceAll("[\\s\\.*\\&nbsp;]+\\s*$", "")
                                .trim();
                    }
                    job.setJobDescription(snippet);

                    job.setApplyUrl((String) result.get("link"));
                    job.setSource(JobMatch.JobSource.JOOBLE);
                    jobs.add(job);
                }
            }
            return jobs.isEmpty() ? jobs : repository.saveAll(jobs);
        } catch (Exception e) {
            log.error("Error fetching jobs from Jooble: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    @Transactional
    public JobMatch analyzeJobFit(Integer userId, Integer resumeId, Long matchId, String authToken) {
        JobMatch job = repository.findById(matchId)
                .orElseThrow(() -> new RuntimeException("Job not found with id: " + matchId));

        log.info("Starting AI Analysis for Job: {} and Resume: {}", job.getJobTitle(), resumeId);

        // Step 0: Deduct Credits (5 credits per AI analysis)
        deductCredits(userId, 5);

        // Step 1: Fetch resume content
        String resumeContent = fetchResumeContent(resumeId, authToken);
        String jobDescription = job.getJobDescription();

        if (jobDescription == null || jobDescription.isBlank()) {
            jobDescription = "No job description provided.";
            log.warn("Job description is empty for job id: {}", matchId);
        }

        // Step 2: Build a rich analysis prompt
        String prompt = String.format(
                """
                        You are an expert technical recruiter and ATS (Applicant Tracking System) optimizer.
                        Your task is to conduct a detailed compatibility analysis between a JOB DESCRIPTION and a CANDIDATE'S RESUME.

                        --- JOB DETAILS ---
                        TITLE: %s
                        COMPANY: %s
                        DESCRIPTION:
                        %s

                        --- CANDIDATE RESUME ---
                        %s

                        --- INSTRUCTIONS ---
                        1. Critically compare the skills, experience, and qualifications.
                        2. Calculate an honest compatibility score (0-100%%).
                        3. Identify specific strengths where the candidate exceeds or meets requirements.
                        4. Identify critical gaps or weaknesses.
                        5. Provide actionable recommendations to improve the resume for this specific role.

                        Return ONLY a valid JSON object with these exact fields:
                        {
                          "score": number,
                          "strengths": "bulleted string",
                          "weaknesses": "bulleted string",
                          "recommendations": "bulleted string"
                        }
                        """,
                job.getJobTitle(), job.getCompany(), jobDescription, resumeContent);

        try {
            log.debug("Sending analysis prompt to Gemini...");
            String aiResponse = callGemini(prompt);
            log.debug("Job analysis raw AI response: {}", aiResponse);

            // Extract JSON from potential markdown wrapping
            String cleaned = aiResponse.replaceAll("(?s)```(?:json)?(.*?)```", "$1").trim();
            if (cleaned.contains("{")) {
                int start = cleaned.indexOf('{');
                int end = cleaned.lastIndexOf('}');
                if (end > start) {
                    cleaned = cleaned.substring(start, end + 1);
                }
            }

            JsonNode result = objectMapper.readTree(cleaned);

            job.setMatchScore(result.has("score") ? result.get("score").asInt() : 0);
            job.setStrengths(
                    result.has("strengths") ? result.get("strengths").asText() : "No specific strengths identified.");
            job.setWeaknesses(
                    result.has("weaknesses") ? result.get("weaknesses").asText() : "No critical gaps identified.");
            job.setRecommendations(result.has("recommendations") ? result.get("recommendations").asText()
                    : "No recommendations at this time.");
            job.setResumeId(resumeId);

            log.info("AI Analysis completed successfully for job id: {}. Score: {}", matchId, job.getMatchScore());
        } catch (Exception e) {
            log.error("AI Analysis failed for job {}: {}", matchId, e.getMessage(), e);
            job.setMatchScore(0);
            job.setRecommendations(
                    "The AI analysis encountered an error. Please ensure your resume is fully populated and try again.");
            job.setStrengths("Analysis unavailable.");
            job.setWeaknesses("Analysis unavailable.");
        }

        return repository.save(job);
    }

    private void deductCredits(Integer userId, Integer amount) {
        // Credit system disabled
        log.info("Skipping credit deduction of {} for user {} (System Disabled)", amount, userId);
    }

    private String fetchResumeContent(Integer resumeId, String authToken) {
        try {
                log.info("Fetching resume content for resumeId: {} from resume endpoint", resumeId);
                Map resumeData = webClientBuilder.build()
                    .get()
                    .uri(resumeServiceUrl + "/api/v1/resumes/" + resumeId)
                    .header("Authorization", "Bearer " + authToken)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (resumeData == null) {
                log.warn("Resume service returned null for resumeId: {}", resumeId);
                return "No resume content available.";
            }

            // Build a plain-text representation of the resume from the sections map
            StringBuilder sb = new StringBuilder();
            Object title = resumeData.get("title");
            if (title != null)
                sb.append("Resume: ").append(title).append("\n");

            Object targetJobTitle = resumeData.get("targetJobTitle");
            if (targetJobTitle != null)
                sb.append("Target Role: ").append(targetJobTitle).append("\n");

            sb.append("\n");

            Object sections = resumeData.get("sections");
            if (sections instanceof List) {
                for (Object s : (List) sections) {
                    if (s instanceof Map) {
                        Map section = (Map) s;
                        String sectionType = String.valueOf(section.getOrDefault("sectionType", "SECTION"));
                        String sectionTitle = section.get("title") != null ? ": " + section.get("title") : "";
                        sb.append("## ").append(sectionType).append(sectionTitle).append("\n");

                        Object content = section.get("content");
                        if (content != null) {
                            String contentStr = content.toString();
                            // Try to pretty-format JSON content for better AI understanding
                            try {
                                Object parsed = objectMapper.readValue(contentStr, Object.class);
                                sb.append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(parsed));
                            } catch (Exception e) {
                                sb.append(contentStr);
                            }
                        }
                        sb.append("\n\n");
                    }
                }
            }

            String result = sb.toString().trim();
            if (result.isBlank()) {
                log.warn("Resume {} had no parseable content", resumeId);
                return "No resume content found.";
            }

            log.info("Successfully fetched resume content for resumeId: {} ({} chars)", resumeId, result.length());
            return result;
        } catch (Exception e) {
            log.error("Could not fetch resume {}: {}", resumeId, e.getMessage(), e);
            return "Could not retrieve resume content.";
        }
    }

    private String callGemini(String prompt) {
        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + geminiModel + ":generateContent?key="
                + geminiKey;
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of(
                        "temperature", 0.2,
                        "maxOutputTokens", 8192));

        try {
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
                    return (String) ((Map) parts.get(0)).get("text");
                }
            }
            return "{}";
        } catch (Exception e) {
            log.error("Gemini call failed: {}", e.getMessage());
            return "{}";
        }
    }

    @Override
    @Transactional
    public void toggleBookmark(Long matchId) {
        repository.findById(matchId).ifPresent(job -> {
            job.setBookmarked(!job.isBookmarked());
            repository.save(job);
        });
    }

    @Override
    public Map<String, Object> testJooble(String title, String location) {
        log.info("Testing Jooble API connection for: {} in {}", title, location);
        String url = "https://jooble.org/api/" + joobleApiKey;
        Map<String, Object> body = Map.of(
                "keywords", title != null ? title : "Java Developer",
                "location", location != null ? location : "USA");

        try {
            return webClientBuilder.build()
                    .post()
                    .uri(url)
                    .header("Content-Type", "application/json")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
        } catch (Exception e) {
            log.error("Jooble API Test failed: {}", e.getMessage());
            return Map.of("error", e.getMessage(), "status", "failed");
        }
    }

    @Override
    public List<JobMatch> getUserHistory(Integer userId) {
        return repository.findByUserIdOrderByMatchedAtDesc(userId);
    }

    @Override
    public List<JobMatch> getBookmarks(Integer userId) {
        return repository.findByUserIdAndIsBookmarkedTrue(userId);
    }

    public List<JobMatch> fetchJobsFallback(Integer userId, String title, String location, Throwable t) {
        log.warn("Job search fallback triggered for {} due to: {}", title, t.getMessage());
        return new ArrayList<>();
    }
}
