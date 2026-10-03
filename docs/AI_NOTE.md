# AI engineering note

## How I used AI on this work

I built this slice pair-programming with an AI coding agent (Claude Code) in the terminal and editor. The split:

- **Plan first, then small verified steps.** From the brief, the agent proposed a written plan: the game rules (bet window, closed blocks, lock = the server quote at processing time), the architecture (pure single-threaded engine behind an actor loop, integer money, idempotent requestId, snapshot-on-connect) and what the tests had to prove. Each of those decisions is recorded in ARCHITECTURE.md so it can be challenged on its merits rather than taken on trust. The agent then wrote most of the code, CSS and boilerplate (Maven wrapper, Vite/Vitest config, protocol DTOs), compiling and testing after each step.
- **It was most valuable** for the pricing maths (the Markov-chain DP and spotting that one backward pass gives every start state, so the table can be computed once), for writing the large test matrix quickly, and for tedious-but-error-prone work like keeping TypeScript protocol types in line with Java records.
- **Verification was not delegated.** Every claim was checked by running something: unit tests, a Monte Carlo test that cross-checks the exact probabilities, a 900k-bet simulation to measure RTP (94.8%) before writing it in a document for leadership, and a Playwright run with screenshots to see the UI, not just trust that it rendered.

**What it got wrong, and what caught it:**

| Issue | Caught by |
|---|---|
| Two engine tests assumed quotes change immediately. In fact they change only on a step, which is the correct design. The tests were wrong, not the engine. | Running the tests, then reading the failure carefully instead of "fixing" the engine to match the test. |
| The first version re-ran the probability DP every tick. That was fine for one tick, but would have made a 20k-step simulation take minutes. | Reasoning about complexity before running it. |
| The arrow glyph rendered as an emoji star in Chromium, and the board overflowed a 720px-high screen. | Looking at a real screenshot. Unit tests can't see this. |
| An e2e selector matched both the "5.00" stake chip and a "5.00x" tile. | The e2e run itself. |

None of these were exotic. They're exactly the class of bug a confident reviewer approves without running anything. That's the lesson behind the standards below.

## Standards I'd set for a squad

**Allowed and encouraged**

- AI for drafting code, tests, migrations, configs, docs and PR descriptions; for exploring an unfamiliar codebase; for first-pass code review; for generating test cases, property tests and edge-case lists.
- Agents running locally with the repo's own commands (build, test, lint), in a branch, under the engineer's identity.

**Non-negotiable**

1. **The human who opens the PR owns every line**, whoever or whatever typed it. "The AI wrote it" is never an answer in review or in an incident.
2. **Behaviour is locked by tests the engineer has read and understands.** For AI-written tests, check that the test fails when the behaviour breaks: mutate the code or flip an assertion once.
3. **Run it.** No merge on "looks right". CI is green and the author has exercised the change: a screenshot for UI, a log or trace for backend.
4. **Normal review rules apply, with no reduced scrutiny because "it's generated".** Large AI-produced diffs get split up. Reviewers can reject a PR for being too big to review.
5. **No secrets, customer data or production credentials in prompts.** Use only approved, enterprise-tier tools with no training on our data.

**Needs a named human expert to review, every time**

- Money movement, settlement, wallet/ledger code, and anything with idempotency or exactly-once claims.
- RNG, pricing and RTP maths. The AI can derive it; a person who understands it signs it off, backed by a simulation.
- Concurrency and threading, authentication and authorisation, input validation on public endpoints.
- Database migrations and anything irreversible.

**What I would not trust AI with**

- Deciding product or regulatory rules (what counts as a win, how prices move, which jurisdictions allow what).
- Claims about performance, cost or capacity without a measurement.
- Dependency choices it "remembers". Versions and APIs change; check the lockfile, the changelog and licences.
- Writing both the code *and* the only tests for safety-critical logic with no independent check. Here the Monte Carlo and simulation tests check the DP independently, and that's the pattern.

**How I'd measure it is working:** lead time and change-failure rate (DORA), escaped defects per release, and review turnaround. If AI increases throughput but escaped defects rise, we tighten the review rules instead of shipping faster.
