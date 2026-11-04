# AI harness map

Read `AGENTS.md` first. Then read the workflow for the task and any relevant rule in this directory. Consult the Korean `docs/` files for product context and detailed domain procedures.

Priority: explicit user request, `AGENTS.md`, applicable `docs/ai/` workflow or rule, then tool-specific wrapper. Edit shared rules only in `AGENTS.md` or `docs/ai/`; keep `.claude/skills/` and `.codex/prompts/` thin.

| Task | Workflow | Rules |
| --- | --- | --- |
| Add a domain | `workflows/add-domain.md` | `rules/spring-kotlin.md`, `rules/testing.md`, `rules/security.md` |
| Verify changes | `workflows/verify.md` | `rules/testing.md` |
| Commit and open a PR | `workflows/commit-and-pr.md` | `rules/security.md` |
| Respond to PR review | `workflows/review-response.md` | Applicable rules above |

Keep this harness in English. Keep human documentation, user-facing text, commits, PR bodies, and reports in Korean, as specified in `AGENTS.md`.
