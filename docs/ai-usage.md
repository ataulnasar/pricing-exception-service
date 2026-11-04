# How AI agents were used

Format: `Phase X — <agent>: <what it did>. I changed: <what>. Checked by: <tests / review / manual>.`

- Phase 0 — Claude (chat): drafted CLAUDE.md, invariants.yaml, ci.yml, design.md and the agent files. I changed: reviewed and adjusted. Checked by: manual review, CI green.
- Phase 1 — test-writer / implementer (Claude) / reviewer: test-writer wrote the domain tests (value objects, rules, status x command table) and attacked the finished code (27 attack tests, 6 hardenings, 1 attack test removed as a design misread); implementer wrote the domain package and ArchUnit rules; reviewer found no high findings. I changed: validation now uses coded ValidationException; documented error codes and events in design.md; fixed a test-writer reflection test that could never pass; decided UTF-16 length limits and comment-check order. Checked by: ./mvnw verify green (134 tests), reviewer report, reading every test name against invariants.yaml.
