package com.resumade.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.resumade.auth.dto.NotificationEvent;
import com.resumade.notification.entity.Notification;
import com.resumade.notification.service.NotificationService;

@Service
public class NotificationProducer {

    private static final Logger log = LoggerFactory.getLogger(NotificationProducer.class);

    private final NotificationService notificationService;

    public NotificationProducer(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    public void sendNotification(NotificationEvent event) {
        log.info("Creating notification event: {}", event.getTitle());

        try {
            Notification.NotificationType type = Notification.NotificationType.valueOf(event.getType().toUpperCase());
            Notification.NotificationChannel channel = Notification.NotificationChannel.valueOf(event.getChannel().toUpperCase());

            notificationService.createNotification(
                    event.getUserId(),
                    event.getRecipientEmail(),
                    type,
                    event.getTitle(),
                    event.getMessage(),
                    channel
            );
        } catch (Exception e) {
            log.error("Error processing notification event: {}. Using default values.", e.getMessage());
            notificationService.createNotification(
                    event.getUserId(),
                    null,
                    Notification.NotificationType.SYSTEM,
                    event.getTitle(),
                    event.getMessage(),
                    Notification.NotificationChannel.IN_APP
            );
        }
    }
}
