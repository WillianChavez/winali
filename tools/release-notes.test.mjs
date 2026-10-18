import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { releaseNotes } from './release-notes.mjs';

const text = '## 0.1.1\n\nSecond.\n\n## 0.1.0\n\nFirst.\n';
test('extracts only the section of the released version', () => {
  assert.equal(releaseNotes(text, '0.1.1'), 'Second.');
  assert.equal(releaseNotes(text, '0.1.0'), 'First.');
});
test('uses a changelog without version sections whole', () => assert.equal(releaseNotes('Only notes.\n', '0.1.1'), 'Only notes.'));
test('the shipped changelog has a section for the current version', () => {
  const gradle = fs.readFileSync(new URL('../app/build.gradle.kts', import.meta.url), 'utf8');
  const version = gradle.match(/VERSION_NAME"\) \?: "([^"]+)"/)[1];
  assert.match(releaseNotes(fs.readFileSync(new URL('../CHANGELOG.md', import.meta.url), 'utf8'), version), /Ficha completa/);
});
