import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import crypto from 'node:crypto';

const catalog = JSON.parse(fs.readFileSync(new URL('../catalog/index.json', import.meta.url)));
const root = new URL('../', import.meta.url);
const png = relative => {
  const bytes = fs.readFileSync(new URL(relative, root));
  assert.equal(bytes.subarray(0, 8).toString('hex'), '89504e470d0a1a0a');
  assert.ok(bytes.length < 1_000_000, `${relative}: exceeds store download limit`);
  return { bytes, width: bytes.readUInt32BE(16), height: bytes.readUInt32BE(20) };
};

for (const id of ['winali', 'apk', 'focus', 'read', 'books']) {
  test(`${id}: versioned 512px store art and matching offline bitmap`, () => {
    const app = catalog.apps.find(app => app.id === id);
    assert.ok(app.icon.endsWith(`/docs/icons/${id}-3d.png`));
    const {width, height} = png(`docs/icons/${id}-3d.png`);
    assert.deepEqual([width, height], [512, 512]);
    const offline = png(`app/src/main/res/drawable-nodpi/catalog_icon_${id}.png`);
    assert.deepEqual([offline.width, offline.height], [256, 256]);
  });
}
test('Deck reference stays unchanged and is bundled offline', () => {
  assert.ok(catalog.apps.find(app => app.id === 'deck').icon.endsWith('/docs/icons/deck.png'));
  png('docs/icons/deck.png');
  png('app/src/main/res/drawable-nodpi/catalog_icon_deck.png');
});
test('launcher art matches Winali store art byte for byte', () => {
  const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
  assert.equal(sha(png('docs/icons/winali-3d.png').bytes), sha(png('app/src/main/res/drawable-nodpi/launcher_art.png').bytes));
});
test('all six apps have an offline store resource mapping', () => {
  const source = fs.readFileSync(new URL('app/src/main/java/io/github/willianchavez/winali/Screens.kt', root), 'utf8');
  for (const id of ['winali', 'apk', 'focus', 'read', 'books', 'deck']) assert.ok(source.includes(`"${id}" -> R.drawable.catalog_icon_${id}`));
});
