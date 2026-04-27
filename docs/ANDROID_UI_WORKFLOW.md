# Android UI workflow (planning doc)

> Living planning doc.  Companion to [ANDROID_PLAN.md](ANDROID_PLAN.md).
> Tools + cycle for designing the Android keyboard UI without burning
> a designer's worth of time.

## The constraint

A mobile-keyboard IME is a unique UI surface:

- Lives in the bottom 1/3 to 1/2 of the screen
- Touch-first (Material recommends 48dp minimum touch target)
- Latency-critical -- key repaint must hit 60 / 90 / 120 fps
- Theming: light / dark / system + Material You dynamic color
- Portrait + landscape, plus tablets / foldables
- Accessibility: TalkBack, large-text mode
- "Looks right" matters less than "feels right under a thumb"

Most general design tools (Figma, Sketch) optimize for app screens
and don't give great affordances for keyboards.  And no amount of
high-fidelity mockup tells you whether a 8dp gutter feels too tight
on a 6.7" phone.  So: **prototype on the device fast, iterate on
the device fast.**

## Recommended workflow (3 tiers)

Pick the tier that matches the moment.

### Tier 1 -- Code-first (default for 80 % of iterations)

```
sketch on paper / whiteboard
       ↓
write Compose component
       ↓
@Preview in Android Studio  (light + dark + landscape variants)
       ↓
sideload APK to real phone
       ↓
screenshot → drop into Claude (this CLI) → ask for review
       ↓
apply Compose patch → loop
```

No external tool needed.  Fastest cycle.  Use this for everything
except first-pass exploration.

### Tier 2 -- AI-mockup-assisted (for first-pass + theme exploration)

```
brief in plain language → Claude Design  (research preview)
       ↓
3-5 visual variants generated
       ↓
pick one direction (color palette + key shape + candidate strip placement)
       ↓
hand the chosen mockup back to Tier 1 cycle
```

Use this when:
- Starting a new screen / component from scratch
- Comparing color palettes (light / dark / Material You)
- Considering layout variants (one-handed mode, split keyboard, etc.)
- Want non-developer to weigh in on visual direction

[Claude Design](https://www.anthropic.com/news/claude-design-anthropic-labs)
launched 2026-04, Anthropic Pro / Max / Team / Enterprise.  Exports
to PDF / URL / PPTX / Canva.

### Tier 3 -- Full Figma pipeline (skip unless team grows)

```
Figma (Material 3 design kit) → designer iterates → handoff via Figma Dev Mode
       ↓
manually port to Compose (or use Figma → Compose plugin)
```

Overkill for solo work.  Only worth it if a designer joins the
project or you're doing brand / marketing assets.

## Tools shortlist

| Layer | Tool | Why |
|---|---|---|
| **Mockup generation** | [Claude Design](https://www.anthropic.com/news/claude-design-anthropic-labs) | Natural-language → prototypes; iterate on colour / layout without writing code |
| **Color system** | [Material Theme Builder](https://material-foundation.github.io/material-theme-builder/) | Generates Compose colour tokens from a single seed colour; supports dynamic colour |
| **Component lib** | Material 3 for Compose (`androidx.compose.material3`) | Buttons / surfaces / typography that already follow Material 3; saves writing all the polish |
| **Live preview** | Compose `@Preview` + `@PreviewParameter` | In-IDE rendering at multiple sizes / themes; instant visual feedback |
| **Multi-device preview** | `@Preview(device = "spec:...")` family | Phone / tablet / foldable / landscape in one Preview run |
| **Real-device testing** | ADB sideload + USB-debug | The only way to validate touch feel; emulator lies about latency |
| **Inspiration** | [Mobbin](https://mobbin.com), [Material 3 gallery](https://m3.material.io) | Pattern library for mobile UI |
| **Direct competitors** | Gboard, SwiftKey, Mozc Android, Naver SmartBoard | Install side-by-side, screenshot, study key sizing / colours / candidate strip |
| **Visual review** | Claude (this CLI) with screenshot attachments | "Backspace looks too narrow vs the others" → Compose patch |
| **Accessibility** | Accessibility Scanner app + TalkBack | Run on device; checks contrast / target size automatically |

## What to design in what order

Going in this order minimizes rework -- big foundational decisions
first, polish last.

1. **Color tokens** (1 cycle)
   Material Theme Builder → seed colour → export Compose code.
   Defines every other surface's look.

2. **Key layout grid** (2-3 cycles)
   How wide is each key, what's the gutter, where do special keys
   (Shift / Backspace / Space / Enter / Switch-IME) sit.  Use
   Compose Preview to render the empty grid first.

3. **Single key states** (2 cycles)
   Default / pressed / disabled / shifted.  Get the visual feedback
   right before adding more keys.

4. **Candidate strip** (3-5 cycles)
   Above the keyboard, scrolls horizontally, shows kanji candidates.
   Most differentiated component; needs the most iteration.

5. **Theme variants** (1 cycle)
   Light / dark / Material You dynamic.  Compose handles most of
   this with theme parameters; just verify in `@Preview`.

6. **Landscape + tablet** (1 cycle)
   `@Preview(device = "spec:width=...")`.  Decide: same layout
   stretched, or different layout entirely.

7. **Settings activity** (2-3 cycles)
   Material 3 list components.  Less critical than the keyboard
   itself.

## Pitfalls

- **Designing in Figma without testing on device.**  Keyboards
  FEEL different than they look.  Don't trust pixel-perfect
  mockups; trust your thumb.
- **Over-investing before validating one-handed typing.**  If the
  keyboard isn't comfortable for one-handed use, it doesn't matter
  what colour the keys are.  Validate that early.
- **Forgetting landscape.**  ~10 % of usage.  Build in `@Preview`
  from day 1, not as an afterthought.
- **Forgetting accessibility.**  Run Accessibility Scanner once a
  milestone.  Easier to fix incrementally than at the end.
- **Custom drawing instead of Material 3.**  Tempting for
  "uniqueness" but loses dark-mode and dynamic-colour for free.
  Override sparingly.
- **Animating everything.**  Keyboards are pressed thousands of
  times.  Subtle haptic + colour state change beats elaborate
  spring animations.

## Concrete first-week plan

Day-by-day, leveraging the tiers above:

- **Day 1** -- Tier 2: Claude Design 5-10 keyboard mockups, pick a
  direction.  Tier 1: skeleton Compose project, render an empty
  4×11 grid in Preview.
- **Day 2** -- Tier 1: render all 32 jamo keys with proper labels;
  wire up to a no-op InputConnection.  Sideload, type, see
  characters appear (raw jamo, no kana yet).
- **Day 3** -- Tier 1: add HangulComposer + BatchimLookup logic
  (ports from Windows code), commit kana to text fields.  This is
  M1's "it works" milestone.
- **Day 4** -- Tier 1: theme polish.  Material You / dark mode /
  colour token tweaks.  Preview every variant.
- **Day 5** -- Tier 2: Claude Design pass on candidate-strip
  variants for M2.  Pick a direction so M2 implementation can
  start with intent.

## Visual-review template (using this CLI Claude)

When sharing a screenshot for review, structure the ask:

```
Screenshot attached.  Layout intent: <what you wanted>.  
Specific feedback wanted on:
- <thing 1>  (e.g. "key spacing on the bottom row")
- <thing 2>  (e.g. "candidate strip height vs visibility")

Compose source: <paste the relevant component>
```

Beats "what do you think" -- gets a code patch back instead of
adjectives.
