# AGENTS

Add repo-specific instructions above or below the managed facts block. Keep manual guidance outside the generated markers.

<!-- BEGIN app-wabbit-dev managed facts -->
## Generated Facts

- Workspace config source of truth: `root.clj` at the workspace root.
- Use `dev where` from this repo to confirm the inferred workspace, repo, and project context.
- Canonical repo target: `kotlin-lang-jinjer`. Useful entrypoints: `dev project show kotlin-lang-jinjer`, `dev build kotlin-lang-jinjer`, `dev check kotlin-lang-jinjer`.
- Setup-managed files are regenerated with `dev setup kotlin-lang-jinjer`; avoid hand-editing stamped generated files.
- Sanctioned override files in this repo: `build.extra.gradle.kts`, `settings.local.gradle.kts`.
- Review `kotlin-conventions.md` before editing Kotlin code in this repo.
- Configured project types: `kotlin/kmp`. Docs: `dokka`.
<!-- END app-wabbit-dev managed facts -->
