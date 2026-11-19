# TV Launcher: working notes

Ethan's custom home screen for his EKO Android TV (Android 11, MediaTek, low RAM, about 1.3 GB, not rooted), plus a
Filer screen that runs his inbox filer (the EC Inbox Filer userscript from Badseed26/ec-userscripts) on the TV.
This repo is **public**: never put logins, PINs, tokens, IP addresses or the business name in it.

## Ground rules
- Modern clean look (blue and violet glows on dark). Not the orange and warm grey, those are for his work stuff only.
- No em dashes anywhere (code, comments, commits, notes). Plain, short Aussie English in comments.
- Remote control is for the Filer only, never Netflix or other apps on the TV.
- Outlook is read only (never send, move or change read status). Never touch AroFlo admin or settings areas.
- Commit as `-c user.name="Ethan Callaghan" -c user.email="Badseed26@users.noreply.github.com"`, push with
  `git -c push.negotiate=true push`.

## Building and updating
- Every push to main builds on GitHub Actions (`.github/workflows/build.yml`). versionCode = run number, so the
  build number is the Actions run number. The APK and `version.json` go on the release tagged `latest`.
- Check a build: `gh api repos/Badseed26/tv-launcher/actions/runs --jq '.workflow_runs[0] | [.run_number,.status,.conclusion]|@tsv'`
- Quick syntax check without the Android SDK:
  `javac -d /tmp/jc app/src/main/java/com/ethan/tvlauncher/*.java 2>&1 | grep error: | grep -vE "does not exist|cannot find symbol|method does not override|cannot be applied|incompatible types|is not abstract|cannot access"`
  (only real syntax errors survive the filter, so CI is the real compile).
- **Remote update (build 32 on):** send `update` to the TV remote (Todoist, see below) or press Update TV on the
  phone page or the dashboard. The app downloads the APK, opens the install prompt, and HomeButtonService (the
  accessibility helper) presses Install, only for our own app and only for 3 min after the command. The Filer
  reopens afterwards if it was open. Before build 32 an update needed Home > Updates > Install at the TV.
- Recovery if a build ever breaks the home screen (the stock launcher is disabled): over ADB (AirHQ on Ethan's
  phone, works away from home over Tailscale) run `pm enable com.google.android.tvlauncher`.

## Talking to the TV
1. **Todoist TV remote** (task `6j3H69pvQ9hQ86pX` in the job tracker's settings section). Add a comment with a
   command; the TV polls every 20 s and answers with a comment starting `TV:`. Commands: wake, open filer,
   close filer, status, log, reload, press <label>, code <n>, update, web remote, web remote new pin, check mail,
   test mail check, mail check on/off, mail check opens on/off, stay awake on/off, inbox pull <day>,
   lookup <terms; ...>. The TV ignores anything over
   30 min old and deletes comments over 2 days old. The task description is the TV's heartbeat (last check-in,
   build, screen, Filer states, last mail check), rewritten every few minutes; the dashboard shows it.
2. **Phone remote page** (build 34 on, `RemoteServer.java`, `assets/remote.html`, `assets/remote-login.html`).
   Port 8765, answers only Tailscale addresses (100.64.0.0/10, fd7a:115c:a1e0::/48) and only after the PIN
   (5 wrong tries locks it 10 min, cookie lasts 30 days, last 5 phones). Send `web remote` on the Todoist remote
   to get the address and PIN. Live picture of the Filer's Outlook or AroFlo page (PixelCopy, falls back to
   drawing the WebView), taps, typing, keys, scroll, AroFlo code, Filer buttons. The chosen page shows on the TV
   while it's being used and hides again 2 min after.
3. **ADB** from AirHQ: `am broadcast` to SecretsReceiver (saves logins into the Keystore) and CodeReceiver (log,
   press, code). Both need the DUMP permission, so only the ADB shell can send them.

## How the pieces fit
- `MainActivity`: home screen (clock, Filer, Updates, All apps, Settings, favourite app tiles). `Ui`: shared views.
- `FilerActivity`: two hidden WebViews (Outlook and AroFlo) laid out at 1600x1000 pixels behind a dimmed,
  drifting Filer screen. Injects `gm-shim.js` (fakes Tampermonkey's GM_ API, storage in `GmStore`, HTTP in `Http`)
  wrapped around the filer script from ec-userscripts main (cached in filesDir, the new copy is used from the next
  open). `probe.js` reads each page's state every 8 s and fills the saved logins. Taps are real MotionEvents
  (AroFlo's Continue ignores script clicks). Closing the Filer shuts everything down. Auto runs (opened by the mail
  check) close themselves after about a minute of nothing to do and put the TV back to sleep if it was off.
- `RunnerActivity` (build 37 on): runs Ethan's other scripts on the TV, one job at a time, then goes back to how the TV
  was (Filer reopened, asleep again, or home). Never takes the TV off HDMI or another app.
  - The TV's pixel ratio is 2, so the Runner sizes its page at 1600x1000 times the density (true PC width, 20/11).
    The Filer's pages are still 1600x1000 pixels, which Outlook sees as 800x500 (folder list folded).
  - Inbox pull: the EC Inbox Pull script (ec-userscripts src, cached in filesDir) in a hidden PC-size Outlook page with
    `#ec-inbox-run=<day>`, read only, unread put back. Days: today and the 6 before, like the dashboard. Then
    `assets/runner-af.js` (loaded from GitHub main each run, so a push to it is live without a build; the built-in
    copy is the fallback) reads 5 weeks of the AroFlo calendar into the bundle. The bundle goes on the TV remote task as
    a file (`ec-inbox-<day>.txt`, Todoist upload; split into comments if that fails). Start it from the Runner tile on
    the home screen, the phone page's Runner day picker, or `inbox pull <day>` on the Todoist remote.
  - Lookup: `lookup 9364; Norman Dr; po 20251598; client Smith; desc solar; notes 9364` answers AroFlo task searches
    and a task's notes and upcoming schedules (read only) as a TV: comment. For the inbox check when the PC's off.
  - AroFlo writes still go through the filer's queues (filer-note, filer-site labels on follow ups). ListenService opens
    the Filer for them every 5 min while the TV's off or home (once per 30 min), and an auto run doesn't close while
    any are queued.
- `ListenService`: foreground service with a partial wake lock, beat every 60 s. Runs the mail check every 5 min
  while the TV's off or on the home screen (never over an HDMI input or another app). Starts `RemoteServer`.
- `Peek`: the screen-off mail check. Replays Outlook's own mail list request with the kept sign-in (code only,
  about 2 s); when that sign-in has run out it loads an invisible Outlook page (accessibility overlay, alpha 0)
  just long enough to catch the list, with every mailbox write blocked. A builder email newer than the filer's
  newest (matched to the minute) from a sender on the filer's rules wakes the TV and runs the Filer, unless the PC
  holds the device lock.
- `Remote`: the Todoist remote. `Status`: heartbeat. `Alerts`: follow ups the TV raises and ticks off itself
  (Outlook signed out, AroFlo bumped with work waiting, AroFlo login failing).
- `Updater`, `UpdateReceiver`, `UpdatedReceiver`: self update. `HomeButtonService`: presses Home after an update,
  sleeps the TV after an auto run, presses Install during a remote update. `WakeActivity`: turns the screen on
  from standby. `BootReceiver`: starts the listener on boot. `TvLog`: the TV's own log (`log` when the Filer's closed).
- Device lock with the PC: Todoist task `6j3H69rH7Cj9858X`. Only one of PC or TV files at a time.

## AroFlo session limit
AroFlo caps how many devices can be signed in as one user. When Ethan (or anything on his PC) signs in, the TV gets
the User Session Limit page. The TV only takes AroFlo back when the filer has AroFlo jobs waiting: weeknights 6pm
to 6am and weekends it presses CONTINUE itself (every 20 min at most); in work hours it adds a follow up instead so
it never logs him out mid job. The filer script on the TV never stands down after a Continue (0.9.89).

## Open items
- Deep dive on PDF grabs missing on the first go (follow up "Deep dive: why the TV filer misses PDFs"). Best long
  term fix: download attachments straight from Outlook with the kept sign-in instead of the preview.
- Watch for false wakes (2:53 am on 19/11 had nothing to file). Suspect: conversation times moving when someone
  replies in a builder thread.
- Never tested: a real power cut (does the listener, helper and mail check come back after the TV boots to standby).
- Phone remote page is new (build 34 on): first real use 20/11. Pictures never change what the TV shows, only /api/view does (picking Outlook, AroFlo or Filer screen).
