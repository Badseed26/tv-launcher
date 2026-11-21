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
- Every push to main builds on GitHub Actions (`.github/workflows/build.yml`), except pushes that only touch `jobs/` or
  CLAUDE.md (21/11: those load fresh on the TV, and a build mid-update used to make `update` miss). versionCode = run number, so the
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
   command; the TV polls every 20 s (every 2 s for 10 min after a command, 21/11) and answers with a comment starting `TV:`. Commands: wake, open filer,
   close filer, status, log, reload, press <label>, code <n>, update, web remote, web remote new pin, check mail,
   test mail check, mail check on/off, mail check opens on/off, stay awake on/off, inbox pull <day>,
   lookup <terms; ...>, tv code (shows it), tv code <4 to 8 digits> (sets it), weather <town>, weather, weather off. The TV ignores anything over
   30 min old and deletes comments over 2 days old. The task description is the TV's heartbeat (last check-in,
   build, screen, Filer states, last mail check), rewritten every few minutes; the dashboard shows it.
2. **Phone remote page** (build 34 on, `RemoteServer.java`, `assets/remote.html`, `assets/remote-login.html`).
   Port 8765, answers only Tailscale addresses (100.64.0.0/10, fd7a:115c:a1e0::/48) and only after the PIN
   (5 wrong tries locks it 10 min, cookie lasts 30 days, last 5 phones). Send `web remote` on the Todoist remote
   to get the address and PIN. Live picture of the Filer's Outlook or AroFlo page (PixelCopy, falls back to
   drawing the WebView), taps, typing, keys, scroll, AroFlo code, Filer buttons. The chosen page shows on the TV
   while it's being used and hides again 2 min after.
   From 20/11 the picture is whatever the TV app has on screen at all times (home, all apps, Filer, Runner, and any
   screen we add later, as long as it calls `Screen.on/off` in onResume/onPause). Any other app or HDMI only gets its
   name in a text box, never a picture: HomeButtonService notes the front app's package name (nothing else) for that.
   Taps, Back, Enter and scroll work on any of our screens (`Screen.tap/key`; a Filer page still gets the Filer's
   own tap). The page reloads itself when the TV's build changes, so an old copy never runs against a new build.
3. **ADB** from AirHQ: `am broadcast` to SecretsReceiver (saves logins into the Keystore) and CodeReceiver (log,
   press, code). Both need the DUMP permission, so only the ADB shell can send them.

## How the pieces fit
- `MainActivity`: home screen (clock, Ethan work apps, Updates, All apps, Settings, favourite app tiles). `Ui`: shared views.
- `Lock` (21/11): Filer and Runner sit behind one "Ethan work apps" button on the home row. It asks for a code when
  pressed on the TV itself (number keys or an on screen pad, 5 wrong tries locks it 10 min, no asking again for 2 min
  after), then opens `WorkAppsActivity`, a menu screen with a tile per work app (Filer, Runner). Closing an app comes
  back to the menu, Back goes home, and the menu closes itself after 5 min untouched. Taps and keys from the phone page
  (`Screen.tap/key` mark `Lock.remoteAt`), the Todoist remote and auto runs never ask. The code is `tv code <digits>`
  on the Todoist remote, kept in the app's own storage; until one's set it's the phone page PIN. New work apps go in
  as tiles on that menu. The phone page also has Wake TV.
- `Weather` (21/11): live weather under the home screen's buttons (beside the clock pushed the top row off screen) from Open-Meteo (no key), every 15 min. The
  place is set with `weather <town>` on the Todoist remote (kept in app storage, never in this public repo),
  `weather` shows it, `weather off` hides it. Nothing shows until a place is set.
- `FilerActivity`: two hidden WebViews (Outlook and AroFlo) laid out at 1600x1000 pixels behind a dimmed,
  drifting Filer screen. Injects `gm-shim.js` (fakes Tampermonkey's GM_ API, storage in `GmStore`, HTTP in `Http`)
  wrapped around the filer script from ec-userscripts main (cached in filesDir, the new copy is used from the next
  open). `probe.js` reads each page's state every 8 s and fills the saved logins. Taps are real MotionEvents
  (AroFlo's Continue ignores script clicks). Closing the Filer shuts everything down. Auto runs (opened by the mail
  check) close themselves after about a minute of nothing to do and put the TV back to sleep if it was off.
- `RunnerActivity` (build 37 on): runs Ethan's other scripts on the TV, one job at a time, then goes back to how the TV
  was (Filer reopened, asleep again, or home). Never takes the TV off HDMI or another app.
  - The TV's pixel ratio is 2, so the Runner sizes its page at 1600x1000 times the density (true PC width, 20/11),
    shrinks it to fit and shows it full screen with a status strip while a job runs. Ethan's OK with any new TV job
    running full screen as long as the TV goes back to exactly how it was (Filer, home or asleep) after.
  - Scripts come from GitHub's API (not cached), raw file as fallback, so a push is live on the next run.
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
    any are queued. Verified 20/11: the first cloud inbox run (no PC) queued 4 notes and 2 site info lines at 10:58 and
    the open Filer had all 6 on their tasks by 11:03 (the filer does one note per 50 s, on purpose).
  - Lookup answers have AroFlo's button and icon text stripped (runner-af.js, 20/11), one note per line.
  - A missed-day pull reads the calendar from the pull's own day through to 5 weeks past today (build 46 on passes
    `day` in the job; older builds start from today).
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

## The TV api (21/11): the TV as Claude's computer
Ethan wants everything doable from a chat with the PC off, using the TV as the compute.
- Send `api {"id":"a1","do":"aroflo.task","args":{"jn":"9530"}}` on the Todoist remote. Answer: `TV: api a1 ok {...}`
  or `TV: api a1 err {...}` (no "queued" comment when it goes straight away, build 74 on). Build 76 on also rewrites the
  description of the "* TV answers" task (`6j44X9fp3rv7pPjX`, Api.BOARD) with the newest answers, newest first: read
  that with a direct task fetch, it's there straight away (Todoist's activity feed runs about a minute behind). Big results and every failed job's log come attached as a file. Optional: `"key"` (a write
  sent twice with the same key only runs once, the second gets the first answer back), `"dry":true`, `"branch":"test"`
  (job code from that branch), `args.timeout` (sec, default 180), `args.url` (start page, same site only).
- `Api.java`: queue (kept through restarts, one at a time), keys, logs. Jobs wait while the TV's on another app or HDMI
  (Ethan, 21/11) and go once it's off or on our screens. `tv.*` answers straight away: tv.ping, tv.queue,
  tv.cancel {id|all}, tv.log {id} (or the last 15), tv.shots {id} (that job's pictures, one comment each), tv.safe
  {off:true}, tv.wake.
- Job code lives in `jobs/` and loads fresh from GitHub on every request (no build needed): `jobs/lib.js` (shared
  helpers) then `jobs/<area>.js`. It runs again on every page the job lands on, with `ec.state` carried over
  (`ec.go(url)` saves it). If a newer copy throws a script error, the TV runs the job once more with the last copy
  that worked. Areas so far: aroflo (check, search, task, calendar, run, plus writes: note.add, note.edit, siteinfo.add, schedule.add, schedule.move), outlook (read only), web (tabs on CNW, Tradezone, Ambrose Tradies, AroFlo). `aroflo.run {js}` runs any bit of code on an
  AroFlo page and returns the result: use it to look at a page (and `ec.shot`) before writing or fixing a job.
- Presses: `ec.press(target, {expect, what})` only presses exactly one visible match, scrolls it into view, checks
  nothing's on top, real finger tap in its middle, then waits for `expect`. Pictures before and after go in the
  job's folder. Never pressed: send, submit, pay, delete, archive, reply, forward (ec.BLOCK). Admin and settings
  pages are blocked in lib and in the WebView. Writes must check `ec.dry`.
- Outlook (build 64 on): `jobs/outlook-hook.js` loads first on every Outlook job page (fresh from GitHub too, the job
  won't open Outlook without it). It blocks every mailbox write from the page, workers, beacons and graphql mutations, and
  notes the headers Outlook signs its mail calls with. `jobs/outlook.js` then asks Outlook's mail service directly with
  Find/Get calls only, never opening an email in the list (that would mark it read). Ops: outlook.check, outlook.search
  {q, folder, max}, outlook.read {id | q, n}, outlook.attachment {id | q, name | all} (files on the remote task),
  outlook.run {js} (has svc(action, body), search, getItem). If Outlook's signed out the job says so; the TV never types
  the Microsoft login.
  - Search (verified 21/11): Outlook's search words (QueryString) come back empty and a whole-mailbox (Deep) search is
    refused, so it's Contains restrictions on subject or body per folder ("all" = inbox and sent). Sender and date
    can't go in a restriction either, so from: and since: page through newest first (500 a folder tops).
  - Verified 21/11 on build 67-71: check 16 s, "Four Mile" over inbox and sent 32 hits, from:ambrose since:14d 4 hits,
    read with attachments list, a 698 KB PDF attachment came through whole on the remote task (32 s).
  - Not yet checked: reading an email that's unread (GetItem shouldn't change read status; check it on the first real one).
- `*.run` jobs: the TV puts the js on the page itself as `window.__ecRun` (build 68 on), because Outlook's page blocks
  turning text into code (CSP). A js that doesn't parse fails the job cleanly.
- Job code comes from the newest commit on the branch: one GitHub API call for the commit sha (kept 15 s), then the raw
  file at that sha (not rate limited, never stale). If GitHub's API is rate limited it falls back to the branch's raw file.
- Web tabs (build 72 on, `jobs/web.js`): Claude drives CNW, Tradezone, Ambrose Tradies Web and AroFlo pages one step at
  a time, like Chrome. web.open {url}, web.look, web.text, web.press {ref|text|sel}, web.fill {ref|label, value} or
  {fields}, web.key, web.scroll, web.wait, web.shot (picture posted after the answer), web.run {js}, web.close. Every
  answer has a look at the page: its text and each box, tick box, list and button with a ref number (refs stay the
  same while the tab's open). The page stays open as a tab after each web job (`args.keep` minutes, 5 by default, 15
  tops), so the next request carries on from there; then the TV goes back how it was. Ethan pressing Back or opening
  another app closes it. Never pressed on web pages: anything that sends, submits, pays, checks out, lodges, finalises,
  deletes or archives (wider than lib's list), and Enter isn't pressed in a form that has one of those. Passwords are
  never typed by job code.
- Saved logins (21/11): Ethan saves the CNW, Tradezone and Ambrose logins on the phone page (Saved logins box). They
  go in the Keystore (`Sites.java`, keys `site_<name>_user/pass`) and only the app reads them back: web.js spots a
  sign in page (or CNW's logged-out page), asks `ecApi.siteLogin(name)` and the TV fills it itself (one page or email
  then password, ticks remember me), at most 3 goes per site in 30 min. Then it goes back to the page it was after.
- Tradezone (21/11): Cloudflare's bot check came up on the TV and didn't clear by itself (it wanted a person). The job
  never ticks it: web.js waits 15 s, then fails saying so with the tab left open, so Ethan can tick it from the phone
  page. CNW signed itself in and searched fine (21/11, "rcbo 20a", 1457 results at Oxenford).
- Speed (21/11): a step on an open tab takes about 2 to 3 s on the TV; with the 2 s polling and the answers task the
  whole loop is about 5 to 10 s. A fresh page (web.open) is 10 to 45 s depending on the site.
- BridgeSelect is off on the TV (Ethan, 21/11, until he's shown Claude how): blocked in Sites.BLOCKED and the Runner.
- Ambrose admin (tradiesadmin.ambroseconstruct.com.au, Ethan 21/11) is allowed: it's where reports are found. It has its
  own saved login slot. `web.ambrose {po}` (with args.url .../PurchaseOrders) searches the PO list, opens the PO, then
  Bookings, then the report link, ending on Tradies Web as an open tab; it stops with a look when there's a choice.
  Blazor pages that hang (error bar, lost connection, never loads) get reloaded, 4 goes tops. Settings pages stay blocked.
- AroFlo documents and photos (21/11, same list as AroFlo's Documents tab, from AJAX_JSON_search_documents like the
  EC AroFlo photo grab script): `aroflo.files {jn, paths}` lists them (n, photo or doc, name, when, who, caption),
  `aroflo.file {jn, n | name | all, max}` sends them to Claude on the remote (photos shrunk to 1600 px). `web.upload
  {files:[{path, name, comment}], ref}` (or a step) puts AroFlo files into the open page's upload box: the app fetches
  each with the TV's AroFlo sign in (`ecApi.grab`, AroFlo only, 16 MB tops), batches of 4, and on Ambrose's image box
  waits for each batch then saves each card's comment (only when the cards line up exactly). Build 80 on.
- Ambrose admin, tested 22/11 on PO 20332055 (JN 9518): landing page LOG IN, then Auth0 (acg.au.auth0.com, email
  step then password step) signed in by itself. The PO row only opens with a script click (a real tap doesn't). The PO
  page's Bookings is a fold-out h5; `web.ambrose {po}` opens it and lists each booking (type, status, start, end,
  images, report, complete). `{po, booking: n, open: images | report | tradies}` opens that one: images opens the
  Bookings Images box (blazored-modal, file input, "minimum of 2 images", takes PDFs too) ready for web.upload; report
  and tradies are plain links, followed in the tab. Not yet done for real: an upload (no job needed one at the time).
- Tradies Web sign in (22/11): Ethan's is Trade Sign In (new). Admin and Tradies Web share one Auth0, so after admin
  the new button signs in as the admin email ("not linked"): web.js signs out, presses new again and fills the saved
  Tradies Web login (the Auth0 page title says Tradies Web or Tradies Admin). Redirects take up to a minute. Verified
  22/11: signed in and landed on the booking's safety page.
- NEVER on Tradies Web: the booking's site safety pages (sign in to site, PSEA, sign off). They're a real on-site record.
  web.js refuses presses and fills there, and sign on, sign off, acknowledge, attendance and clock in or out are on
  the press block list. (22/11: the old landing-page rule tried to press that page's SIGN IN; it was covered, so nothing
  happened. The LOG IN rule is now Ambrose admin's front page only.)
- `web.steps {steps:[{do:'press',ref:4},{do:'fill',fields:[...]},{do:'wait',text:'Saved'}]}` runs several actions in one
  request (look, press, fill, key, scroll, wait, sleep, shot), carrying on after a page change, stopping at the first
  failure with a look. Use it whenever the next few steps are known, it saves a round trip each.
- `RunnerActivity` runs api jobs (kind api) on its PC-size hidden page, full screen while they run, then back to how
  the TV was. With more jobs queued it stays open for them.
- Verified 21/11 on build 63: tv.ping in ~10 s, aroflo.search "Four Mile" 21 s (signed itself in), aroflo.task 9530
  18 s with the edited 50A note in it. Remote updates on build 62+ wake a standby TV for the install box and put it
  back to sleep (61 and older couldn't, the install just waited).
- `Crash.java`: any crash's trace is posted on the remote next start. Two crashes in 10 min = safe mode (api jobs
  paused, everything else runs). A new build or tv.safe {off:true} clears it.

## The relay and Claude's connector (22/11)
Faster than Todoist: Claude's custom MCP connector talks to a Cloudflare Worker (`relay/`, Durable Object "hub"), and
the TV keeps a long poll open to it (`Link.java`), so a tool call reaches the TV in well under a second.
- Deploys from `.github/workflows/relay.yml` on pushes to `relay/` (needs repo secrets CLOUDFLARE_API_TOKEN,
  CLOUDFLARE_ACCOUNT_ID, OWNER_PASSWORD). The Worker's address is in the deploy log; it's kept out of this repo.
- Sign in: OAuth with dynamic client registration; the sign in page takes Ethan's OWNER_PASSWORD (5 wrong tries locks
  it 15 min). Only Claude's own callback addresses can register. Tokens are stored hashed; access 24 h, refresh 60 days.
- Tools: tv_screen (picture), tv_tap, tv_type, tv_key, tv_scroll, tv_status, tv_filer, tv_view, tv_job (any api job,
  waits for the answer, pictures come back as images), tv_wait, tv_pictures, tv_pair.
- Pairing: tv_pair gives a line to send on the Todoist remote (`link <relay url> <code>`, 15 min, 5 tries). The TV swaps
  the code for its own key (Keystore). `link` says how the link's going, `link off` unpairs.
- The TV side only runs the phone page's own actions (`RemoteServer.local`, never logins or the PIN) and api jobs, so
  every block on those still applies. If the TV's not connected, the tools say so: use the Todoist remote.
- Tested 22/11 locally (wrangler dev, fake TV): OAuth, wrong password, bad redirect, PKCE, refresh, pairing, all tools,
  30 to 70 ms per call.

## AroFlo session limit
AroFlo caps how many devices can be signed in as one user. When Ethan signs in elsewhere, the TV gets the User
Session Limit page. Pressing CONTINUE on the TV does NOT sign his PC or phone out (Ethan, 21/11), so the Filer presses
it itself any time (at most every 10 min), day or night, and api jobs press it too (jobs/aroflo.js).

## Open items
- Deep dive on PDF grabs missing on the first go (follow up "Deep dive: why the TV filer misses PDFs"). Best long
  term fix: download attachments straight from Outlook with the kept sign-in instead of the preview.
- Watch for false wakes (2:53 am on 19/11 had nothing to file). Suspect: conversation times moving when someone
  replies in a builder thread.
- Inbox pulls on the TV (20/11): Outlook only loaded the newest 24 emails until the page ran full screen at true PC
  width and the pull waited longer for older mail (Inbox Pull 1.9.3: 3 goes of 10 sec). Wed 18/11 then matched the PC
  pull exactly (22 inbox, 9 sent). A couple of emails per day still say "pane check failed" but read fine.
- The Filer still runs at 800x500 to Outlook (folder list folded). Same density fix might help the PDF misses; not
  done without asking Ethan.
- Never tested: a real power cut (does the listener, helper and mail check come back after the TV boots to standby).
- Phone remote page is new (build 34 on): first real use 20/11. Pictures never change what the TV shows, only /api/view does (picking Outlook, AroFlo or Filer screen).
