# Codex in this repository

Codex reads `AGENTS.md` as repository instructions. Read `docs/ai/README.md` for the shared workflow map. Use the paired files in `.codex/prompts/` as manual task prompts: refer to or paste the relevant file when starting a task. Automatic discovery of project `.codex/prompts/` was not verified by the installed CLI help or official documentation, so these files do not depend on a custom prompt loader.

Official Codex documentation supports repository skills under `.agents/skills/` and project settings in `.codex/config.toml` for trusted projects. This repository does not need project settings or Codex-specific skills, so neither is created. The shared procedures live in `docs/ai/`.

References: [AGENTS.md guidance](https://developers.openai.com/codex/guides/agents-md), [skills](https://developers.openai.com/codex/skills), [project configuration](https://developers.openai.com/codex/config-basic).
