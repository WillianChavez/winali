// CHANGELOG sections are headed `## <version>`. The notes for a release are its own section;
// a changelog without such a section is used whole.
export function releaseNotes(text, version) {
  const lines = text.split(/\r?\n/);
  const start = lines.findIndex(line => line.trim() === `## ${version}`);
  if (start < 0) return text.trim();
  const end = lines.findIndex((line, i) => i > start && line.startsWith('## '));
  return lines.slice(start + 1, end < 0 ? undefined : end).join('\n').trim();
}
