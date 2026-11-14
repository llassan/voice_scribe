"""
Generates the demo recordings used for the Play Store screenshots.

Store screenshots went stale once already because recreating them meant
recording junk into an emulator by hand; the result was a listing full of
"[Bell]" and "No speech detected". This writes a shell script that seeds a
realistic library straight into the app's private directory instead.

    python3 store/seed-demo-data.py
    adb shell run-as com.vikash.voicescribe sh < store/seed.sh

Needs a DEBUG build installed (run-as only works on debuggable packages).
The audio is silence sized to match each recording's stated duration, so the
player shows the right length and the transcript timestamps line up.
"""

import json, base64, struct, time, os

PKG = "com.vikash.voicescribe"
OUT = os.path.dirname(os.path.abspath(__file__))  # store/
now = int(time.time() * 1000)
MIN = 60_000

def seg_list(lines, start_ms, step_ms):
    segs, t = [], start_ms
    for text in lines:
        segs.append({"t0": t, "t1": t + step_ms, "text": text, "speaker": None})
        t += step_ms
    return segs

bio_lines = [
    "Okay, let's pick up where we left off on Monday with cellular respiration.",
    "The whole point of this process is to take the energy stored in glucose and",
    "convert it into ATP, which is the currency the cell can actually spend.",
    "There are three stages, and I want you to know all three by name for the exam.",
    "Glycolysis, the citric acid cycle, and oxidative phosphorylation.",
    "Glycolysis happens in the cytoplasm and it does not require oxygen.",
    "One glucose molecule is split into two molecules of pyruvate,",
    "and you get a net gain of two ATP and two NADH.",
    "Now, if oxygen is available, that pyruvate moves into the mitochondrion.",
    "This is where the citric acid cycle takes over, and it is sometimes called",
    "the Krebs cycle after Hans Krebs, who worked it out in the nineteen thirties.",
    "Each turn of the cycle releases carbon dioxide and loads up more NADH and FADH2.",
    "Those electron carriers are the key, because they feed the electron transport chain.",
    "The chain sits in the inner mitochondrial membrane, and as electrons pass down it,",
    "protons get pumped into the intermembrane space, building a gradient.",
    "That gradient drives ATP synthase, which is essentially a molecular turbine.",
    "This is chemiosmosis, and it accounts for the large majority of your ATP yield.",
    "Total yield is roughly thirty to thirty-two ATP per glucose molecule.",
    "I say roughly because the number depends on the shuttle system the cell uses.",
    "If oxygen is not available, the cell falls back on fermentation instead,",
    "which regenerates NAD+ so glycolysis can keep running, but yields far less energy.",
    "That is why sprinting burns out so quickly compared to steady aerobic exercise.",
    "For Thursday, read chapter nine, sections one through four,",
    "and be ready to walk through the full pathway on the board.",
]

recordings = [
    dict(
        title="Biology 204 — Cellular Respiration",
        created=now - 2 * 3600_000,
        dur=47 * MIN + 12_000,
        real_audio=True,
        summary=[
            "Cellular respiration converts glucose into ATP in three stages: glycolysis, the citric acid cycle, and oxidative phosphorylation.",
            "Glycolysis runs in the cytoplasm without oxygen and nets 2 ATP plus 2 NADH per glucose.",
            "The citric acid cycle releases CO2 and loads electron carriers NADH and FADH2 inside the mitochondrion.",
            "The electron transport chain builds a proton gradient that drives ATP synthase — chemiosmosis, and most of the ATP yield.",
            "Total yield is about 30–32 ATP per glucose, varying with the shuttle system used.",
            "Without oxygen the cell switches to fermentation, which regenerates NAD+ but yields far less energy.",
            "Homework: read chapter 9, sections 1–4 before Thursday.",
        ],
        segments=seg_list(bio_lines, 6_000, 7_000),
    ),
    dict(
        title="Standup — sprint 14",
        created=now - 26 * 3600_000,
        dur=6 * MIN + 48_000,
        summary=[
            "Checkout refactor is code complete and waiting on review from the payments team.",
            "The flaky integration test was traced to a shared fixture, fix is in review.",
            "Design handoff for the new empty states lands Thursday.",
        ],
        segments=seg_list([
            "Morning everyone, let's keep this quick, I know there's a lot on today.",
            "The checkout refactor is code complete as of last night.",
            "It's waiting on a review from the payments team before it can merge.",
            "On the flaky test — I tracked it down to a shared fixture.",
            "Two suites were writing to the same file, so order decided who won.",
        ], 4_000, 8_000),
    ),
    dict(
        title="Interview — Maria on onboarding",
        created=now - 2 * 86400_000,
        dur=28 * MIN + 35_000,
        summary=[
            "New hires lose most of their first week waiting on access to internal tools.",
            "Maria suggests a single checklist owned by the hiring manager rather than three separate ones.",
            "Buddy system worked well, but pairing is inconsistent across teams.",
        ],
        segments=seg_list([
            "Thanks for making the time, I know you're between projects right now.",
            "I want to understand what the first two weeks actually felt like.",
            "Honestly? The first week was mostly waiting for accounts to be set up.",
        ], 3_000, 9_000),
    ),
    dict(
        title="Groceries and weekend errands",
        created=now - 3 * 86400_000,
        dur=52_000,
        summary=[
            "Pick up oat milk, coffee, rice, and something for Sunday dinner.",
            "Drop the blue jacket at the dry cleaner before they close at six.",
            "Call the garage about the rattling noise on the passenger side.",
        ],
        segments=seg_list([
            "Oat milk, coffee, a bag of rice, and figure out Sunday dinner.",
            "Drop the blue jacket at the cleaners, they close at six.",
            "And call the garage about that rattle on the passenger side.",
        ], 2_000, 10_000),
    ),
    dict(
        title="Thesis idea — walking notes",
        created=now - 4 * 86400_000,
        dur=4 * MIN + 17_000,
        summary=[
            "Narrow the question to how small clinics handle records when the network is down.",
            "Offline-first is the interesting constraint, not the storage format itself.",
            "Email Dr. Ellis about whether the 2024 field data can be reused.",
        ],
        segments=seg_list([
            "I think the question is too broad as written in the proposal.",
            "What I actually care about is what happens when the network is down.",
            "Small clinics still have to see patients when the connection drops.",
        ], 5_000, 11_000),
    ),
    dict(
        title="Client call — Riverside rollout",
        created=now - 6 * 86400_000,
        dur=22 * MIN + 6_000,
        summary=[
            "Riverside wants the pilot live in two sites before the end of the quarter.",
            "Training will be remote, with recorded sessions for staff on night shifts.",
            "They need the data-retention policy in writing before signing.",
        ],
        segments=seg_list([
            "So where we landed last time was two pilot sites, not five.",
            "That still holds, and we'd want both live before the quarter closes.",
            "Training can be remote as long as the sessions are recorded.",
        ], 4_000, 9_500),
    ),
]

def wav_header(seconds, rate=8000):
    data_len = int(seconds * rate * 2)
    hdr = b"RIFF" + struct.pack("<I", 36 + data_len) + b"WAVEfmt " + \
        struct.pack("<IHHIIHH", 16, 1, 1, rate, rate * 2, 2, 16) + \
        b"data" + struct.pack("<I", data_len)
    return hdr, data_len

cmds = ["set -e", "cd files", "mkdir -p recordings", "rm -f recordings/*"]
ids = []
for i, r in enumerate(recordings):
    rid = "demo-%02d" % i
    ids.append(rid)
    secs = r["dur"] / 1000.0 if r.get("real_audio") else 1.0
    hdr, data_len = wav_header(secs)
    obj = {
        "id": rid, "title": r["title"], "titleEdited": True,
        "createdAt": r["created"], "durationMs": r["dur"],
        "audioPath": "/data/data/%s/files/recordings/%s.wav" % (PKG, rid),
        "transcript": " ".join(s["text"] for s in r["segments"]),
        "segments": r["segments"], "summary": r["summary"],
        "language": "en", "status": "DONE", "error": None,
    }
    j = base64.b64encode(json.dumps(obj).encode()).decode()
    h = base64.b64encode(hdr).decode()
    cmds.append("echo %s | base64 -d > recordings/%s.json" % (j, rid))
    cmds.append("echo %s | base64 -d > recordings/%s.wav" % (h, rid))
    cmds.append("dd if=/dev/zero bs=4096 count=%d >> recordings/%s.wav 2>/dev/null" %
                (max(1, data_len // 4096), rid))
cmds.append("ls -l recordings | head -20")

open(os.path.join(OUT, "seed.sh"), "w").write("\n".join(cmds) + "\n")
print("wrote seed.sh with %d recordings" % len(recordings))
