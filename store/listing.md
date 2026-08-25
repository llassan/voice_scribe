# VoiceScribe — Play Store listing kit

## App title (max 30 chars)

```
VoiceScribe: Voice to Text AI
```
(29 chars — carries the two highest-intent keywords.)

## Short description (max 80 chars)

```
Offline voice recorder with unlimited AI transcription & summaries. No cloud.
```
(78 chars.)

## Full description (max 4000 chars)

```
Record a lecture, meeting, or voice memo — and get a full transcript plus a
bullet-point summary, generated entirely on your phone. No cloud. No account.
No minute limits. Works in airplane mode.

UNLIMITED FREE TRANSCRIPTION
Other transcription apps cap you at a few hundred minutes a month because they
pay for cloud servers. VoiceScribe runs its AI speech-to-text on your device,
so transcription is unlimited and free — forever.

100% PRIVATE, 100% OFFLINE
Your audio never leaves your phone. There are no accounts, no analytics, and
no uploads. Record confidential meetings, medical notes, or personal memos
with confidence — everything stays in your pocket.

WHAT YOU GET
• Voice recorder tuned for speech — keeps recording with the screen off
• Automatic transcript with tap-to-play: tap any sentence to hear that moment
• Automatic bullet-point summary of every recording
• 99 languages with automatic language detection
• Import audio files (MP3, M4A, WAV, OGG, FLAC) — unlimited
• Recordings auto-title themselves from what was said
• Audio playback at 1×, 1.5×, or 2× speed
• Share transcripts and summaries to WhatsApp, email, or any app
• Small download — AI models install on demand, sized to your phone

VOICESCRIBE PRO (optional one-time purchase — not a subscription)
• Export transcripts as PDF or Word (.docx)
• Speaker labels for meetings and interviews (English)
• All future Pro features included

PERFECT FOR
• Students recording lectures and turning them into study notes
• Meetings and interviews — get minutes without typing
• Journalists, researchers, doctors, and lawyers who need privacy
• Voice memos, ideas, and to-do lists captured hands-free
• Transcribing old audio files you already have

HOW IT WORKS
VoiceScribe uses Whisper-class AI models running directly on your phone's
processor. Pick the model that fits your device — from a fast 32 MB model for
quick memos to larger models for maximum accuracy. On phones with less memory,
VoiceScribe automatically recommends the lighter model so transcription stays
smooth.

Note: transcription speed depends on your phone. Longer recordings take longer
to process on older devices — but they always finish, even with the app in the
background.

Voice recorder, speech to text, audio to text, transcribe, meeting notes,
lecture recorder — all in one private app.
```

## Category & tags

- Category: **Tools** (alt: Productivity)
- Tags: voice recorder, speech to text, transcription, audio to text, meeting notes

## Contact / links

- Email: aiamvikku@gmail.com
- Privacy policy URL: host `store/privacy-policy.html` publicly and paste the URL
  (options: GitHub Pages from a small public repo, or a page on llassan.com).

## In-app product (Monetization → Products → In-app products)

- Product ID: `voicescribe_pro`
- Name: VoiceScribe Pro
- Description: One-time unlock: PDF & Word export, speaker labels, all future Pro features.
- Price: $7.99 (set per-country pricing from the US base)

## Data safety form answers

- Does your app collect or share any of the required user data types? **No**
- Is all of the user data collected by your app encrypted in transit? **N/A (no data collected)**
- Do you provide a way for users to request that their data is deleted? **N/A (no data collected)**
  - Audio/transcripts exist only in local app storage; deleting a recording or
    uninstalling removes them.
- App functionality notes if reviewers ask: microphone audio is processed on-device
  only; network access is used solely for model file downloads (Hugging Face CDN)
  and Google Play Billing.

## Permission declarations

- `RECORD_AUDIO`: core feature — user-initiated voice recording.
- Foreground service (microphone): keeps user-initiated recordings running with
  the screen off; visible notification shown.
- Foreground service (dataSync/mediaProcessing): completes on-device transcription
  of a recording the user just made if they background the app; visible notification.

## Assets in this folder

- `icon-512.png` — hi-res icon (512×512)
- `feature-graphic-1024x500.png` — feature graphic
- `screenshots/01-home.png … 06-pro.png` — phone screenshots (1080×2160, 2:1)

## Submission checklist

- [ ] Host privacy policy, paste URL in Play Console
- [ ] Upload AAB (`./gradlew :app:bundleRelease`), not the APK
- [ ] Create `voicescribe_pro` in-app product (must exist before Pro purchase works)
- [ ] Data safety form (answers above)
- [ ] Foreground-service permission declarations (text above)
- [ ] Content rating questionnaire (utility app, no objectionable content → Everyone)
- [ ] Internal testing track first; add license testers for billing tests
- [ ] Verify purchase + restore flows with a license-tester account on a real device
- [ ] Real-device pass: record → transcript → summary; speaker labels with two real voices
