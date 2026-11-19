# SPEC-EXTRACT amendment A1 — productive-ratio guard basis (E16.1)

Date: 2026-11-18. Authority: SPEC-v1 §15 amendment process; registers against
SPEC-EXTRACT-v1 E16.1 and E26. Orchestrator decision, evidence below.

## Problem (device-verified)

Jason reproduced on 0.1.4 (S26, API 37): manual extract on an Engadget article
shows the E13 failure tint (red icon), no content. Orchestrator diagnosis:

- Fetch with the app UA returns HTTP 200, full 61,559-byte page, no challenge.
- Readability4J 1.0.8 + the app pipeline extracts the article cleanly:
  6,701-byte fragment, 2,503 chars of text (Waymo robotaxi article, verified
  via JVM harness running the same readability4j/jsoup versions).
- E16.1 productive-ratio guard as specified: `plainTextLength(sanitized) × 20 <
  body.html.length` → 2,503 × 20 = 50,060 < 61,559 → REJECT (UNPRODUCTIVE).

The guard compares extracted text against the WHOLE raw document. Modern news
pages are 50-200KB of chrome; a complete 2-4K-char article is routinely <5% of
that. The guard therefore rejects legitimate extractions on most major sites.
The battle-tested web reference (feedbucket/extract.py, _run_extraction) has NO
ratio guard; its quality gates are challenge detection + the length guard.

## Amendment (binding)

E16.1's productive-ratio guard is RETAINED but its basis changes:

- OLD: `plainTextLength(sanitized) × 20 < rawDocumentHtml.length` → failure.
- NEW: `plainTextLength(sanitized) × 20 < cleanedFragmentHtml.length` → failure,
  where `cleanedFragmentHtml` is the post-ExtractCleaner, post-sanitize article
  fragment HTML (the same string whose plain text is measured).

Intent preserved: a fragment that is mostly markup (nav/menu/junk grab) still
fails (e.g. 100 chars text in 5,000 chars markup → 2,000 < 5,000 → reject).
Site chrome outside the extracted fragment no longer counts against it.

All other E16 guards unchanged (challenge markers on the fetched document,
web-exact length guard, 2 MiB stored-size cap). E21 silent no-op unchanged.

## Required implementation

- ExtractCoordinator: measure the ratio against the sanitized fragment length,
  not body.html.length. One-line basis change + comment citing this amendment.
- Tests: (a) regression fixture — heavy page (~60KB raw) with a ~2.5K-char
  article fragment MUST store (the Engadget case, synthetic fixture per D16);
  (b) junk fragment (short text, markup-heavy fragment) MUST still reject;
  (c) existing guard tests updated to the new basis, none weakened.
- REDCHECK: restore the raw-document basis → heavy-page regression red →
  restore fix → green.
- Instrumented coverage: the failure path is observable on-device; add/extend
  an instrumented case if the coordinator is constructible there, else the JVM
  suite + hosted CI is the gate (record honestly).

## Out of scope

No UA change (E1/E18 stand). No trigger change (E6 stands; uniformly truncated
feeds use the per-feed isFullContent chip, which is the preserved ReadYou
behavior). No paywall circumvention (E1).
