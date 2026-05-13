package com.resumade.auth.service;

import java.util.Collections;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.resumade.auth.dto.AuthResponse;
import com.resumade.auth.dto.ChangePasswordRequest;
import com.resumade.auth.dto.GoogleLoginRequest;
import com.resumade.auth.dto.LoginRequest;
import com.resumade.auth.dto.NotificationEvent;
import com.resumade.auth.dto.RegisterRequest;
import com.resumade.auth.dto.ResetPasswordRequest;
import com.resumade.auth.dto.UpdateProfileRequest;
import com.resumade.auth.entity.User;
import com.resumade.auth.exception.EmailAlreadyExistsException;
import com.resumade.auth.exception.InvalidCredentialsException;
import com.resumade.auth.exception.UserNotFoundException;
import com.resumade.auth.repository.UserRepository;
import com.resumade.auth.security.JwtService;

@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final UserDetailsService userDetailsService;
    private final NotificationProducer notificationProducer;

    @Value("${google.oauth.client-id}")
    private String googleClientId;

    public AuthServiceImpl(UserRepository userRepository, PasswordEncoder passwordEncoder,
            JwtService jwtService, AuthenticationManager authenticationManager,
            UserDetailsService userDetailsService, NotificationProducer notificationProducer) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.authenticationManager = authenticationManager;
        this.userDetailsService = userDetailsService;
        this.notificationProducer = notificationProducer;
    }

    @Override
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        log.info("Registering user with email: {}", request.getEmail());

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new EmailAlreadyExistsException("An account with this email already exists");
        }

        if (!request.getPassword().equals(request.getConfirmPassword())) {
            throw new InvalidCredentialsException("Passwords do not match");
        }

        User.Role role = User.Role.USER;
        if (request.getRole() != null && request.getRole().equalsIgnoreCase("ADMIN")) {
            role = User.Role.ADMIN;
        }

        User user = new User(
                request.getFullName(),
                request.getEmail(),
                passwordEncoder.encode(request.getPassword()),
                role,
                User.Provider.LOCAL,
                true,
                User.SubscriptionPlan.FREE);

        user = userRepository.save(user);
        log.info("User registered successfully with id: {}", user.getUserId());

        // Send registration notification
        try {
            notificationProducer.sendNotification(new NotificationEvent(
                    user.getUserId(),
                    user.getEmail(),
                    "SYSTEM",
                    "Welcome to Resumade!",
                    "Thank you for registering. We're excited to have you on board!",
                    "BOTH"));
        } catch (Exception e) {
            log.error("Failed to send welcome notification: {}", e.getMessage());
        }

        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getEmail());
        String token = jwtService.generateToken(userDetails, user);

        return buildAuthResponse(token, user);
    }

    @Override
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        log.info("Login attempt for email: {}", request.getEmail());

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword()));
        } catch (AuthenticationException e) {
            throw new InvalidCredentialsException("Invalid email or password");
        }

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getEmail());
        String token = jwtService.generateToken(userDetails, user);

        log.info("User logged in successfully: {}", user.getUserId());
        return buildAuthResponse(token, user);
    }

    @Override
    @Transactional
    public AuthResponse googleLogin(GoogleLoginRequest request) {
        log.info("Google login attempt with token");

        if (request.getToken() == null || request.getToken().isBlank()) {
            log.warn("Google login attempt with empty token");
            throw new InvalidCredentialsException("Google token is required");
        }

        log.debug("Token length: {} chars", request.getToken().length());
        log.debug("Configured Google Client ID: {}", googleClientId);

        try {
            GoogleIdToken idToken = verifyGoogleToken(request.getToken());
            if (idToken == null) {
                log.warn("Google token verification returned null - token may be invalid or expired");
                throw new InvalidCredentialsException("Invalid Google token");
            }

            GoogleIdToken.Payload payload = idToken.getPayload();
            String email = payload.getEmail();
            String name = (String) payload.get("name");
            if (name == null) {
                name = email; // Use email as fallback if name not provided
            }
            String tokenIssuer = payload.getIssuer();
            Object audienceObj = payload.get("aud");
            String tokenAudience = audienceObj != null ? audienceObj.toString() : "unknown";

            log.debug("Token issuer: {}", tokenIssuer);
            log.debug("Token audience: {}", tokenAudience);
            log.debug("Token verified for email: {}", email);

            if (email == null || email.isBlank()) {
                log.warn("Google token missing email claim");
                throw new InvalidCredentialsException("Google token missing required email claim");
            }

            Optional<User> userOpt = userRepository.findByEmail(email);
            User user;

            if (userOpt.isPresent()) {
                user = userOpt.get();
                log.info("Existing user found for Google login: {}", user.getUserId());
                // Only update provider if user doesn't have one set yet
                // Do NOT switch provider for existing users (security fix)
                if (user.getProvider() == null) {
                    log.info("Setting provider to GOOGLE for existing user: {}", user.getUserId());
                    user.setProvider(User.Provider.GOOGLE);
                    userRepository.save(user);
                }
            } else {
                // Register new user via Google
                log.info("Creating new user via Google with email: {}", email);
                user = new User(
                        name,
                        email,
                        null, // No password for Google users
                        User.Role.USER,
                        User.Provider.GOOGLE,
                        true,
                        User.SubscriptionPlan.FREE);
                user = userRepository.save(user);
                log.info("New user registered via Google: {} with email: {}", user.getUserId(), email);
            }

            UserDetails userDetails = userDetailsService.loadUserByUsername(user.getEmail());
            String jwtToken = jwtService.generateToken(userDetails, user);

            log.info("Google login successful for user: {}", user.getUserId());
            return buildAuthResponse(jwtToken, user);

        } catch (InvalidCredentialsException e) {
            log.error("Google login validation failed: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Google token verification failed with exception: {} - {}",
                    e.getClass().getSimpleName(), e.getMessage(), e);
            log.error("Possible causes: invalid token, expired token, client ID mismatch, network error");
            throw new InvalidCredentialsException("Failed to verify Google token: " + e.getMessage());
        }
    }

    protected GoogleIdToken verifyGoogleToken(String token) throws Exception {
        log.debug("Starting token verification with client ID: {}", googleClientId);

        try {
            GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), new GsonFactory())
                    .setAudience(Collections.singletonList(googleClientId))
                    .build();

            log.debug("Verifier created, attempting to verify token of length: {}", token.length());
            GoogleIdToken idToken = verifier.verify(token);
            log.debug("Token verification result: {}", idToken != null ? "SUCCESS" : "NULL");
            return idToken;
        } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
            log.error("Google API error during token verification - Status: {} - {}",
                    e.getStatusCode(), e.getMessage());
            if (e.getStatusCode() == 400) {
                log.error("Invalid token format or signature");
            }
            throw new Exception("Google token invalid: " + e.getMessage(), e);
        } catch (java.io.IOException e) {
            log.error("Network error during token verification (check internet connectivity): {}", e.getMessage());
            throw new Exception("Cannot reach Google API to verify token. Check backend internet connectivity.", e);
        } catch (Exception e) {
            log.error("Token verification exception - {}: {}", e.getClass().getSimpleName(), e.getMessage());
            throw e;
        }
    }

    @Override
    public void logout(String token) {
    }

    @Override
    @Transactional(readOnly = true)
    public AuthResponse refreshToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new InvalidCredentialsException("Invalid token");
        }

        String oldToken = authHeader.substring(7);
        String email = jwtService.extractUsername(oldToken);
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        UserDetails userDetails = userDetailsService.loadUserByUsername(email);
        if (!jwtService.validateToken(oldToken, userDetails)) {
            throw new InvalidCredentialsException("Invalid or expired token");
        }

        String newToken = jwtService.generateToken(userDetails, user);
        return buildAuthResponse(newToken, user);
    }

    @Override
    @Transactional(readOnly = true)
    public AuthResponse getUserById(Integer userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found with id: " + userId));
        return buildAuthResponse(null, user);
    }

    @Override
    @Transactional
    public AuthResponse updateProfile(Integer userId, UpdateProfileRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found with id: " + userId));

        user.setFullName(request.getFullName());
        if (request.getEmail() != null) {
            user.setEmail(request.getEmail());
        }
        if (request.getPhone() != null) {
            user.setPhone(request.getPhone());
        }

        user = userRepository.save(user);
        log.info("Profile updated for user: {}", userId);
        return buildAuthResponse(null, user);
    }

    @Override
    @Transactional
    public void changePassword(Integer userId, ChangePasswordRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found with id: " + userId));

        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("Current password is incorrect");
        }

        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new InvalidCredentialsException("New passwords do not match");
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
        log.info("Password changed for user: {}", userId);
    }

    @Override
    @Transactional
    public void updateSubscription(Integer userId, String plan) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found with id: " + userId));

        User.SubscriptionPlan newPlan = User.SubscriptionPlan.valueOf(plan.toUpperCase());
        user.setSubscriptionPlan(newPlan);

        // When upgrading to premium, set credits to 1000
        if (newPlan == User.SubscriptionPlan.PREMIUM) {
            user.setCredits(1000);
        }

        userRepository.save(user);
        log.info("Subscription updated to {} for user: {}. Credits reset if applicable.", plan, userId);

        // Send notification
        try {
            notificationProducer.sendNotification(new NotificationEvent(
                    userId,
                    user.getEmail(),
                    "SYSTEM",
                    "Plan Upgraded!",
                    "Your account has been upgraded to " + plan.toUpperCase() + ". You now have " + user.getCredits()
                            + " credits!",
                    "BOTH"));
        } catch (Exception e) {
            log.error("Failed to send plan update notification: {}", e.getMessage());
        }
    }

    @Override
    @Transactional
    public void deductCredits(Integer userId, Integer amount) {
        // Credit system disabled
        log.info("Bypassing credit deduction of {} for user {} (System Disabled)", amount, userId);
    }

    @Override
    @Transactional
    public void deactivateUser(Integer userId) {
        setUserStatus(userId, false);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<User> getAllUsers() {
        return userRepository.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Map<String, Object> getAdminStats() {
        java.util.Map<String, Object> stats = new java.util.HashMap<>();
        stats.put("totalUsers", userRepository.count());
        stats.put("premiumUsers", userRepository.countBySubscriptionPlan(User.SubscriptionPlan.PREMIUM));
        stats.put("activeUsers", userRepository.countByIsActiveTrue());
        return stats;
    }

    @Override
    @Transactional
    public void setUserStatus(Integer userId, boolean active) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found with id: " + userId));

        user.setIsActive(active);
        userRepository.save(user);
        log.info("Status updated for user {}: {}", userId, active);
    }

    @Override
    @Transactional
    public void deleteUser(Integer userId) {
        userRepository.deleteById(userId);
        log.info("User deleted: {}", userId);
    }

    @Override
    @Transactional
    public void forgotPassword(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UserNotFoundException("User not found with email: " + email));

        String token = java.util.UUID.randomUUID().toString();
        user.setResetToken(token);
        user.setResetTokenExpiry(java.time.LocalDateTime.now().plusHours(1));
        userRepository.save(user);

        try {
            notificationProducer.sendNotification(new NotificationEvent(
                    user.getUserId(),
                    user.getEmail(),
                    "AUTH",
                    "Password Reset Request",
                    "To reset your password, click the link: https://ai-resume-builder-frontend-sandy.vercel.app/reset-password?token=" + token,
                    "EMAIL"));
        } catch (Exception e) {
            log.error("Failed to send password reset email: {}", e.getMessage());
        }
    }

    @Override
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        User user = userRepository.findByResetToken(request.getToken())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid or expired reset token"));

        if (user.getResetTokenExpiry().isBefore(java.time.LocalDateTime.now())) {
            throw new InvalidCredentialsException("Reset token has expired");
        }

        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new InvalidCredentialsException("Passwords do not match");
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        user.setResetToken(null);
        user.setResetTokenExpiry(null);
        userRepository.save(user);
        log.info("Password reset successfully for user: {}", user.getUserId());
    }

    private AuthResponse buildAuthResponse(String token, User user) {
        return new AuthResponse(
                token,
                user.getUserId(),
                user.getFullName(),
                user.getEmail(),
                user.getRole().name(),
                user.getSubscriptionPlan().name(),
                user.getCredits());
    }
}
