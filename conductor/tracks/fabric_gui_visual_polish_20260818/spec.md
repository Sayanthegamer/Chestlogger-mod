# Specification: Fabric GUI Visual Polish & Column Alignment

## 1. Overview
Fix text overlap and formatting bugs in the client-side Item Provenance Screen (`ChestLogProvenanceScreen.java`) and add structured container panel styling, dark backdrop, field labels, and aligned action buttons to the Configuration Screen (`ChestLogConfigScreen.java`).

## 2. Functional & Technical Requirements

### 2.1 Provenance Screen Formatting & Layout Overhaul (`ChestLogProvenanceScreen.java`)
- **Friendly Action Names**: Transform raw enum names into clean, readable text:
  - `SHIFT_CLICK_EXTRACT` $\implies$ `Shift Extract`
  - `SHIFT_CLICK_INSERT` $\implies$ `Shift Insert`
  - `HOPPER_EXTRACT` $\implies$ `Hopper Out`
  - `HOPPER_INSERT` $\implies$ `Hopper In`
  - `DROP_FROM_SLOT` $\implies$ `Drop`
  - `DOUBLE_CLICK_COLLECT` $\implies$ `Collect`
  - `HOTBAR_SWAP` $\implies$ `Hotbar Swap`
- **Clean Confidence Badges**: Format confidence badges concisely:
  - `EXACT_LINKAGE` $\implies$ `§a[EXACT]`
  - `HIGH_CONFIDENCE` $\implies$ `§e[HIGH]`
  - `PROBABLE` $\implies$ `§6[PROB]`
- **Proportional Column Layout**:
  - Re-balance column X offsets so `Step`, `Action`, `Confidence`, `Actor`, `Item & Delta`, and `Time & Pos` never collide.
  - Implement text truncation and ellipsis with hover tooltips for overflow values.

### 2.2 Configuration Screen Panel & Visual Styling (`ChestLogConfigScreen.java`)
- **Dark Container Panel**: Add outer border (`0xFF000000`) and structured dark container fill (`0xEE16161C`) with section header bars, matching the visual theme of `ChestLogScreen`.
- **Field Labels**: Render clear descriptive labels above/beside text inputs:
  - `Discord Webhook URL:` above webhook `EditBox`
  - `Bot Username:` above username `EditBox`
  - `Web Server Host / Port:` above web settings `EditBox`
- **Section Headers & Dividers**: Categorized section headers for each active tab.
- **Action Buttons**: Centered `[Save & Apply]` and `[Cancel]` buttons in the panel footer.

## 3. Acceptance Criteria
- [ ] No overlapping text anywhere in `ChestLogProvenanceScreen` under any screen resolution or action type.
- [ ] `ChestLogConfigScreen` renders a framed dark container panel with clear field labels.
- [ ] Full unit test suite passes with zero regressions.
