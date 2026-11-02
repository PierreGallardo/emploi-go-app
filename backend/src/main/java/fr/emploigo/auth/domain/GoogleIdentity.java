package fr.emploigo.auth.domain;

/** Only the claims needed for authentication; civil identity and photo are discarded. */
public record GoogleIdentity(String subject, String email, boolean authoritativeEmail) {
    public static GoogleIdentity verified(
            String subject, String email, boolean verified, String hostedDomain) {
        if (subject == null
                || subject.isBlank()
                || subject.length() > 255
                || subject.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Identité Google invalide.");
        if (email == null) throw new IllegalArgumentException("Courriel Google manquant.");
        String normalized = AccountIdentifier.emailKey(email);
        String domain = normalized.substring(normalized.lastIndexOf('@') + 1);
        boolean workspace =
                hostedDomain != null
                        && hostedDomain.length() <= 253
                        && hostedDomain.matches(
                                "(?i)[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+")
                        && domain.equalsIgnoreCase(hostedDomain);
        return new GoogleIdentity(
                subject, normalized, verified && (domain.equals("gmail.com") || workspace));
    }

    @Override
    public String toString() {
        return "GoogleIdentity[redacted]";
    }
}
