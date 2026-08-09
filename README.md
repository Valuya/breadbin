# Breadbin

A Commodore 64 emulator for Android, written in Kotlin and Jetpack Compose.

No ads, no tracking, no account, no in-app purchases — and no `INTERNET` permission in the
manifest, so the app cannot phone home even by accident.

It plays **tapes, disks and cartridges**.

## What it is

The emulator is written from scratch rather than wrapped around an existing one. That is a choice
with consequences, so here they are: it means one Kotlin codebase with no NDK, no native build and
no C fork to maintain, and it means the compatibility ceiling is lower than VICE's. What it does
run, it runs properly — the processor is exact, the video chip is drawn a cycle at a time, and the
tape is emulated as a signal rather than as a shortcut.

```
engine/   Pure Kotlin, no Android: the 6510, the VIC-II, the SID, the CIAs, the PLA,
          and the disk, tape and cartridge formats.
app/      The Android app: Compose UI, touch controls, audio, storage.
```

Keeping the emulator in a plain JVM module means the whole machine is unit-testable without a
device, which is how most of it was checked (see [Tests](#tests)).

## The ROMs

**Breadbin works the moment it is installed.** It ships the MEGA65 project's
[Open ROMs](https://github.com/MEGA65/open-roms) — a free, independently written replacement for
BASIC, the KERNAL and the character set, under the LGPL, containing none of Commodore's code. The
exact images in `app/src/main/assets/openroms/` are covered by the boot test: they reach a `READY.`
prompt, run BASIC and load a program off a tape.

**They cost you disks.** Open ROMs drives the serial bus itself rather than through the KERNAL
routines the emulated drive answers, so `.d64` images will not load under them. Tapes, cartridges
and BASIC all do. The app says so on the setup screen and again when you open a disk, rather than
leaving you watching a machine that sits there searching.

**Commodore's own ROMs lift that limit.** Breadbin will take them three ways — the file picker, a
link that opens [VICE's download page](https://vice-emu.sourceforge.io/) in your browser, or an
address you paste in for it to fetch — and works out which of the three each file is from its
contents. If you own a C64 you can read them off it instead.

What it will not do is come with an address already in it. They are still someone's copyright, and
an app that ships a pointer to a copy of them is the thing doing the distributing; one that fetches
from an address you typed is a tool you pointed somewhere. That fetch is also the only thing in the
app that ever opens a connection — no analytics, no update check, no ads, nothing phoning home.

## What works

**Video.** All the graphics modes — standard and multicolour text, standard and multicolour bitmap,
extended background colour — plus sprites with expansion, priority and both kinds of collision. The
display state machine is the one from Christian Bauer's VIC-II article: VC, VCBASE, RC, badlines,
and both border flip-flops, which means raster interrupts land on the right line, badlines steal
the right forty cycles, and a program that opens the border gets an open border.

Pixels are produced eight at a time, once per cycle, reading the registers as they stand. A raster
interrupt that changes the background colour halfway across a line changes it halfway across the
line.

**Sound.** Three voices with all four waveforms, ring modulation, hard sync, the 6581's own envelope
rate tables and its exponential decay, and a resonant filter. The emulation is paced by the audio
device rather than by the display, so it runs at the machine's speed — 50.12Hz on PAL — rather than
the phone's, and the sound has no seams in it.

**Tapes.** `.tap` files are played back as what they are: a recording of the gaps between edges on
the tape head, delivered to CIA 1's FLAG line at the right moments. The KERNAL's own loader decodes
them, which is why turbo loaders work too — this emulates the tape, not the loading.

`.t64` archives and `.prg` files are also read, and those are injected straight into memory.

**Disks.** `.d64` images, through an emulated drive that sits above the KERNAL rather than below it:
the KERNAL's serial routines are found via its jump table and patched, and answered from the image.
Loading is instant. Directories, wildcards, sub-directory syntax, saving, and scratching all work,
and a game that saves its high scores writes them back to the image file.

**Cartridges.** `.crt` files, including the banked boards most games shipped on: Ocean, System 3,
Dinamic, Magic Desk, Zaxxon, Fun Play, Super Games, Comal-80, Simons' BASIC, Final Cartridge III and
EasyFlash, plus plain 8K and 16K and raw binaries.

**The rest.** PAL and NTSC, an on-screen joystick for either port, the whole C64 keyboard, fast
forward, and opening a file from a file manager straight into the emulator.

## What does not work

These are real limits, not oversights:

- **Fast loaders on disk.** The emulated drive answers the KERNAL's serial routines. A game that
  bit-bangs the serial lines itself — most disk releases from about 1986, and every cracked intro —
  is talking to a 1541 that is not there, and will sit waiting. Those need a real drive emulation,
  with the 1541's own 6502 and DOS ROM, which this does not have. Tape and cartridge releases are
  unaffected, and so are disk releases that load through the KERNAL.
- **Open ROMs and disks.** The same limit, from the other side: Open ROMs drives the serial lines
  itself rather than through its own KERNAL routines, so disks do not load under it. Tapes,
  cartridges and everything else do.
- **Save states.** Not implemented.
- **Mid-line sprite movement.** Sprites are evaluated once per line. Sprite multiplexers — what
  games actually use — work; the handful of demo effects that move a sprite within a line do not.
- **The 1541's own quirks.** No real drive means no drive-side timing, no GCR, no fast loaders and
  no copy protection that reads the disk surface.
- **The SID filter** is a state variable filter, not a model of the analogue original. Filter sweeps
  sound right. They do not sound like your particular 6581.

## The real drive, unfinished

`engine/.../drive/` holds a genuine 1541: its own 6502, two 6522 VIAs, the stepper and the motor,
and the disk as a track of GCR bits going past a head rather than as a file of sectors. It exists
because a fast loader is a program that runs *on the drive's processor*, and nothing above the
KERNAL can ever serve one.

It is not finished, and it is not wired into the app. What works, and is covered by tests: the
drive boots its own DOS, the computer stops reporting it missing, the motor spins, the head seeks
to the directory track, and bytes come off the surface at the rate the surface goes past. What does
not: a transfer does not complete. The fault is somewhere in the two VIAs or in how the DOS's read
loop is being answered. `DriveBootTest` holds both the parts that pass and, marked ignored, the two
that say when it is done.

Using it needs the 1541's own DOS ROM as well as the computer's three, which is worth knowing before
counting on it: emulating the drive properly does not remove the ROM problem, it adds to it.

## Building

Needs a JDK 21+ and an Android SDK with platform 37.

```bash
./gradlew :app:assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease    # minified with R8
./gradlew test                    # the engine's unit tests
./gradlew :app:lintDebug
```

Minimum Android version: 7.0 (API 24).

## Tests

`./gradlew test` runs 57 of them. The ones that matter:

- **The processor passes Klaus Dormann's 6502 functional test** — every documented opcode,
  addressing mode, flag interaction and decimal-mode case, run to the success trap. The test binary
  is in `engine/src/test/resources`; it is [Klaus2m5's](https://github.com/Klaus2m5/6502_65C02_functional_tests),
  GPL-3.0, used unmodified as test data.
- **The PLA's truth table** is checked at the configurations programs actually use, including `$34`
  for 64K of RAM and `$35` for RAM with I/O, and that a write under a ROM lands in the RAM beneath.
- **The video chip is checked by its pixels**: a character cell in its colour on its background, the
  border around the display window, a blanked screen, a sprite over the background, and a
  sprite-to-background collision.
- **Badlines take the right amount of time** — between 3.5% and 7.5% of a frame, against the 5.1%
  that twenty-five badlines of forty cycles come to.
- **The virtual drive is driven by real 6502 code** calling through the KERNAL jump table, loading a
  file, a directory and a wildcard out of a `.d64`, and reporting FILE NOT FOUND and DEVICE NOT
  PRESENT the way the KERNAL expects to hear them.
- **Disk images survive a round trip**: sector chains, the BAM, replacing a file, scratching one,
  and more files than one directory sector holds.
- **GCR is exact**: every byte survives encoding and decoding, no encoded byte can be mistaken for a
  sync mark, and a whole track built for a real drive reads back with every sector intact and takes
  a fifth of a second to go past the head, as a disk turning at 300 rpm does.

### The boot test

`BootTest` switches a whole machine on and reads the screen, which needs a ROM set. Point
`BREADBIN_ROMS` at a directory holding one and it runs; without it those tests are skipped.

```bash
BREADBIN_ROMS=~/c64-roms ./gradlew test
```

With the Open ROMs set, this repository's boot test has been seen to:

- boot to a `READY.` prompt;
- run `PRINT"HELLO WORLD"` typed at that prompt and print it;
- **load a program off a tape**, pulse by pulse, through the KERNAL's own tape loader:
  `SEARCHING` → `FOUND HELLO` → `LOADING FROM $0801 TO $0817` → `READY.` → `RUN` → the program's
  output. The tape is generated by `TapWriter`, which writes the three pulse lengths and the two
  copies of each block exactly as a C64 wrote them.

The disk half of the boot test skips under Open ROMs for the reason given above.

## What has actually been run

The engine is covered by the tests above, and the whole machine has been booted, typed into and
loaded from tape as described. The Android app builds — debug and minified release — and passes
lint clean, but **it has not been run on a device or an emulator**: the touch controls, the audio
path and the screens have not been used by hand. That is the honest state of it.
