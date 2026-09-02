# Demo script

Target: **six minutes**. Structured so the strongest evidence lands even if it gets cut short.

The order is deliberate. Most demos open with the AR because it looks good, then run out of time before the
part that is actually hard. This opens with the claim that is hardest to fake — offline verification of a
tamper-evident chain — and treats the AR as the delivery mechanism it is.

**Airplane mode is on for the first three minutes.** Say so out loud, on camera, and leave the status bar
visible. Everything up to the sync section works with the radio off, and that is the whole argument.

---

## Before recording

```powershell
.\tools\verify-all.ps1          # confirm nothing is broken
.\tools\run-backend.ps1 -Seed   # terminal 1
.\tools\run-dashboard.ps1       # terminal 2
.\gradlew.bat :android-app:assembleDebug
```

Setup checklist:

- Two handsets if the buddy drill is being shown. If only one is available, **cut that section** rather than
  faking it — see the honesty note at the end.
- Print or display one certificate QR from the seeded data, for the inspector segment.
- Supervisor already signed in on the phone and a site key generated, so the demo does not open on a form.
- One worker with a PIN already set, and one without, to show both paths.
- Dashboard signed in as `inspector.dgms`, sitting on the Overview page.
- Screen recording at 1080p minimum; Ol Chiki and Devanagari need the resolution to be legible.

---

## 0:00 – 0:25 · The problem, in one sentence

> "A safety certificate says somebody passed a test once. It says nothing about whether they would act
> correctly today — and the worker who knows the right answer but freezes for four seconds is the one who gets
> hurt. Jaagruk measures the decision, not the answer, and it does it on the phone the worker already carries,
> underground, with no signal."

Airplane mode already visible. Do not explain the architecture yet.

---

## 0:25 – 2:00 · A drill, in Santali, with no network

Sign in as a worker. **Switch the language to Santali on the sign-in screen** — one tap, in front of the
camera. Point out that this is on the *first* screen because a shared site handset changes hands during a
shift and the previous worker's language is not a sensible default for the next.

Start **Fire evacuation**. Then, while it runs:

- Point at the exit marker in the real room. Note that on a scanned site this marker sits on the *actual*
  doorway, not a generic template position.
- Let the countdown ring get visibly low on one step, then answer. Say: *"That timer is not decoration — the
  score is weighted by how long the decision took against a trained baseline."*
- **Deliberately hesitate on one step.** Answer correctly, but slowly.
- Cover the camera for two seconds. The drill pauses, the prompt says *"Paused: the camera lost track of the
  scene"*, and the overlay states that paused time is not counted. Say: *"That pause matters. If the clock
  kept running, an interruption would be recorded as hesitation — and hesitation is the thing we are
  measuring."*
- Answer one step with a **voice command** in Santali. If voice is not enrolled on the demo device, skip this
  and say so rather than pretending.

Result screen: point at the hesitation banner. *"Passed — and flagged. This worker knew the answers and paused
on a critical step. That flag is signed into the certificate."*

---

## 2:00 – 3:15 · The certificate, and why it verifies with nothing

Open **My certificates**, show the QR. Then say the thing that matters:

> "This QR is not a lookup key. The certificate itself is inside it — site, sequence number, module, score,
> median decision time, outcome flags, the hash of the previous record, and an Ed25519 signature. 216
> characters."

Scroll down to the text form and the record hash. *"An inspector can read that hash out over a radio."*

Now the strongest part. **Hand the phone to a second device — or use a second app instance — that has never
communicated with the first.** Still in airplane mode. Scan the code.

- Verdict appears: **Verified**, or **Signature valid, chain unknown on this device** on a fresh handset.
- Explain why there are seven verdicts rather than two: *"A fresh phone holding no copy of that site's chain is
  a completely different situation from a broken chain. Collapsing them into valid/invalid would either cry
  wolf on every new device or hide real tampering. Either way inspectors stop trusting the tool."*
- Type the worker number from the physical card into **Confirm identity**. It matches. *"The QR carries only a
  SHA-256 of the worker id. A dropped card identifies nobody, and the comparison is constant-time so the
  device cannot be used to enumerate ids."*

---

## 3:15 – 4:00 · Reporting a hazard from where the hazard is

Still offline. **Report a hazard** → pick a category from the pictogram grid (no reading required) → severity
→ record a ten-second voice note → send.

> "Saved and complete the instant it is stored. There is no GPS fix underground, which is why the location is
> a zone label rather than coordinates — and a report with neither is still accepted, because 'blocked exit,
> somewhere on this site' is worth having and refusing it would teach people to stop reporting."

---

## 4:00 – 4:30 · Turning the network on

Turn airplane mode **off**, on camera. Tap **Upload now**.

Point at the status text before it clears: *"Three records waiting to upload. Notice the wording — the
training is already recorded and signed. The queue is a delivery detail, not a failure. An app that reads like
it is broken when it is working correctly teaches people to distrust it."*

Then, if there is time, the part nobody else will have: open **Supervisor tools → Hand records to another
phone**. *"For records that cannot get out from underground at all. The supervisor's handset carries them to
the surface. It cannot alter anything — every certificate is signed and chain-linked — and it never appends to
another site's chain, because it stores them as finished records rather than as its own."*

---

## 4:30 – 5:30 · The dashboard, and the finding that matters

Switch to the dashboard. Overview page.

Do **not** narrate every tile. Go straight to the two things that are actually interesting:

**1. Statutorily valid but operationally stale.** Point at the count.

> "These workers are legally cleared to work today. Their certificates are current. Their readiness has
> decayed below the threshold where they would act reliably. This is the cohort a pass/fail record cannot show
> you, and it is the reason we report statutory validity and operational readiness as two separate numbers
> instead of one blended score."

**2. Hesitation risk.** Open the page.

> "Everybody on this list passed. Every one of them paused on a critical step. That is the finding — not who
> failed."

Then **Chain integrity**. Select `JH-JAM-021`.

> "This site's ledger has a break at sequence 4. We planted that in the seed data so you can see the detection
> work rather than take my word for it. Note that the record is still there — quarantined and stored, never
> deleted. Destroying tamper evidence would defeat the entire purpose of the chain, so there is no delete
> operation anywhere in this API."

---

## 5:30 – 6:00 · Close on the honest version

> "Two of the five modules ship as complete AR experiences — the two the problem statement asks for. The other
> three run through the identical assessment engine with generic AR placement, and the app says so on the
> module card rather than implying otherwise.
>
> Cross-device site anchors need a Google Cloud key we cannot commit to a public repository, so without one a
> site scan lasts for the session and the supervisor screen tells you that in plain words.
>
> `docs/CAPABILITY_MATRIX.md` lists what is built, what needs an external asset, and what is designed and not
> built. We would rather you read that than find it out by asking."

End there. Not on a logo.

---

## The buddy drill, if two handsets are available

Insert at **4:00**, about forty seconds, and trim the dashboard section:

Both phones on the buddy pairing screen. They find each other over Bluetooth — no internet, no shared Wi-Fi.
Neither is designated host; whichever notices the other first connects, and roles are decided by device id.

> "The buddy system is two people coordinating under stress. Simulating the partner as an NPC — which is the
> easier build — trains none of the skill being certified."

Run to the simulated collapse. Show the response window timing. Show that the resulting certificate carries
the buddy flag, and that a solo run of the same scenario is voided rather than quietly certified.

---

## Rules for the recording

**Show one failure path deliberately.** The camera-cover pause is the best one: it is fast, it is visibly
handled, and it demonstrates the design decision behind the measurement. A demo where nothing ever goes wrong
looks staged, because it is.

**Never say "blockchain".** It is a hash chain. Say hash chain.

**Never claim a number you have not verified.** Every figure in this script is checkable by running
`.\tools\verify-all.ps1`.

**If a feature is not working on the demo device, cut it and say why.** A thirty-second gap explained is worth
more than a feature faked. Assessors have seen faked features.

**Leave the airplane-mode indicator visible for the whole first half.** It is doing more persuasive work than
any narration could.
