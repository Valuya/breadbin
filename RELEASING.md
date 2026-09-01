# Releasing Breadbin

Modelled on the process used for Crakinoku in `Valuya/sdk`, with the differences
an emulator brings — chiefly that this app holds the `INTERNET` permission and
that ROM files are somebody else's copyright.

## 1. Create the upload key, once

The key you sign with is the app's identity on Play forever. Lose it and you
cannot ship an update; leak it and somebody else can. Back it up somewhere that
survives this laptop, and do not put it in the repository — `.gitignore` already
refuses `*.jks`, `*.keystore` and `keystore.properties`, but that only helps if
the file stays where those rules apply.

```sh
keytool -genkeypair -v \
  -keystore breadbin-upload.jks \
  -alias breadbin \
  -keyalg RSA -keysize 4096 \
  -validity 10000 \
  -dname "CN=Valuya, O=Valuya, C=BE"
```

`-validity 10000` is roughly 27 years. Play requires the key to outlive the app;
a short validity strands you.

Then write `keystore.properties` in the repository root — beside
`settings.gradle.kts`, not in `app/`:

```properties
breadbinStoreFile=/absolute/path/to/breadbin-upload.jks
breadbinStorePassword=…
breadbinKeyAlias=breadbin
breadbinKeyPassword=…
```

On a build server set `BREADBIN_STORE_FILE`, `BREADBIN_STORE_PASSWORD`,
`BREADBIN_KEY_ALIAS` and `BREADBIN_KEY_PASSWORD` instead — the build reads
either, file first.

With neither configured the release build still runs and produces an *unsigned*
artefact, so a fresh clone builds for anyone. That is deliberate: an unsigned
bundle is obviously not shippable, whereas a debug-signed one looks fine right
up until Play rejects it.

## 2. Bump the version

`versionCode` must increase for every upload — Play rejects a repeat. Both live
in `app/build.gradle.kts`:

- `versionCode` — an integer, monotonic, never reused.
- `versionName` — what players see.

Do this **before** building. The version is compiled into the bundle, so bumping
it after step 3 leaves you holding an artefact with the previous `versionCode` —
which Play rejects on upload, or, worse, accepts as the release you did not mean
to ship.

## 3. Build the bundle

```sh
./gradlew clean test lintDebug bundleRelease
python3 tools/check_listing.py
```

The artefact is `app/build/outputs/bundle/release/app-release.aab`. Play wants
the `.aab`, not an `.apk`.

Confirm it really is signed before uploading:

```sh
jarsigner -verify app/build/outputs/bundle/release/app-release.aab
```

The engine tests skip anything needing Commodore's ROMs rather than failing, so
a clean run does not mean those ran. Point `BREADBIN_ROMS` at a directory holding
a BASIC, a KERNAL and a character ROM to include them, and do that before a
release — they are the tests that cover loading under the ROMs most people use.

## 4. Store listing

`store/listing-en.md` holds the name, short description and full description.
`tools/check_listing.py` checks them against Play's limits (30 / 80 / 4000
characters), which are enforced on upload and silently truncating in places.

The pictures are generated rather than checked in by hand:

- `python3 tools/make_store_art.py` draws `store/art/icon-512.png` and
  `store/art/feature-1024.png`. It renders the launcher icon's own vector
  drawables — an Android `<vector>`'s `pathData` is SVG path data — so the
  breadbin in the listing cannot drift from the one on the home screen. Needs
  Pillow and Inkscape.
- `tools/screenshots.sh` captures `store/screenshots/en/`, five 1080×1920 PNGs,
  from a booted emulator with `app-debug.apk` built. Play wants at least two
  and takes up to eight.

Two things about the screenshots that are decisions rather than mechanics:

- The running-machine picture is **Just BASIC** on the bundled Open ROMs, not a
  game. It is the one screenshot of a working C64 that raises no question about
  what is being distributed — see §6.
- The "Find games" picture searches for `gazette`, which returns Compute!'s
  Gazette type-ins. A broader query against the Internet Archive's C64
  collection turns up crack-group tags on commercial titles and, a little
  further down, profanity in a title. Either one in a store screenshot is
  exactly what a reviewer looking at an emulator is looking for.

Still to do outside the repository:

- The privacy policy hosted at a public URL. `store/privacy-policy.html` is
  ready; the Console will not accept a file.

## 5. What to tell the Console

This is where Breadbin differs from an app with no permissions, and the answers
have to match `store/privacy-policy.html` exactly — the Console form and the
policy are read together.

**Data safety.** No data is collected and none is shared: there is no account,
no analytics, no advertising identifier, no crash reporting, and no server of
ours for anything to go to. What must not be claimed is that the app cannot
reach the network — it holds `INTERNET`, and two user-initiated features use it:
fetching a ROM from an address, and searching the Internet Archive and
downloading a game. Those requests expose the device's IP address to those third
parties in the ordinary way, which the policy says plainly.

**Ads, purchases, subscriptions.** None.

**Target API.** New apps must target API 36 or higher. `targetSdk` is 36.

**Account requirements.** A *personal* developer account created after 13
November 2023 must run a closed test with at least 12 testers, opted in
continuously for 14 days, before it can apply for production access. An
*organization* account needs a D-U-N-S number, and the details D&B holds must
match what goes into the Console or verification fails.

## 6. The part that is specific to an emulator

Read this before submitting rather than after a rejection.

Emulators are permitted on Play. What is not permitted is distributing software
you have no right to distribute, and the two things worth being deliberate about
are:

- **What ships inside the bundle.** Only the MEGA65 project's Open ROMs, which
  are LGPL, and their licence travels with them in
  `app/src/main/assets/openroms/LICENSE`. No Commodore ROM and no game is in the
  repository or the bundle. That is the position to be able to state, and it is
  true today — keep it true.

- **The button that fetches Commodore's ROMs.** The app can download Commodore's
  firmware from the VICE project's public repository on one tap. Those files are
  still under copyright, and the app is what puts them on the device, even though
  it neither hosts nor bundles them. This is a deliberate choice made with the
  reasoning written down in `RomStore.COMMODORE_ROM_SET` and in the dialog the
  user confirms; it is also the single most likely thing to draw a reviewer's
  attention. If a submission is refused over it, the fallback is already built:
  remove the one-tap set and leave the address box and the file picker, which is
  where this started. Nothing else in the app depends on it.

The same reasoning applies to the game search, which downloads from the Internet
Archive's public collection. The app does not host anything and does not choose
what is in that collection.

## 7. Privacy policy

`store/privacy-policy.html` is a self-contained page, ready to host anywhere that
serves static files — the Console only needs a public URL.

Before publishing it, **replace the `CONTACT@EXAMPLE.COM` placeholder** with a
real address.

Unlike an app with no permissions, this page has to be kept honest as the app
changes: it enumerates the two features that use the network. Adding a third
means rewriting the page and revisiting the Data safety form in the same change,
not afterwards.

## 8. Uploading from CI

`.github/workflows/release.yml` builds and signs a bundle from a `v*` tag, or on
demand, and attaches it to the GitHub release. It needs four secrets:

- `BREADBIN_KEYSTORE_BASE64` — `base64 -w0 breadbin-upload.jks`
- `BREADBIN_STORE_PASSWORD`
- `BREADBIN_KEY_ALIAS`
- `BREADBIN_KEY_PASSWORD`

It stops with an error rather than producing an unsigned artefact when the
keystore secret is missing, and it checks the finished bundle really is signed
before uploading it anywhere.

It does **not** push to Play. That needs a service account and a separate
decision, and in any case Play refuses to accept the *first* release of an app
over its API — the first upload is by hand in the Console whatever the pipeline
looks like.

`.github/workflows/check.yml` runs the tests, lint, the listing check and a debug
APK on every pull request. It needs no secrets, so it works on a fork.
