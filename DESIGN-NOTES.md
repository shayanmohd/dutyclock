# Dutyclock design notes

**Design read.** Reading this as: a professional hours instrument for EU and UK lorry, coach and van drivers, with a cab-dashboard and roadside hi-vis language (the analogue tachograph chart, reflective sign white, wet tarmac), leaning toward IBM Plex Sans Condensed plus Red Hat Text on a hi-vis chartreuse and wet tarmac palette.

**Dials.**
- DESIGN_VARIANCE 3: an instrument is predictable. Same layout grammar on every screen, left-aligned rows, the disc is the only round thing.
- MOTION_INTENSITY 2: nothing moves unless state changed. One first-run moment (the disc hand settles from midnight to now, 400 ms), a 150 ms crossfade on the centre numerals after a mode change. Both snap when LocalReducedMotion is true.
- VISUAL_DENSITY 6: many counters read at arm's length. Rows with dividers, not cards; tabular numerals everywhere.

**Tokens (from blueprint section 7).**

| token | role | light | dark |
|---|---|---|---|
| Kerb | background | #ECEFEA | #1F272B |
| Signboard | surfaces, dark disc plate | #F8F9F5 | #2A3439 |
| Tarmac | text, light disc plate, onAccent | #1D2529 | #E8ECE6 |
| Slate | secondary text, dividers | #55616A | #A3AEB4 |
| HiVis | the one accent | #B9D839 | #B9D839 |
| Stop | error role only | #B3261E | #EE8A7F |

HiVis never carries text on light Kerb (1.4:1). In light mode it is a fill under Tarmac text or a mark on the Tarmac disc plate. A breach always shows the word "Over" and an icon, never colour alone.

**Type.** IBM Plex Sans Condensed SemiBold and Bold for numerals, headings and buttons, with tabular figures. Red Hat Text Regular and Medium for body. Scale 56, 22, 20, 16, 14sp.

**Shape.** 4dp chips and fields, 8dp buttons and groups, 16dp sheet tops. Cards only for the Plan result and the catch-up prompt.

**The one memorable thing.** The Day Disc on Today: the analogue tachograph chart redrawn as a live instrument. All the boldness is spent there; everything around it stays quiet.

**Layout.** Bottom bar with Today, Week, Log and Plan below 600dp; a NavigationRail at 600dp and wider, where Today splits disc left and limits right. Records and Settings live as top-bar icons.
