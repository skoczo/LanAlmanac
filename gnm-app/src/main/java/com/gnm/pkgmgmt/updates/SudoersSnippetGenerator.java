package com.gnm.pkgmgmt.updates;

import com.gnm.pkgmgmt.profile.OsProfile;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@ApplicationScoped
public class SudoersSnippetGenerator {

    private static final Pattern POSIX_USERNAME_PATTERN =
            Pattern.compile("^[a-z_][a-z0-9_-]{0,31}$");

    public String generate(String username, OsProfile profile) {
        if (username == null || !POSIX_USERNAME_PATTERN.matcher(username.trim()).matches()) {
            throw new IllegalArgumentException(
                    "Invalid POSIX username for sudoers generation");
        }
        String cleanUser = username.trim();

        if (profile == null
                || profile.privilegeSpec == null
                || profile.privilegeSpec.sudoersAllowedCommands() == null
                || profile.privilegeSpec.sudoersAllowedCommands().isEmpty()) {
            return "# No privileged sudo commands required for profile "
                    + (profile != null ? profile.slug : "unknown")
                    + "\n";
        }

        List<String> validatedCommands = new ArrayList<>();
        for (String rawCmd : profile.privilegeSpec.sudoersAllowedCommands()) {
            if (rawCmd == null || rawCmd.isBlank()) {
                continue;
            }
            String cmd = rawCmd.trim();
            if (!cmd.startsWith("/")
                    || cmd.contains("\n")
                    || cmd.contains("\r")
                    || cmd.contains(",")
                    || cmd.contains("#")) {
                throw new IllegalArgumentException(
                        "Invalid sudoers command entry (must be an absolute path without commas or newlines): "
                                + cmd);
            }
            validatedCommands.add(cmd);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# /etc/sudoers.d/lanalmanac — generated for OS profile '")
                .append(profile.slug)
                .append("'\n");
        sb.append("# Install with:\n");
        sb.append("#   sudo tee /etc/sudoers.d/lanalmanac >/dev/null <<'EOF'\n");
        sb.append("Defaults:").append(cleanUser).append(" !requiretty\n");
        sb.append(cleanUser)
                .append(" ALL=(root) NOPASSWD: ")
                .append(String.join(", ", validatedCommands))
                .append("\n");
        sb.append("# EOF\n");
        sb.append("#   sudo chmod 0440 /etc/sudoers.d/lanalmanac && sudo visudo -cf /etc/sudoers.d/lanalmanac\n");
        return sb.toString();
    }
}

