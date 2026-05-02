/**
 * collapse-tools — OpenCode server plugin
 *
 * Collapses every tool call output into a compact one-liner with
 * a foldable <details> block containing the full output.
 */

// ── helpers ──────────────────────────────────────────────────────────────────

const ICONS = {
  bash:      "🖥️ ",
  read:      "📖",
  write:     "✏️ ",
  edit:      "✏️ ",
  glob:      "🔍",
  grep:      "🔍",
  webfetch:  "🌐",
  todowrite: "📋",
  task:      "🤖",
  default:   "🔧",
};

function icon(toolName) {
  const key = toolName.toLowerCase().replace(/[^a-z]/g, "");
  return ICONS[key] ?? ICONS.default;
}

function compactTitle(toolName, args, lineCount) {
  const ic   = icon(toolName);
  const name = toolName.split(".").pop();

  let hint = "";
  if (args && typeof args === "object") {
    const raw = args.command ?? args.filePath ?? args.path
             ?? args.pattern ?? args.url ?? args.description
             ?? args.query   ?? args.prompt ?? "";
    hint = typeof raw === "string" ? raw : JSON.stringify(raw);
    if (hint.length > 60) hint = hint.slice(0, 57) + "…";
  }

  const hintPart  = hint      ? `  · ${hint}`        : "";
  const linePart  = lineCount ? `  [${lineCount}L]`  : "";
  return `${ic} ${name}${hintPart}${linePart}`;
}

function collapsed(title, full) {
  const body = (full ?? "").trimEnd();
  return `<details>\n<summary>${title}</summary>\n\n\`\`\`\n${body}\n\`\`\`\n</details>`;
}

// ── plugin ────────────────────────────────────────────────────────────────────

// OpenCode expects: default export = async function(PluginInput, options?) => Hooks
export default async function collapseTools(_input, _options) {
  return {
    async "tool.execute.after"(input, output) {
      const raw   = output.output ?? "";
      const lines = raw.split("\n").filter(Boolean).length;
      const title = compactTitle(input.tool, input.args, lines);

      output.title  = title;
      output.output = collapsed(title, raw);
    },
  };
}
