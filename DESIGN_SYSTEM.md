# QUIK — Frontend Design System
> Reference this document for **all UI/visual changes**. Derived from the Pulse SMS design spec (`pulse-sms-with-tabs_2.html`).
> **Always consult `pulse-sms-with-tabs_2.html` as the primary visual reference** — this document is a structured summary of it. When the two conflict, the HTML file is the source of truth.

---

## 1. Typography

| Role | Family | Size | Weight | Letter-Spacing |
|---|---|---|---|---|
| Page / Screen Title | Geist | 24–30px | 500 | −0.03 to −0.04em |
| Section Sub-title | Geist | 15–16px | 500 | −0.02em |
| Body / Row Name | Geist | 13–14px | 400 | — |
| Body Preview / Sub | Geist | 11.5–13px | 300 | — |
| Labels / Badges | Geist | 10–12px | 400–500 | — |
| Monospace (time, amounts, codes) | Geist Mono | 9.5–13px | 300–500 | 0.02–0.08em |
| Wordmark / Section Caps | Geist | 10–12px | 500–600 | 0.09–0.18em (UPPERCASE) |

**Rules:**
- Titles use tight letter-spacing (`-0.03em` to `-0.04em`).
- Uppercase section labels use positive letter-spacing (`0.09–0.18em`).
- Use **Geist Mono** for: timestamps, account numbers, OTP codes, monetary amounts, monospace tags.
- Font weight: 300 for preview/secondary text · 400 for body · 500 for titles/active labels.

---

## 2. Color Tokens

### Theme-Adaptive Tokens

| Token | Light | Dark | Usage |
|---|---|---|---|
| `--bg` | `#f4f4f0` | `#0c0c0c` | Page / window background |
| `--surface` | `#ffffff` | `#141414` | Cards, headers, bottom bars |
| `--surface2` | `#f9f9f7` | `#111111` | Screen / list background |
| `--surface3` | `#f0f0ec` | `#1e1e1e` | Input bg, toggle bg, chip bg |
| `--border` | `rgba(0,0,0,0.08)` | `rgba(255,255,255,0.07)` | Subtle dividers, card outlines |
| `--border-md` | `rgba(0,0,0,0.12)` | `rgba(255,255,255,0.10)` | Buttons, stronger separators |
| `--text` | `#111110` | `#d4d4d4` | Primary text |
| `--text-2` | `#6b6b67` | `#888888` | Secondary / subdued text |
| `--text-3` | `#a8a8a4` | `#555555` | Placeholders, timestamps, section caps |
| `--accent` | `#1a56db` | `#e8e8e8` | CTAs, active states, FAB, send button |
| `--accent-bg` | `#e8f0fe` | `#232323` | Tinted bg for accent-colored icon chips |
| `--accent-fg` | `#1a3db5` | `#f0f0f0` | Text displayed on `--accent-bg` |
| `--bubble-bg` | `#f7f7f4` | `#0e0e0e` | Chat bubble area background |
| `--bubble-in-bg` | `#ffffff` | `#1a1a1a` | Incoming chat bubble fill |
| `--input-bg` | `#f7f7f4` | `#0e0e0e` | Compose / text input background |
| `--shadow-a` | `rgba(0,0,0,0.10)` | `rgba(0,0,0,0.45)` | Large drop shadow |
| `--shadow-b` | `rgba(0,0,0,0.06)` | `rgba(0,0,0,0.25)` | Small drop shadow |

### Semantic Colors

| Color | Hex | Light BG | Dark BG |
|---|---|---|---|
| Blue (info / link) | `#1a56db` | `#e8f0fe` | `#161f30` |
| Green (success / balance) | `#1a7f4b` | `#f0faf4` | `#101e14` |
| Red (error / destructive) | `#c0392b` | `#fff0f0` | `#1e0e0d` |
| Purple (premium / special) | `#7b1fa2` | `#f3e5f5` | `#160d1e` |
| Amber (warning / OTP) | `#b45309` | `#fffbeb` | `#1c1205` |

### Semantic Text Colors (Theme-Adjusted for Readability)

| Token | Light | Dark |
|---|---|---|
| `--green-text` | `#1a7f4b` | `#3dab6a` |
| `--red-text` | `#c0392b` | `#d9574d` |
| `--purple-text` | `#7b1fa2` | `#a06cd5` |
| `--amber-text` | `#b45309` | `#c98a20` |

> **Rule:** In dark mode, semantic text colors are brighter for readability. Always use `--*-text` tokens for text labels — never raw hex directly on dark backgrounds.

---

## 3. Border Radius Scale

| Context | Value |
|---|---|
| Small — inputs, chips, small buttons | `8px` (`--radius-sm`) |
| Default — cards, list groups, reminders | `12px` (`--radius`) |
| Large — feature cards | `18px` (`--radius-lg`) |
| Chat bubbles | `14px` (tail corner: `3px`) |
| FAB | `16px` |
| Pill — search bar, segment pickers, month switchers | `20–24px` |
| Circular — avatars, send btn, unread dot | `50%` |
| Toggle switch track | `9px` |
| Settings row icon chip | `7px` |
| Stat icon chip | `6px` |

---

## 4. Spacing & Layout

| Element | Value |
|---|---|
| Page padding | `48px 24px 64px` |
| Major section gap | `52px` |
| Card internal padding | `11–17px` |
| Row padding | `11–12px` vertical · `13–20px` horizontal |
| Gap between row icon and text | `9–11px` |
| Avatar sizes | `30–40px` |
| Toolbar icon button | `36×36px` |
| FAB | `50×50px` |
| Send button | `38×38px` |
| Tab bar height | ~`54px` · padding `10px 0 12px` |
| Tab icon area | `32×32px` (9px radius) |

---

## 5. Component Patterns

### Cards / List Items
```
background:    var(--surface)
border:        1px solid var(--border)
border-radius: 12px
padding:       10–12px vertical · 12–13px horizontal
hover/active:  background var(--surface3)
```

### Screen / Section Headers
```
background:    var(--surface)
border-bottom: 1px solid var(--border)
padding:       20px 20px 16px
title:         font-size 24px · font-weight 500 · letter-spacing -0.03em · color var(--text)
```

### Search Bar
```
background:    var(--surface3)
border:        1px solid var(--border)
border-radius: 8px
padding:       9px 12px
icon:          13×13px · stroke var(--text-3)
placeholder:   font-size 12px · color var(--text-3)
```

### Bottom Tab Bar
```
inactive: icon-bg transparent · icon stroke var(--text-3) · label color var(--text-3) weight 400
active:   icon-bg var(--accent-bg) · icon stroke var(--accent) · label color var(--accent) weight 500
```

### Segment / Filter Chips (Pill Row)
```
container:  background var(--surface3) · border 1px solid var(--border) · border-radius 20px · padding 3px
item-off:   color var(--text-2) · font-weight 400
item-on:    background var(--surface) · border 1px solid var(--border) · color var(--accent) · font-weight 500
```

### Message Tab (Inbox Filter Tabs)
```
inactive: color var(--text-2) · border-bottom 2px solid transparent · font-weight 400
active:   color var(--accent) · border-bottom 2px solid var(--accent) · font-weight 500
```

### Unread Message Row
```
light bg:  #fafcff
dark bg:   #191919
name:      font-weight 500 (read: 400)
preview:   font-weight 400 (read: 300)
dot:       7px circle · background var(--accent)
```

### Chat Bubbles
```
outgoing:  background var(--accent) · border-bottom-right-radius 3px · align-self flex-end
           text color: #fff (light) / #111 (dark) · font-weight 400
incoming:  background var(--bubble-in-bg) · border 1px solid var(--border) · border-bottom-left-radius 3px
           align-self flex-start · font-weight 300
timestamp: Geist Mono · 9.5px · color var(--text-3)
```

### FAB (Floating Action Button)
```
background:    var(--accent)
border-radius: 16px
size:          50×50px
box-shadow:    0 4px 16px rgba(0,0,0,0.28)
icon:          17×17px · stroke #fff (light) / #111 (dark) · stroke-width 1.75
```

### Send Button
```
background:    var(--accent)
border-radius: 50%
size:          38×38px
icon:          13×13px · stroke #fff (light) / #111 (dark)
```

### Icon Chips (Settings / Stat Icons)
```
size:          21–32px
border-radius: 6–8px
background:    semantic color bg  (e.g. var(--green-bg))
icon stroke:   matching semantic color (e.g. var(--green))
icon size:     12–15px · stroke-width 1.75
```

### Toggle Switch
```
track-on:  background var(--accent) · border-radius 9px · size 32×18px
track-off: background var(--surface3) · border 1px solid var(--border-md)
thumb:     14×14px circle · background #fff (light) / #111 (dark) · top 2px
           box-shadow: 0 1px 3px rgba(0,0,0,0.2) · transition 0.15s
on:  left 16px  ·  off: left 2px
```

### Value / Status Badge
```
font-size:     10px · font-weight 500 · font-family Geist Mono
color:         var(--text-2)
background:    var(--surface3)
border:        1px solid var(--border) · border-radius 6px
padding:       2px 8px
```

### Settings Row
```
layout:    flex · align-items center · gap 11px · padding 11px 13px
divider:   border-bottom 1px solid var(--border) (last-child: none)
icon:      32×32px · border-radius 7px · semantic color bg
label:     12.5px · font-weight 500 · color var(--text)
sublabel:  10.5px · font-weight 300 · color var(--text-2)
right:     val-badge or toggle or chevron (13px, stroke var(--text-3))
```

### Selection / Radio Option Picker
```
border:        1.5px solid var(--border-md) · border-radius 12px · padding 11px 12px
default bg:    var(--surface)
selected:      border-color var(--accent) · background var(--accent-bg)
radio circle:  14px · selected: fill var(--accent) + 5px white inner dot
```

### Info / Warning Banner
```
background:    var(--amber-bg)
border:        1px solid rgba(180,83,9,0.2)  [dark: rgba(201,138,32,0.2)]
border-radius: 8px
padding:       10px 12px · gap 9px
icon:          14×14px · stroke var(--amber-text) · stroke-width 1.75
text:          font-size 11px · color var(--amber-text) · line-height 1.5
```

### Profile Card
```
background:    var(--surface)
border:        1px solid var(--border) · border-radius 12px · padding 14px
avatar:        40×40px circle · background var(--accent-bg) · color var(--accent) · 15px weight 600
name:          14px · font-weight 500 · color var(--text)
sub:           11px · color var(--text-2)
```

---

## 6. Elevation & Shadows

| Level | Value |
|---|---|
| Phone frame / major cards | `0 24px 56px var(--shadow-a), 0 6px 16px var(--shadow-b)` |
| Popovers / dropdowns | `0 4px 16px var(--shadow-a)` |
| FAB | `0 4px 16px rgba(0,0,0,0.28)` |
| Toggle thumb | `0 1px 3px rgba(0,0,0,0.2)` |

---

## 7. Motion & Animation

| Pattern | Spec |
|---|---|
| Screen / card entrance | `opacity 0→1, translateY 18px→0 · 0.5s · cubic-bezier(0.22,1,0.36,1)` |
| Staggered list children | `+0.08s animation-delay per child` |
| Settings sub-page slide | `translateX ±100%→0 · 0.28s · cubic-bezier(0.22,1,0.36,1)` |
| Theme switch | `background / color · 0.3s` |
| Tab / chip / toggle micro-interactions | `0.15s` linear |

---

## 8. Iconography

| Property | Value |
|---|---|
| Style | Outlined — stroke only, **no fill** |
| `stroke-width` | `1.75` |
| `stroke-linecap` | `round` |
| `stroke-linejoin` | `round` |
| Size in toolbar / header buttons | `13–17px` |
| Size in list rows / feature cards | `14–15px` |
| Default color | `var(--text-2)` |
| Active / accent state | `var(--accent)` |
| Inside colored icon chip | matching semantic color |

---

## 9. Background Texture

Subtle dot-grid overlay over the page background:
```css
background-image: radial-gradient(circle, var(--dot-color) 1px, transparent 1px);
background-size: 24px 24px;
pointer-events: none;
```
`--dot-color`: light `rgba(0,0,0,0.06)` · dark `rgba(255,255,255,0.025)`

---

## 10. Dialog Patterns

All dialogs share a common **modal sheet** structure overlaid on a scrimmed, blurred background.

### Dialog Container
```
position:       centered overlay (absolute, translate -50% -50%)
width:          calc(100% - 48px)
background:     var(--surface)
border:         1px solid var(--border-md)
border-radius:  18px
box-shadow:     0 20px 60px rgba(0,0,0,0.25), 0 4px 16px rgba(0,0,0,0.15)
overflow:       hidden
```

### Scrim / Backdrop
```
background:     rgba(0,0,0,0.45)
backdrop-filter: blur(1px)
```

### Dialog Icon Chip
```
size:           48×48px
border-radius:  14px
background:     semantic color bg  (red-bg / green-bg / accent-bg)
icon:           22×22px · stroke matching semantic text color · stroke-width 1.75 · no fill
centered:       padding 22px 20px 0
```

### Dialog Title & Body
```
padding:        14px 20px 4px
text-align:     center
title:          font-size 16px · font-weight 600 · color var(--text) · letter-spacing -0.02em · margin-bottom 8px
body:           font-size 13px · color var(--text-2) · line-height 1.6 · font-weight 300
                highlighted span: color var(--text) · font-weight 500
```

### Dialog Divider
```
height:         1px
background:     var(--border)
margin:         16px 0 0
```

### Dialog Button Row
```
layout:         flex row · equal flex:1 children
padding:        14px 0  (each button)
text-align:     center
font-size:      14px
letter-spacing: -0.01em
separator:      border-right 1px solid var(--border) between buttons
```

### Dialog Variants

| Variant | Icon bg | Icon stroke | Confirm button color | Use when |
|---|---|---|---|---|
| **Destructive** | `var(--red-bg)` | `var(--red-text)` | `var(--red-text)` · weight 600 | Irreversible delete / remove |
| **Confirmation** | `var(--green-bg)` | `var(--green-text)` | `var(--accent)` · weight 600 | Positive action (unblock, enable) |
| **Informational** | `var(--accent-bg)` | `var(--accent)` | `var(--accent)` · weight 600 | Neutral info / permission request |

**Cancel button** (all variants): `color var(--text-2)` · `font-weight 500`

---

## 11. Android XML / Attribute Mapping

| Design Token | Android Equivalent |
|---|---|
| `--bg` | `colorBackground` / `windowBackground` |
| `--surface` | `colorSurface` |
| `--surface2` | `colorSurfaceVariant` (custom attr) |
| `--surface3` | custom `colorSurfaceElevated` attr |
| `--accent` | `colorPrimary` |
| `--accent-bg` | `colorPrimaryContainer` |
| `--accent-fg` | `colorOnPrimaryContainer` |
| `--text` | `textColorPrimary` |
| `--text-2` | `textColorSecondary` |
| `--text-3` | `textColorHint` |
| `--border` | custom `colorOutline` / divider drawable |
| `--radius` (12dp) | `cornerRadius` on `MaterialCardView` |
| FAB 16px radius | `shapeAppearanceMediumComponent` override |
| Outgoing bubble | custom drawable + `colorPrimary` fill |
| Incoming bubble | custom drawable + `colorSurface` fill + stroke |
| Geist / Geist Mono | Bundled font resource or fallback Roboto / RobotoMono |

---

*Last updated: May 2026 · Source: `pulse-sms-with-tabs_2.html`*

