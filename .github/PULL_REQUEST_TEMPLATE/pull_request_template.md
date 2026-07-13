## Summary

<!-- One paragraph describing what this PR does and why. -->

## Type of Change

- [ ] Bug fix (non-breaking change that fixes an issue)
- [ ] New feature (non-breaking change that adds functionality)
- [ ] Breaking change (fix or feature that changes existing behavior)
- [ ] Documentation update
- [ ] Refactoring (no behavior change)
- [ ] Performance improvement
- [ ] Dependency update
- [ ] CI/CD / infrastructure change

## Related Issues

<!-- Link related issues: "Fixes #123", "Closes #456", "Part of #789" -->

Fixes #

## What Changed

<!-- Bullet points describing the key changes. Focus on the "what", not the "how" — the code shows the how. -->

- 
- 
- 

## Why This Approach

<!-- Explain the key design decision(s) made, especially if alternatives exist. Reference EDRs if relevant. -->

## Testing

<!-- Describe how you tested these changes. -->

- [ ] Unit tests added / updated
- [ ] Integration tests added / updated (Testcontainers)
- [ ] Manually tested with Docker Compose
- [ ] Load tested (if performance-sensitive)

**Test commands run:**
```bash
mvn test
mvn verify
```

## Screenshots / Logs

<!-- For UI changes, include before/after screenshots. For behavior changes, include relevant log output. -->

## Database Migrations

<!-- If this PR adds or modifies Flyway migrations, answer: -->

- [ ] No database migrations in this PR
- [ ] Migration added — backward-compatible with previous version
- [ ] Migration requires coordinated deployment (explain below)

## Breaking Changes

<!-- If this is a breaking change, describe what breaks and the migration path. -->

N/A

## Checklist

- [ ] `mvn spotless:check` passes (code formatting)
- [ ] `mvn checkstyle:check` passes
- [ ] `mvn test` passes
- [ ] `mvn verify` passes (integration tests)
- [ ] Test coverage ≥ 80% for new code
- [ ] No sensitive data, credentials, or PII in code or tests
- [ ] Javadoc added for new public methods in service classes
- [ ] If this changes Kafka event schemas: all consumers updated, `schemaVersion` incremented
- [ ] If this changes API contracts: OpenAPI docs / Swagger annotations updated
- [ ] CHANGELOG.md updated (for user-visible changes)
- [ ] Relevant docs updated (README, ARCHITECTURE, docs/)
