package com.gnm.pkgmgmt.profile;

import com.gnm.pkgmgmt.profile.OsProfileSpecs.DetectionCondition;
import com.gnm.pkgmgmt.profile.OsProfileSpecs.DetectionRules;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@ApplicationScoped
public class OsProfileMatcher {

    /**
     * Evaluates active profiles ordered by {@code priority ASC} against collected {@code osFacts}
     * and optional {@code verifyResults} without any hardcoded distribution switch.
     */
    public Optional<OsProfile> findMatch(
            List<OsProfile> profiles,
            Map<String, String> osFacts,
            Map<String, Boolean> verifyResults) {
        if (profiles == null || profiles.isEmpty() || osFacts == null || osFacts.isEmpty()) {
            return Optional.empty();
        }

        return profiles.stream()
                .filter(p -> p != null && p.enabled && p.detectionRules != null)
                .sorted(Comparator.comparingInt(p -> p.priority))
                .filter(p -> matchesRules(p.detectionRules, osFacts))
                .filter(p -> verifyCommandPassed(p, verifyResults))
                .findFirst();
    }

    public boolean matchesRules(DetectionRules rules, Map<String, String> facts) {
        if (rules == null || rules.conditions() == null || rules.conditions().isEmpty()) {
            return false;
        }
        boolean anyMode = "ANY".equalsIgnoreCase(rules.matchMode());
        for (DetectionCondition cond : rules.conditions()) {
            boolean matched = evaluateCondition(cond, facts);
            if (anyMode && matched) {
                return true;
            }
            if (!anyMode && !matched) {
                return false;
            }
        }
        return !anyMode;
    }

    private static boolean verifyCommandPassed(
            OsProfile profile, Map<String, Boolean> verifyResults) {
        String verifyCmd =
                profile.detectionRules != null ? profile.detectionRules.verifyCommand() : null;
        if (verifyCmd == null || verifyCmd.isBlank()) {
            return true;
        }
        if (verifyResults == null || !verifyResults.containsKey(profile.slug)) {
            return true;
        }
        return Boolean.TRUE.equals(verifyResults.get(profile.slug));
    }

    private static boolean evaluateCondition(
            DetectionCondition cond, Map<String, String> facts) {
        if (cond == null || cond.fact() == null || cond.op() == null) {
            return false;
        }
        String factVal = facts.getOrDefault(cond.fact(), "");
        String expected = cond.value() != null ? cond.value() : "";

        return switch (cond.op().toUpperCase(Locale.ROOT)) {
            case "EQUALS" -> factVal.equalsIgnoreCase(expected);
            case "NOT_EQUALS" -> !factVal.equalsIgnoreCase(expected);
            case "PREFIX" ->
                    factVal.toLowerCase(Locale.ROOT)
                            .startsWith(expected.toLowerCase(Locale.ROOT));
            case "CONTAINS_TOKEN" -> containsToken(factVal, expected);
            case "IN" -> matchesAnyTokenInList(factVal, expected);
            case "REGEX" -> Pattern.compile(expected).matcher(factVal).find();
            default -> false;
        };
    }

    private static boolean containsToken(String factVal, String token) {
        String target = token.trim().toLowerCase(Locale.ROOT);
        if (target.isEmpty()) {
            return false;
        }
        for (String part : factVal.toLowerCase(Locale.ROOT).split("[\\s,]+")) {
            if (part.equals(target)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAnyTokenInList(String factVal, String commaSeparatedAllowed) {
        Set<String> allowed =
                Arrays.stream(commaSeparatedAllowed.toLowerCase(Locale.ROOT).split("[,\\s]+"))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toSet());
        if (allowed.isEmpty()) {
            return false;
        }
        for (String part : factVal.toLowerCase(Locale.ROOT).split("[\\s,]+")) {
            if (allowed.contains(part.trim())) {
                return true;
            }
        }
        return false;
    }
}

