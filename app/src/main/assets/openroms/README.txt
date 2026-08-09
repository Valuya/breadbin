Open ROMs
=========

basic.rom, kernal.rom and chargen.rom in this directory are the MEGA65 project's
Open ROMs: a free, independently written replacement for the Commodore 64's BASIC,
KERNAL and character set. They are not Commodore's ROMs and contain none of
Commodore's code.

  Copyright Paul Gardner-Stephen, 2019
  Copyright Roman Standzikowski (FeralChild64), 2019-2021

They are licensed under the GNU Lesser General Public License, version 3 or later;
the full text is in LICENSE beside this file. Breadbin loads them as data at run
time and does not incorporate them into its own code.

Source, and the means to rebuild these images:

  https://github.com/MEGA65/open-roms

They are shipped so that Breadbin works the moment it is installed. They are not
as compatible as Commodore's own ROMs — in particular Open ROMs drives the serial
bus itself rather than through its KERNAL routines, so disk images will not load
under them. Supplying Commodore's three ROMs in the app's settings replaces these
and lifts that limit.
