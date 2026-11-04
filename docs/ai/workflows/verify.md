# Verify changes

1. Inspect the diff and relevant tests. Do not overwrite unrelated work.
2. Run `./gradlew ktlintCheck test build` (Windows: `.\gradlew.bat ktlintCheck test build`).
3. Report each actual command outcome in Korean. Include the failing task and root cause if verification fails; do not claim unrun checks passed.
