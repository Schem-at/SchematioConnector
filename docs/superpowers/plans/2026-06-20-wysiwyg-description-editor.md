# True HTML WYSIWYG Description Editor (ImGui) — Implementation Plan

**Goal:** Replace the current markup-source + preview `RichTextWidget` with a true
inline WYSIWYG editor in ImGui: the user sees bold/italic/underline/strike (and
colors) rendered live as they type, selects text and clicks toolbar buttons to apply
styles, and the result round-trips to/from the website's HTML.

**Why it's non-trivial (read before starting):** ImGui has no rich-text widget and its
bundled font has no bold/italic glyphs. This is a from-scratch custom editor + a font
infrastructure change, and it REQUIRES in-game visual iteration (caret placement,
wrapping, selection highlight) — budget for several `:fabric:26.1:runClient` passes.

**Reuse:** the web-compat model already exists — `core/.../text/RichText.kt`
(`htmlToSpans`, `spansToHtml`, `markupToSpans`, `markupToHtml`) + `RichSpan`. The
target editor edits a `List<RichSpan>` (or an internal per-char style buffer) and
serializes via `spansToHtml` / loads via `htmlToSpans`. The deleted vanilla editor
`git show c3424f2^:fabric/src/client/kotlin/io/schemat/connector/fabric/client/ui/foundation/RichTextEditor.kt`
(732 lines) is the authoritative algorithm reference (RChar model, VLine wrapped-row
layout, caret/selection, mouse hit-test, keyboard nav, clipboard) — port its logic to
ImGui draw lists.

## Task 1: Bold/italic font atlases in ImGui
- Modify `imgui/ImGuiManager.kt` init: load Regular + **Bold** + **Italic** (+ BoldItalic)
  TTFs into the ImFontAtlas (e.g. `io.fonts.addFontFromFileTTF` / from bundled
  resources), keep `ImFont` handles. Pick fonts that ship with the mod (add under
  `resources/assets/schematioconnector/font/`).
- Expose the handles (e.g. `ImGuiFonts.regular/bold/italic/boldItalic`).
- Verify: text drawn with each handle renders distinct weights in-client (26.1 gate).

## Task 2: `RichTextEditorWidget` core model + layout
- Create `ui/imgui/RichTextEditorWidget.kt`. Internal model: per-char `(char, StyleFlags,
  color)` buffer (mirror vanilla `RChar`), plus `cursor: Int`, `selStart/selEnd`.
- Layout pass (mirror vanilla `rebuildLayout`/`VLine`): wrap to the widget width using
  `ImGui.calcTextSize` per run; produce visual lines with per-char x-offsets for caret
  hit-testing. Cache; rebuild on edit/resize.
- API: `setFromHtml(html)` (via `RichText.htmlToSpans` → fill buffer), `toHtml()` (buffer
  → spans → `RichText.spansToHtml`), `isEmpty()`, `isChanged()`, `clear()`.

## Task 3: Rendering (draw list)
- Render each visual line via `ImGui.getWindowDrawList().addText(font, size, pos, color, text)`
  switching `font` per run (regular/bold/italic/boldItalic from Task 1); draw underline /
  strike as `addLine`. Draw selection highlight (translucent accent rect) under selected
  runs; draw a blinking caret rect at the cursor's x/line.
- Use an `invisibleButton` of the content size as the interaction surface (so it captures
  drag + keeps focus), like `PreviewComposerPanel`.

## Task 4: Input (mouse + keyboard)
- Mouse: click → place caret (hit-test against cached x-offsets); drag → extend selection;
  double-click → word select. Gate on the invisibleButton being hovered/active.
- Keyboard: consume via `ImGui.getIO().getInputQueueCharacters()` for typed chars, and
  `ImGui.isKeyPressed(...)` for arrows/Home/End/Backspace/Delete/Enter and
  Ctrl+A/C/X/V (clipboard via `ImGui.getClipboardText`/`setClipboardText`). Typing inserts
  with the caret's current style (or the toolbar's pending style).
- NOTE: the input mixins already passthrough on `wantCaptureKeyboard`; ensure the editor
  sets keyboard focus (`ImGui.setKeyboardFocusHere` / active item) so `wantCaptureKeyboard`
  is true while editing.

## Task 5: Toolbar (apply to selection)
- B / I / U / S buttons + a color picker. Clicking toggles the style on the current
  selection (or sets the pending style for the next typed char when no selection) —
  mirror vanilla `RichDescriptionEditor.styleButton`. Highlight a button when the caret
  is inside a run with that style.

## Task 6: Swap in + verify
- Replace `RichTextWidget` usage in `UploadWizardPanel` and `SchematicEditPanel` with
  `RichTextEditorWidget` (`setFromHtml` on edit-open, `toHtml()` on submit).
- Keep `checkThemeDiscipline` green (widget lives in `ui/imgui/`, panels use only it).
- 26.1 manual gates: typing shows live styles; select+B works; caret/selection correct;
  HTML round-trips (edit an existing schematic, confirm description matches the website).
- Compile all 5 versions + theme + tests.

## Fallback if true WYSIWYG proves too costly
Selection-aware markup editor: keep `inputTextMultiline`, but make the toolbar wrap the
**current selection** (read `SelectionStart/End` via an `ImGuiInputTextCallback` with
`CallbackAlways`) instead of the buffer end. Not WYSIWYG, but fixes the broken toolbar.
The user prefers true WYSIWYG; use this only if Tasks 1–5 stall.
