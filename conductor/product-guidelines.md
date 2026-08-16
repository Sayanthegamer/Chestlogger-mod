# Product Guidelines: ChestLogger

## Voice & Tone
- **Server Administration Focus**: Direct, clear, unambiguous, and professional output designed for server operators and administrators.
- **Console & Chat Clarity**: Distinguish between informational, warning, and error outputs with clean standard prefixes (e.g. `[ChestLogger]`).

## Command UX & Visual Design
1. **Color & Hierarchy**:
   - Additions / Insertions: Distinct positive accent (Green / `+`).
   - Removals / Extractions: Distinct negative accent (Red / `-`).
   - Coordinates & Dimensions: Crisp neutral accent (Aqua / Gold).
   - Item Names & Quantities: Clear formatting with count multiplier.
2. **Inspection Display**:
   - Human-readable lines showing `[Timestamp] [Player/Automation] [Action (+/-)] [Qty]x [Item] @ [X, Y, Z] (Dimension)`.
   - Multi-page bounded pagination (`[< Prev] Page X of Y [Next >]`) without reading excessive historical byte ranges into memory.
3. **Safety & Confirmation Safeguards**:
   - Destructive commands (e.g., `/chestlog purge`) require explicit confirmation tokens with a countdown/timeout.
   - `/chestlog rollback` provides a dry-run / safety validation report before applying changes, reporting blocked items (if inventory is full or modified).
4. **Performance Transparency**:
   - `/chestlog stats` exposes queue depth, flush intervals, compression ratios, disk batch sizes, and buffer utilization in an easy-to-read tabular format.
