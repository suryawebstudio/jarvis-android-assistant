---
name: mark-computer-use
description: Reliable computer-use guidance for MARK/JARVIS. Use when planning or executing visible Android accessibility actions or desktop actions through agent-desktop.
---

# MARK computer use

Use computer control only through a bounded observe–act–verify loop. Prefer a structured API or Android-native tool when one exists. Use visible accessibility control only for the current foreground UI.

## Core loop

1. Start a task for a multi-step request.
2. Inspect the current screen before choosing a target.
3. Select one visible, enabled, actionable element from the latest snapshot.
4. Perform exactly one action.
5. Inspect again and verify the expected state change.
6. Continue only when the result matches the intended step.
7. Stop on completion, cancellation, a security boundary, or the task-step limit.

Never issue multiple UI actions in one turn. Never claim success from the action call alone.

## Android MARK actions

`inspect_visible_screen` returns structured JSON with `snapshot_id`, visible elements, roles, bounds, available actions, and refs such as `@a1abc123:e1`.

For `tap`, `type`, and `scroll`, pass both:

- `snapshot_id`: the ID from the latest inspection;
- `ref`: the exact element ref from that same inspection.

Do not guess a target from a label. Do not reuse a ref after any screen-changing action. If MARK reports a stale, invalid, hidden, disabled, or ambiguous target, inspect again and choose a new ref. Back, Home, and overview are global navigation actions and do not need a ref.

## Desktop agent-desktop actions

When a paired desktop companion is available, use `agent-desktop` through its CLI or MCP interface. Snapshot the target app/window first, act on a fully qualified snapshot ref, then snapshot or wait to verify. Resolve ambiguous windows explicitly. Use skeleton or drill-down snapshots for dense applications. Retry only when the returned error says retry is safe.

Headless semantic actions are preferred. Use headed input only when the platform or application refuses the semantic action. Never treat a screenshot or pointer position as proof that an action succeeded.

## Safety boundaries

Require explicit confirmation immediately before consequential actions, including sending, deleting, purchasing, paying, calling, sharing, posting, publishing, transferring, installing, changing security settings, or submitting a form. Confirm the exact target and effect. Do not combine confirmation with an unrelated action.

Never bypass passwords, biometrics, CAPTCHAs, permission dialogs, security screens, app isolation, or OS confirmation prompts. Do not inspect hidden content. Stop when the user says stop, cancel, or abort.

Do not expose accessibility snapshots, clipboard contents, screenshots, traces, or tool output in public logs. Treat them as private desktop or phone content.

## Recovery

- No accessible screen: ask the user to bring the app to the foreground or enable MARK Accessibility.
- Stale or invalid ref: inspect again; do not retry the old ref.
- Ambiguous target: inspect again with a narrower region or ask the user to choose.
- Inaccessible custom UI: report the limitation and ask the user to perform the step or use an approved alternate API.
- Unexpected result: stop the task, report what was observed, and do not continue guessing.
