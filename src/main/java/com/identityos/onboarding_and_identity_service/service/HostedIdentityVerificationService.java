package com.identityos.onboarding_and_identity_service.service;

import com.identityos.onboarding_and_identity_service.dto.HostedIdentityOtpRequest;
import com.identityos.onboarding_and_identity_service.dto.HostedIdentityOtpResponse;
import com.identityos.onboarding_and_identity_service.dto.HostedIdentityOtpVerifyRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class HostedIdentityVerificationService {
    private static final long OTP_TTL_SECONDS = 300;
    private static final String DUMMY_AADHAAR_OTP = "123456";

    private final JavaMailSender mailSender;
    private final String mailFrom;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, OtpEntry> otpStore = new ConcurrentHashMap<>();
    private final Map<String, String> verificationKeyById = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastEmailSentAt = new ConcurrentHashMap<>();

    public HostedIdentityVerificationService(
            JavaMailSender mailSender,
            @Value("${organization-registration.mail.from}") String mailFrom) {
        this.mailSender = mailSender;
        this.mailFrom = mailFrom;
    }

    public HostedIdentityOtpResponse requestOtp(HostedIdentityOtpRequest request) {
        String fieldType = normalize(request.fieldType());
        String fieldName = normalize(request.fieldName());
        String key = key(request.clientId(), canonicalFieldName(request.fieldName(), request.fieldType()), request.value());

        if ("email".equals(fieldType) || "email".equals(fieldName)) {
            OtpEntry entry = deterministicOtpEntry(key);
            String verificationId = verificationIdFor(key, entry);
            String otp = entry.otp();
            otpStore.put(key, entry);
            if (shouldSendEmail(key)) {
                sendEmailOtp(request.value(), otp);
                return new HostedIdentityOtpResponse(true, "Email OTP sent.", false, verificationId);
            }
            return new HostedIdentityOtpResponse(true, "OTP already sent. Please use the latest code from Mailpit.", false, verificationId);
        }

        if (isAadhaar(fieldType) || isAadhaar(fieldName)) {
            OtpEntry entry = reusableOtp(key, false);
            String verificationId = verificationIdFor(key, entry);
            return new HostedIdentityOtpResponse(true, "Aadhaar dummy OTP generated. Use 123456.", false, verificationId);
        }

        OtpEntry entry = reusableOtp(key, false);
        String verificationId = verificationIdFor(key, entry);
        return new HostedIdentityOtpResponse(true, "Dummy OTP generated. Use 123456.", false, verificationId);
    }

    public HostedIdentityOtpResponse verifyOtp(HostedIdentityOtpVerifyRequest request) {
        String key = keyForVerification(request);
        OtpEntry entry = otpStore.get(key);
        if (entry == null) {
            if (isValidDeterministicOtp(key, request.otp())) {
                removeVerificationId(request.verificationId());
                return new HostedIdentityOtpResponse(true, "Verification completed.", true, request.verificationId());
            }
            return new HostedIdentityOtpResponse(false, "Generate OTP again.", false, request.verificationId());
        }
        if (entry.expiresAt().isBefore(Instant.now())) {
            otpStore.remove(key);
            removeVerificationId(request.verificationId());
            return new HostedIdentityOtpResponse(false, "OTP expired. Generate OTP again.", false, request.verificationId());
        }
        if (!entry.otp().equals(request.otp())) {
            if (!isValidDeterministicOtp(key, request.otp())) {
                return new HostedIdentityOtpResponse(false, "Invalid OTP.", false, request.verificationId());
            }
        }
        otpStore.remove(key);
        removeVerificationId(request.verificationId());
        return new HostedIdentityOtpResponse(true, "Verification completed.", true, request.verificationId());
    }

    private void sendEmailOtp(String email, String otp) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(mailFrom);
        message.setTo(email);
        message.setSubject("Identity OS email verification OTP");
        message.setText("""
                Your Identity OS verification code is:

                %s

                This code expires in 5 minutes.
                """.formatted(otp));
        mailSender.send(message);
    }

    private String generateOtp() {
        return String.valueOf(100000 + secureRandom.nextInt(900000));
    }

    private boolean shouldSendEmail(String key) {
        Instant now = Instant.now();
        Instant lastSentAt = lastEmailSentAt.get(key);
        if (lastSentAt != null && lastSentAt.plusSeconds(10).isAfter(now)) {
            return false;
        }
        lastEmailSentAt.put(key, now);
        return true;
    }

    private OtpEntry deterministicOtpEntry(String key) {
        String verificationId = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
        return new OtpEntry(deterministicOtp(key, currentOtpWindow()), Instant.now().plusSeconds(OTP_TTL_SECONDS), verificationId);
    }

    private boolean isValidDeterministicOtp(String key, String otp) {
        long currentWindow = currentOtpWindow();
        return deterministicOtp(key, currentWindow).equals(otp)
                || deterministicOtp(key, currentWindow - 1).equals(otp);
    }

    private long currentOtpWindow() {
        return Instant.now().getEpochSecond() / OTP_TTL_SECONDS;
    }

    private String deterministicOtp(String key, long window) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((key + ":" + window + ":" + mailFrom).getBytes(StandardCharsets.UTF_8));
            int value = ((bytes[0] & 0x7f) << 24)
                    | ((bytes[1] & 0xff) << 16)
                    | ((bytes[2] & 0xff) << 8)
                    | (bytes[3] & 0xff);
            return String.format("%06d", value % 1_000_000);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }

    private OtpEntry reusableOtp(String key, boolean randomOtp) {
        OtpEntry existing = otpStore.get(key);
        if (existing != null && existing.expiresAt().isAfter(Instant.now())) {
            return existing;
        }
        String otp = randomOtp ? generateOtp() : DUMMY_AADHAAR_OTP;
        OtpEntry entry = new OtpEntry(otp, Instant.now().plusSeconds(OTP_TTL_SECONDS), UUID.randomUUID().toString());
        otpStore.put(key, entry);
        verificationKeyById.put(entry.verificationId(), key);
        return entry;
    }

    private String verificationIdFor(String key, OtpEntry entry) {
        verificationKeyById.put(entry.verificationId(), key);
        return entry.verificationId();
    }

    private String keyForVerification(HostedIdentityOtpVerifyRequest request) {
        if (request.verificationId() != null && !request.verificationId().isBlank()) {
            String key = verificationKeyById.get(request.verificationId());
            if (key != null && !key.isBlank()) {
                return key;
            }
        }
        return key(request.clientId(), canonicalFieldName(request.fieldName(), request.fieldName()), request.value());
    }

    private void removeVerificationId(String verificationId) {
        if (verificationId != null && !verificationId.isBlank()) {
            verificationKeyById.remove(verificationId);
        }
    }

    private String key(String clientId, String fieldName, String value) {
        return normalize(clientId) + ":" + normalize(fieldName) + ":" + normalize(value);
    }

    private boolean isAadhaar(String value) {
        return "aadhaar".equals(value) || "aadhar".equals(value);
    }

    private String canonicalFieldName(String fieldName, String fieldType) {
        String normalizedName = normalize(fieldName);
        String normalizedType = normalize(fieldType);
        if ("email".equals(normalizedType) || "email".equals(normalizedName)) {
            return "email";
        }
        if (isAadhaar(normalizedType) || isAadhaar(normalizedName)) {
            return "aadhaar";
        }
        if ("phone".equals(normalizedType)
                || "mobile".equals(normalizedType)
                || "phone".equals(normalizedName)
                || "phone_number".equals(normalizedName)
                || "mobile".equals(normalizedName)
                || "mobile_number".equals(normalizedName)) {
            return "mobile";
        }
        return normalizedName;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private record OtpEntry(String otp, Instant expiresAt, String verificationId) {
    }
}
