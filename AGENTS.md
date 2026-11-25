# Codex and Antigravity

Read [HANDOFF_KO.md](HANDOFF_KO.md) and inspect `git status --short --branch` before continuing work. Both tools use this Umtomi checkout. Current Android work includes untracked source files, so preserve the worktree when switching tools. Use iOS Umtomi as the reference for Android screens and behavior, keeping existing installed user data compatible.

After changes, update the handoff with the files touched, verification performed, artifact paths, and the next unfinished item. Detailed build and release history is in [CODEX_HANDOFF.md](CODEX_HANDOFF.md).

# Bookshelf development

Bookshelf changes must be coordinated with the Omyaru checkout at `C:/Users/peopl/OneDrive/DEV/CODEX/BROWSER` for Omyaru Android, Windows desktop, and standalone Bookshelf. Read `BOOKSHELF_STORAGE_CONTRACT.md` and both projects' latest handoffs before changing storage or synchronization. Preserve stable identities and existing user originals. Do not report shared local storage as implemented until cross-package concurrent access, migration, and removable-media failures have been tested.
