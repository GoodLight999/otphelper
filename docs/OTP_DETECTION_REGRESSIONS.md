# OTP detection regression ledger

## Numeric notification metadata must not borrow body authentication context

Observed on 2026-09-03 on a physical Android notification:

```text
244080
923030は、Amazonのワンタイムパスワードです。
誰とも共有しないでください。
```

Correct OTP: `923030`  
Historical false positive: `244080`

The failure was structural, not Amazon-specific. Android notification fields were concatenated with title/conversation metadata before body text, and the historical "numeric code before sensitive phrase" matcher could consume from the first number through a later authentication phrase.

Required invariants:

1. Evaluate each visible notification line independently before cross-line inference.
2. A bare numeric title, sender short code, conversation ID, phone number, or similar metadata line cannot borrow an authentication phrase from another body field.
3. When structured Android `Notification` fields are available, cross-line inference uses body fields and excludes `EXTRA_TITLE` / `EXTRA_TITLE_BIG`.
4. A title containing its own explicit authentication phrase remains eligible during line-local evaluation.
5. Accessibility notification ingestion applies the same body-only cross-line rule when it receives the underlying `Notification` object.
6. The core pre-phrase numeric matcher must enumerate each numeric token independently. Matching an earlier number must not consume and hide a later, closer numeric candidate.
7. Candidate ranking selects primarily by authentication-phrase proximity; six-digit shape is only a weak tiebreaker.
8. Do not add provider-specific exceptions for this class of failure.

Regression coverage includes the exact example above, a split-body variant where `923030` and the one-time-password phrase are on separate body lines, and a flattened single-line variant containing both numeric candidates.

## MessagingStyle body text must remain visible to OTP extraction

Android messaging notifications may expose message bodies through `Notification.MessagingStyle` / `Notification.EXTRA_MESSAGES` rather than relying only on flattened `EXTRA_TEXT`, `EXTRA_BIG_TEXT`, or `EXTRA_TEXT_LINES` values. OTP ingestion must therefore preserve current MessagingStyle message text through the public Android/AndroidX representation.

Required invariants:

1. Use the public MessagingStyle extraction API rather than depending on private Bundle key layouts.
2. Treat MessagingStyle message text as **body** content for cross-line OTP inference; conversation title/sender metadata remains outside that body fallback.
3. Append current MessagingStyle messages newest-first so an older authentication message cannot win merely because the style stores messages chronologically.
4. Do not add `EXTRA_HISTORIC_MESSAGES` to OTP input. Historical conversation entries can contain expired authentication codes and are not evidence that an old code is current.
5. A malformed or OEM-specific MessagingStyle payload must not crash notification ingestion; ordinary public text fields remain the fallback.
6. Standard NotificationListener and Accessibility ingestion must share the same structured body representation whenever the underlying `Notification` object is available.

Instrumentation coverage constructs a MessagingStyle notification with numeric conversation metadata, an older OTP message, and a newer OTP message. The body representation must exclude the numeric conversation title and the selector must return the newer OTP.
