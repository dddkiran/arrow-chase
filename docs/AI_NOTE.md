# AI engineering note

## How I used AI on this work

I built this slice pair-programming with an AI coding agent (Claude Code) in the terminal and editor. The split:

- **Plan first, then small verified steps.** From the brief, the agent proposed a written plan: the game rules (bet window, closed blocks, lock = the server quote at processing time), the architecture (pure single-threaded engine behind an actor loop, integer money, idempotent requestId, snapshot-on-connect) and what the tests had to prove. Each of those decisions is recorded in ARCHITECTURE.md so it can be challenged on its merits rather than taken on trust. The agent then wrote most of the code, CSS and boilerplate (Maven wrapper, Vite/Vitest config, protocol DTOs), compiling and testing after each step.
- **It was most valuable** for the pricing maths (the Markov-chain DP and spotting that one backward pass gives every start state, so the table can be computed once), for writing the large test matrix quickly, and for tedious-but-error-prone work like keeping TypeScript protocol types in line with Java records.
- **Verification was not delegated.** Every claim was checked by running something: unit tests, a Monte Carlo test that cross-checks the exact probabilities, a 900k-bet simulation to measure RTP (94.8%) before writing it in a document for leadership, and a Playwright run with screenshots to see the UI, not just trust that it rendered.


## Standards I'd set for a squad

**How work flows**

1. **AI first.** Every task starts with the agent: it reads the ticket and the code and proposes a plan before any code is written.
2. **Steer, don't hand off.** The engineer reviews and corrects the plan, prompts the agent to carry out that plan, then checks it did exactly that: the diff matches the plan, with no unrequested changes, weakened tests or "while I was here" edits.
3. **A local AI dev loop.** Write code, AI review of the diff, write unit and integration tests, run the repo's own build, test and lint commands; repeat until green. Another human sees the work only after the loop passes.
4. **Humans commit.** The agent never commits or pushes. This is enforced in the agent's permission settings (deny `git commit` and `git push`), not left to the prompt.
5. **No direct pushes to main.** Branch protection is on, and every change goes through a clean PR that carries its evidence: the agreed plan, test output, a screenshot for UI or a log/trace for backend, and the AI review notes. The pipeline runs build, unit, integration and e2e tests, lint and security scans on every PR. No merge on "looks right".
6. **A human approves every PR, and accountability sits with people.** The coder owns what was written, the reviewer what was approved, QA what was signed off, whoever or whatever typed it. "The AI wrote it" is never an answer in review or in an incident.

**Steering**

- **Team coding guidelines live in steering files in the repo** (e.g. `CLAUDE.md` plus per-module rules): conventions, architecture boundaries, test expectations, what not to touch. They are versioned and reviewed like code, and updated whenever review catches the same AI mistake twice.
- **Every tool the agent integrates with gets its own steering file** (CI, cloud, database, ticketing, observability, MCP servers): how to use it, which commands and environments are allowed, what is read-only, and what always needs a human.

**Allowed and encouraged**

- AI for drafting code, tests, migrations, configs, docs and PR descriptions; for exploring an unfamiliar codebase; for first-pass code review; for generating test cases, property tests and edge-case lists.
- Agents running locally with the repo's own commands (build, test, lint), in a branch, under the engineer's identity.

**Non-negotiable**

1. **Behaviour is locked by tests the engineer has read and understands.** For AI-written tests, check that the test fails when the behaviour breaks: mutate the code or flip an assertion once.
2. **Normal review rules apply, with no reduced scrutiny because "it's generated".** Large AI-produced diffs get split up. Reviewers can reject a PR for being too big to review.
3. **No secrets, customer data or production credentials in prompts.** Use only approved, enterprise-tier tools with no training on our data.

**Needs a named human expert to review, every time**

- Money movement, settlement, wallet/ledger code, and anything with idempotency or exactly-once claims.
- RNG, pricing and RTP maths. The AI can derive it; a person who understands it signs it off, backed by a simulation.
- Concurrency and threading, authentication and authorisation, input validation on public endpoints.
- Database migrations and anything irreversible.

**What I would not trust AI with**

Many things, because it hallucinates: it will invent APIs, config keys, file paths and test results, and report work as done when it isn't, all with the same confidence as when it's right. Nothing it says is taken for granted; every claim is verified by reading the code or running something. 

**How I'd measure it is working:** lead time and change-failure rate (DORA), escaped defects per release, and review turnaround. If AI increases throughput but escaped defects rise, we tighten the review rules instead of shipping faster.
