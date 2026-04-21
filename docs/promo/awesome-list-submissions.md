# Awesome-list submission drafts

Copy-pasteable entries for submitting KorJpnIme to curated "awesome"
lists on GitHub.  Each target below includes:

- the list repo URL
- the submission guidelines link (where to find their format rules)
- a draft entry matching that list's house style
- a suggested PR title

Submit one PR per list.  Follow each list's contribution guide --
some want alphabetic order, some want a category-specific sort, some
want a one-line test run first.

---

## 1. awesome-windows

- Repo:  <https://github.com/Awesome-Windows/Awesome>
- Contributing: read their README top to find the "Keyboard" / "Text
  input" / "Productivity" section (varies by edition).
- Style: `- [Name](link) - Short description ending with period.`

**Draft entry** (put under a section like `Typing` or `Keyboard`):

```markdown
- [KorJpnIme](https://github.com/ccy5123/kor_based_jap) - Type Japanese on a Korean 2-beolsik keyboard layout; TSF IME with Mozc-quality kanji conversion.
```

**PR title**:
```
Add KorJpnIme (Japanese input on Korean keyboard, TSF IME)
```

---

## 2. awesome-korean

- Repo:  <https://github.com/jeongukjae/awesome-korean-nlp>
  (there is no single "awesome-korean" -- this is the closest match
  for a Korean-language-related tool.  Also worth trying
  <https://github.com/datajohnson/awesome-korean-nlp> if it's more
  active at submission time.)
- Style: table or list, see each list's existing entries.

**Draft entry**:

```markdown
- [KorJpnIme](https://github.com/ccy5123/kor_based_jap) - Windows IME that maps Korean 2-beolsik keystrokes to Japanese kana with Mozc-based kanji conversion, for Korean speakers learning / typing Japanese.
```

**PR title**:
```
Add KorJpnIme: Korean-keyboard Japanese IME
```

---

## 3. awesome-japanese

- Repo:  <https://github.com/aliceoq/awesome-japanese>
  (also consider <https://github.com/tadashi-aikawa/awesome-japanese>
  and <https://github.com/harajuku-tech-hour/awesome-japanese-dev>.)
- Style: check their README for sorting rules; most are category
  based (keyboards / IMEs / tools).

**Draft entry**:

```markdown
- [KorJpnIme](https://github.com/ccy5123/kor_based_jap) - Japanese input method for Windows that accepts Korean 2-beolsik keystrokes, then converts through a Mozc-quality viterbi segmentation engine.
```

**PR title**:
```
Add KorJpnIme (Japanese IME with Korean-keyboard input)
```

---

## 4. awesome-ime  /  awesome-input-method

- No single canonical list today (checked).  If one exists by the
  time you're submitting, search `awesome ime github` on GitHub
  search.
- Alternative: <https://github.com/search?q=awesome+ime&type=repositories>
  -- pick the most starred + actively maintained one.

**Draft entry** (generic markdown-list form):

```markdown
- [KorJpnIme](https://github.com/ccy5123/kor_based_jap) — TSF-based Windows IME: type Japanese on a Korean 2-beolsik keyboard, with Mozc OSS dictionary + top-K viterbi segmentation.
```

---

## 5. awesome-opensource  /  general

These tend to be huge and less curated, with lower per-click ROI.
Skip unless there's a specific category that fits well, or save for
when the project has more stars + real-world usage reports.

---

## General submission tips

1. **Read CONTRIBUTING.md on the target repo first.**  Many lists
   have strict formatting (alphabetical / no emojis / one-line
   descriptions / specific punctuation at the end).
2. **Link to the v1.0.0 release tag, not main**, when the list
   values stability.  Pattern:
   `https://github.com/ccy5123/kor_based_jap/tree/v1.0.0`.
3. **Don't add the project twice** in two sections of the same list.
4. **Reply politely to reviewer feedback.**  One round of formatting
   fixes is normal.
5. **Don't open awesome-* PRs on lists that haven't merged anything
   in 12+ months** -- the maintainer is likely inactive; your PR
   will rot.  Check the merge history first.

## Links to bookmark

- [GitHub Search: "awesome korean"](https://github.com/search?q=awesome+korean&type=repositories)
- [GitHub Search: "awesome japanese"](https://github.com/search?q=awesome+japanese&type=repositories)
- [GitHub Search: "awesome ime"](https://github.com/search?q=awesome+ime&type=repositories)
- [GitHub Search: "awesome windows"](https://github.com/search?q=awesome+windows&type=repositories)
