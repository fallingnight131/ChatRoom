package com.fallingnight.chat.persistence.postgres.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class V1IdentityImportPlannerTest {
    private static final Instant CREATED = Instant.parse("2026-01-02T03:04:05Z");
    private static final String ARGON =
            "$argon2id$v=19$m=65536,t=2,p=1$c2FsdA$aGFzaA";

    @Test
    void deterministicallyMapsBothCredentialGenerationsWithoutDependingOnRowOrder() {
        V1IdentityRow modern = new V1IdentityRow(
                2, "modern", "Modern", ARGON, "", CREATED.plusSeconds(1));
        V1IdentityRow legacy = new V1IdentityRow(
                1, "legacy", "Legacy", "a".repeat(64), "legacy-salt", CREATED);
        V1IdentityImportPlanner planner = new V1IdentityImportPlanner();

        V1IdentityImportPlan first = planner.plan(List.of(modern, legacy));
        V1IdentityImportPlan reordered = planner.plan(List.of(legacy, modern));

        assertTrue(first.readyToCompareWithTarget());
        assertEquals(2, first.sourceRows());
        assertEquals(first.sourceFingerprintSha256(), reordered.sourceFingerprintSha256());
        assertEquals(first.accounts(), reordered.accounts());
        assertEquals(List.of(1L, 2L), first.accounts().stream()
                .map(PlannedIdentityAccount::legacyId)
                .toList());
        PlannedIdentityAccount importedLegacy = first.accounts().get(0);
        assertEquals(ImportedCredentialScheme.V1_SHA256,
                importedLegacy.credentialScheme());
        assertEquals("legacy-salt", importedLegacy.legacyPasswordSalt());
        PlannedIdentityAccount importedModern = first.accounts().get(1);
        assertEquals(ImportedCredentialScheme.ARGON2ID,
                importedModern.credentialScheme());
        assertNull(importedModern.legacyPasswordSalt());

        UUID stable = V1IdentityImportPlanner.deterministicUserId(1);
        assertEquals(stable, importedLegacy.accountId());
        assertEquals(5, stable.version());
        assertEquals(2, stable.variant());
        assertEquals(64, first.sourceFingerprintSha256().length());
    }

    @Test
    void blocksTheWholePlanWithOnlyNonSecretIssueDetails() {
        String sensitiveUsername = "sensitive-user";
        String sensitiveHash = "$argon2id$malformed-secret-hash";
        String sensitiveSalt = "sensitive-salt";
        V1IdentityImportPlan plan = new V1IdentityImportPlanner().plan(List.of(
                new V1IdentityRow(
                        1,
                        sensitiveUsername,
                        "X".repeat(101),
                        sensitiveHash,
                        sensitiveSalt,
                        null),
                new V1IdentityRow(
                        1,
                        sensitiveUsername,
                        "Duplicate",
                        "not-a-supported-hash",
                        "",
                        CREATED)));

        assertFalse(plan.readyToCompareWithTarget());
        assertFalse(plan.issues().isEmpty());
        String report = plan.issues().toString();
        assertFalse(report.contains(sensitiveUsername));
        assertFalse(report.contains(sensitiveHash));
        assertFalse(report.contains(sensitiveSalt));
        assertTrue(plan.issues().stream().anyMatch(
                issue -> issue.code().equals("DUPLICATE_LEGACY_ID")));
        assertTrue(plan.issues().stream().anyMatch(
                issue -> issue.code().equals("DUPLICATE_USERNAME")));
        assertTrue(plan.issues().stream().anyMatch(
                issue -> issue.code().equals("INVALID_ARGON2ID")));
        assertTrue(plan.issues().stream().anyMatch(
                issue -> issue.code().equals("INVALID_CREDENTIAL")));
    }

    @Test
    void refusesAnEmptyOrNonPositiveIdentitySource() {
        V1IdentityImportPlanner planner = new V1IdentityImportPlanner();
        assertEquals("EMPTY_SOURCE", planner.plan(List.of()).issues().get(0).code());
        V1IdentityImportPlan invalid = planner.plan(List.of(new V1IdentityRow(
                0, "zero", "Zero", ARGON, "", CREATED)));
        assertFalse(invalid.readyToCompareWithTarget());
        assertEquals("INVALID_LEGACY_ID", invalid.issues().get(0).code());
    }
}
