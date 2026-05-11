package com.resumade.notification.service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import com.resumade.notification.entity.Notification;
import com.resumade.notification.repository.NotificationRepository;

import jakarta.mail.internet.MimeMessage;

@Service
public class NotificationServiceImpl implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationServiceImpl.class);

    private final NotificationRepository repository;
    private final JavaMailSender mailSender;
    private final RestTemplate restTemplate;
    private final TemplateEngine templateEngine;

    @Value("${services.auth-url:http://localhost:9090}")
    private String authServiceUrl;

    public NotificationServiceImpl(NotificationRepository repository, JavaMailSender mailSender, RestTemplate restTemplate, TemplateEngine templateEngine) {
        this.repository = repository;
        this.mailSender = mailSender;
        this.restTemplate = restTemplate;
        this.templateEngine = templateEngine;
    }

    @Override
    @Transactional
    public Notification createNotification(Integer userId, String recipientEmail, Notification.NotificationType type, String title, String message, Notification.NotificationChannel channel) {
        Notification notification = new Notification(userId, type, title, message, channel);
        repository.save(notification);

        if (channel == Notification.NotificationChannel.EMAIL || channel == Notification.NotificationChannel.BOTH) {
            if (recipientEmail != null) {
                sendEmailWithTemplate(recipientEmail, title, message, type);
            } else {
                sendEmail(userId, title, message);
            }
        }

        log.info("Notification created for user {}: {}", userId, title);
        return notification;
    }

    @Override
    public List<Notification> getUserNotifications(Integer userId) {
        return repository.findByRecipientIdOrderBySentAtDesc(userId);
    }

    @Override
    public long getUnreadCount(Integer userId) {
        return repository.countByRecipientIdAndIsRead(userId, false);
    }

    @Override
    @Transactional
    public void markAsRead(Long id) {
        repository.findById(id).ifPresent(n -> {
            n.setRead(true);
            repository.save(n);
        });
    }

    @Override
    @Transactional
    public void markAllRead(Integer userId) {
        repository.markAllReadForUser(userId);
    }

    @Override
    @Transactional
    public void broadcastNotification(String title, String message, String recipientType) {
        log.info("Starting broadcast for type: {}", recipientType);
        
        List<Integer> userIds;
        try {
            // Fetch users from auth endpoint
            String token = getAuthToken();
            HttpHeaders headers = new HttpHeaders();
            if (token != null) {
                headers.set("Authorization", "Bearer " + token);
            }
            
            HttpEntity<Void> entity = new HttpEntity<>(headers);
                ResponseEntity<Map[]> response = restTemplate.exchange(
                    authServiceUrl + "/api/v1/auth/admin/users",
                    HttpMethod.GET,
                    entity,
                    Map[].class
            );

            if (response.getBody() == null) {
                log.warn("Auth service returned empty user list");
                return;
            }

            userIds = Arrays.stream(response.getBody())
                    .filter(user -> {
                        if ("ALL".equalsIgnoreCase(recipientType)) return true;
                        String plan = (String) user.get("subscriptionPlan");
                        return recipientType.equalsIgnoreCase(plan);
                    })
                    .map(user -> (Integer) user.get("userId"))
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Failed to fetch users from auth endpoint: {}", e.getMessage());
            // Fallback to existing users in repository as a last resort
            userIds = repository.findAll().stream()
                    .map(Notification::getRecipientId)
                    .distinct()
                    .toList();
        }

        for (Integer userId : userIds) {
            createNotification(userId, null, Notification.NotificationType.SYSTEM, title, message, Notification.NotificationChannel.IN_APP);
        }
        
        log.info("Broadcast notification sent to {} users: {}", userIds.size(), title);
    }

    private String getAuthToken() {
        Object details = SecurityContextHolder.getContext().getAuthentication().getCredentials();
        if (details instanceof String) {
            return (String) details;
        }
        return null;
    }

    private void sendEmail(Integer userId, String title, String message) {
        try {
            log.info("Sending simple email to user {}: {}", userId, title);
            SimpleMailMessage mail = new SimpleMailMessage();
            mail.setTo("user-" + userId + "@example.com"); 
            mail.setSubject(title);
            mail.setText(message);
            // mailSender.send(mail);
        } catch (Exception e) {
            log.error("Failed to send simple email: {}", e.getMessage());
        }
    }

    private void sendEmailWithTemplate(String to, String title, String message, Notification.NotificationType type) {
        Map<String, Object> variables = new java.util.HashMap<>();
        variables.put("title", title);
        variables.put("message", message);
        
        String templateName = "generic-notification";
        
        if (title.contains("Welcome")) {
            templateName = "welcome-email";
        } else if (title.contains("Plan Upgraded")) {
            templateName = "upgrade-email";
        } else if (title.contains("Password Reset")) {
            templateName = "reset-password-email";
        }

        sendHtmlEmail(to, title, templateName, variables);
    }

    private void sendHtmlEmail(String to, String subject, String templateName, Map<String, Object> variables) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            
            Context context = new Context();
            context.setVariables(variables);
            String htmlContent = templateEngine.process(templateName, context);
            
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlContent, true);
            helper.setFrom("Resumade <noreply@resumade.com>");
            
            mailSender.send(message);
            log.info("HTML Email sent to {} using template {}", to, templateName);
        } catch (Exception e) {
            log.warn("Failed to send HTML email to {} (SMTP likely not configured): {}", to, e.getMessage());
        }
    }
}
