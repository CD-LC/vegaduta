package com.agenticai.core.platform.moderation;

// HISTORY NOTE (2026-08 moderation unification): this file used to hold four
// enums; three were near-duplicates of public types in
// com.agenticai.core.guardrails.moderation and were deleted when the platform
// admin surface was rewired onto that (live, wired, tested) engine:
//   - ModerationScopeType     -> superseded by guardrails' ModerationPolicyScope,
//                                which adds the GLOBAL level the old
//                                (scope_type, scope_key) table could not express
//   - ModerationReviewStatus  -> guardrails' public enum has identical constants
//                                (PENDING/APPROVED/BLOCKED/ESCALATED); import it
//   - ModerationDecision      -> superseded by direct approve/block/escalate
//                                calls on guardrails' ModerationReviewService.
//                                CAUTION: guardrails ALSO has a public enum
//                                named ModerationDecision meaning something
//                                entirely different (the ENGINE verdict
//                                ALLOW/BLOCK/QUEUE_FOR_REVIEW, not a reviewer
//                                action) - do not "restore" the deleted enum by
//                                importing that one; an IDE auto-import will
//                                offer it and only BLOCK exists on both.
// The moderation RUNTIME lives in core/guardrails - do not re-add moderation
// concept enums here. Only the blocklist type below is genuinely
// platform-only (the guardrails engine has no blocklist concept at all).

/** What kind of identifier a blocklist entry holds. */
enum ModerationBlocklistType { USER, PHONE, DOMAIN, REGION }
