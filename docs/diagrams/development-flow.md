# Development flow (agentic SDLC)

How the service was built: a team of AI agents with clear roles, and a human who approves
every phase. Details: [../agent-workflow.md](../agent-workflow.md). What really happened in
each phase: [../ai-usage.md](../ai-usage.md).

## 1. The team

| Role | Who | Can change | Model |
|---|---|---|---|
| Architect and approver | Human | everything | – |
| Implementer | main Claude Code session | `src/main/` | Opus |
| test-writer | agent in `.claude/agents/` | `src/test/` only | Sonnet |
| reviewer | agent in `.claude/agents/` | nothing (read only) | Sonnet |
| docs-writer | agent in `.claude/agents/` | `README.md`, `docs/` | Haiku |
| project-guide | agent in `.claude/agents/` (`/ask`), also a CLI `agents/ask.py` | nothing (read only) | Sonnet |

Agents are split by **what they may see and change**, not by job title. The checking agents
use a different model from the one that writes the code.

## 2. One phase, step by step

Started with one command: `/run-phase <n>` (`.claude/commands/run-phase.md`).

```mermaid
flowchart TD
    spec["Spec: CLAUDE.md, design.md,<br/>invariants.yaml, requirements.md"]
    t1["1. test-writer writes tests first"]
    t2["2. Implementer writes code<br/>(never changes a test)"]
    v1{"./mvnw verify<br/>green?"}
    t3["3. test-writer attacks the code<br/>(fresh context)"]
    t4["4. reviewer checks the diff<br/>against the invariants"]
    v2{"high findings<br/>or red build?"}
    h["5. Human gate: read report,<br/>check diff, approve"]
    c["Commit + push"]
    ci["GitHub CI"]

    spec --> t1 --> t2 --> v1
    v1 -- no --> t2
    v1 -- yes --> t3 --> t4 --> v2
    v2 -- "yes (max 2 rounds)" --> t2
    v2 -- no --> h
    h -- "changes needed" --> t2
    h -- approve --> c --> ci
```

The agents stop and ask the human when a business rule would change, a new dependency is
needed, or the design is unclear.

## 3. Checks after every push

```mermaid
flowchart LR
    push["git push / pull request"]
    subgraph ci["ci.yml"]
        java["Java: ./mvnw verify<br/>unit, web, integration,<br/>concurrency, ArchUnit"]
        pg["PostgreSQL tests<br/>(Testcontainers, must run)"]
        py["Python tests for the review<br/>agent and the project guide CLI"]
    end
    subgraph air["ai-review.yml (pull requests)"]
        key{"API key<br/>secret set?"}
        rev["AI review agent<br/>posts one advisory comment"]
        skip["skip, job stays green"]
    end

    push --> java --> pg
    push --> py
    push --> key
    key -- yes --> rev
    key -- no --> skip
```

- The AI review is **advisory**. Tests and ArchUnit decide if a change is OK.
- The AI review agent is measured with an eval set: 6 patches that each break one invariant,
  plus 2 clean refactors (`agents/eval/`). The live AI run needs an API key and has not been
  run; see `agents/eval/results.md`.

## 4. After the build: the project guide

A developer who did not take part asks the **project-guide** agent (`/ask "<question>"` in
Claude Code, or `python3 agents/ask.py "<question>"` from a terminal, same instructions in
`agents/project-guide-prompt.md`). It reads only the repository: `docs/invariants.yaml` first,
then `docs/design.md` and the ADRs, `docs/how-to-change.md`, the AI usage log, the git log and
the source files that match the question. It cites the files it used, says when the docs and
the code disagree, and for a change question names the invariants touched, the tests to write
first and the files to change. It never changes a file.

```mermaid
flowchart LR
    q["Developer question"]
    r["Rules, design, ADRs,<br/>how-to-change.md, ai-usage.md"]
    s["git log + matching<br/>source files"]
    g["project-guide<br/>(read only)"]
    a["Answer with<br/>file references"]
    q --> g
    r --> g
    s --> g
    g --> a
```

## 5. Controls on agent output

- Tests are written before the code, from the spec, not from the code.
- A fresh test-writer attacks each phase. A read-only reviewer checks every diff.
- The build enforces the architecture rules (ArchUnit), so they don't rely on the agent.
- Every phase ends at a human approval, and every phase is logged in `docs/ai-usage.md`,
  including the cases where an agent was wrong.
