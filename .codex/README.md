# Codex in this repository

Codex reads `AGENTS.md` as repository instructions. Read `docs/ai/README.md` for the shared workflow map. Use the paired files in `.codex/prompts/` as manual task prompts: refer to or paste the relevant file when starting a task. Automatic discovery of project `.codex/prompts/` was not verified by the installed CLI help or official documentation, so these files do not depend on a custom prompt loader.

Official Codex documentation supports repository skills under `.agents/skills/` and project settings in `.codex/config.toml` for trusted projects. This repository uses thin Codex skills in `.agents/skills/` and needs no project settings. The shared procedures live in `docs/ai/`.

| Claude skill | Codex skill | Manual Codex prompt | Shared workflow |
| --- | --- | --- | --- |
| `add-domain` | `add-domain` | `add-domain.md` | `docs/ai/workflows/add-domain.md` |
| `verify` | `verify` | `verify-changes.md` | `docs/ai/workflows/verify.md` |
| `commit-and-pr` | `commit-and-pr` | `commit-and-pr.md` | `docs/ai/workflows/commit-and-pr.md` |
| `review-response` | `review-response` | `review-response.md` | `docs/ai/workflows/review-response.md` |

References: [AGENTS.md guidance](https://developers.openai.com/codex/guides/agents-md), [skills](https://developers.openai.com/codex/skills), [project configuration](https://developers.openai.com/codex/config-basic).
