package fr.emploigo.auth.api;

import io.swagger.v3.oas.annotations.media.Schema;

public record AuthenticationErrorResponse(
        @Schema(
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        allowableValues = {
                            "INVALID_INPUT",
                            "INVALID_CREDENTIALS",
                            "RECONNECTION_REFUSED",
                            "UNAUTHENTICATED",
                            "CSRF_INVALID",
                            "FORBIDDEN",
                            "RATE_LIMITED",
                            "MAIL_RECENTLY_SENT",
                            "CODES_BLOCKED",
                            "UNAVAILABLE",
                            "BUSY",
                            "WEAK_PASSWORD",
                            "INVALID_USERNAME",
                            "USERNAME_UNAVAILABLE",
                            "USERNAME_ALREADY_CHOSEN",
                            "ACCOUNT_ALREADY_EXISTS",
                            "INVALID_CODE",
                            "CODE_EXPIRED",
                            "CODE_REPLACED",
                            "CODE_USED",
                            "CODE_ATTEMPTS_EXCEEDED",
                            "NO_PENDING_REGISTRATION",
                            "INVALID_LINK",
                            "EXPIRED_LINK",
                            "USED_LINK",
                            "REPLACED_LINK",
                            "INVALID_FLOW",
                            "EXPIRED_FLOW",
                            "CANCELLED",
                            "PROVIDER_REJECTED",
                            "ACCOUNT_ACCESS_REQUIRED",
                            "EMAIL_CONFIRMATION_REQUIRED",
                            "ALREADY_LINKED"
                        })
                String code,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String message) {}
