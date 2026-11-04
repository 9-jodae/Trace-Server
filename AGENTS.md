# Repository instructions

This is the single source of repository rules. Keep AI harness files (`AGENTS.md`, `CLAUDE.md`, `.agents/**`, `.claude/**`, `.codex/**`, `docs/ai/**`) in English. Keep human documentation (`README.md`, other `docs/**`, issue and PR templates), user-facing messages, commit subjects, PR bodies, and reports in Korean. Put workflow details in `docs/ai/` and background in the Korean `docs/` files.

## Structure and ownership

- Keep one Gradle module and simple layers. Put shared code in `global` and feature code in `domain/<feature>`.
- Keep HTTP mapping and input validation in Controllers, business rules and transactions in Services, and persistence in Repositories. Separate entities from API DTOs. Follow `docs/ai/rules/spring-kotlin.md`.
- Add a domain in this order: entity, Flyway migration, Repository, Service, Controller, tests. Follow `docs/adding-a-domain.md` and `docs/ai/workflows/add-domain.md`.
- Manage the shared database schema only through Flyway in this repository. Add a new migration; never edit an existing migration. The AI server must not change the schema.

## Safety and verification

- Do not invent unconfirmed domains, APIs, or access rules. Record unresolved decisions in the relevant Korean `docs/` file.
- Never commit or log secrets, tokens, document contents, or presigned URLs. Follow `docs/ai/rules/security.md`.
- Update README and relevant `docs/` when behavior or setup changes. Isolate test state. Run `./gradlew ktlintCheck test build` (Windows: `.\gradlew.bat ktlintCheck test build`) and report actual results. Follow `docs/ai/workflows/verify.md`.

## Branches, commits, and PRs

- Before work, inspect the current directory, `git status`, branch, and `git fetch` result. Preserve existing changes and stop on conflicts that need judgment.
- Never push directly to `main` or `develop`. Use a feature branch, focused commits, and a PR targeting `develop`. State dependencies on preceding PRs.
- Never force push or rewrite published history: no `--force`, `--force-with-lease`, `-f`, `+refspec` pushes, and no rebase of pushed branches. Update stacked branches with `git merge`. If a push is rejected as non-fast-forward, fetch and merge; stop if that needs judgment.
- Use `type(scope): Korean description`; allowed types are `feat`, `fix`, `docs`, `test`, `chore`, `refactor`. Write PR reasons, changes, verification, and related issues in Korean. Follow `docs/ai/workflows/commit-and-pr.md`.
- A PR is not done until review-bot comments are handled per `docs/ai/workflows/review-response.md`.
