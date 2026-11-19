package com.gnm.pkgmgmt.profile;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

public final class OsProfileSpecs {

    private OsProfileSpecs() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DetectionCondition(String fact, String op, String value) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DetectionRules(
            String matchMode, List<DetectionCondition> conditions, String verifyCommand) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VulnerabilitySpec(
            boolean enabled, String osvEcosystemTemplate, String queryField) {}
}

