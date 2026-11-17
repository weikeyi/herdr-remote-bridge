import assert from 'node:assert/strict';
import { test } from 'vitest';
import { win32 } from 'node:path';
import { safeRelativePath } from '../../scripts/release-path.mjs';

test('release URL paths stay POSIX on Windows', () => {
  for (const path of ['builds/0.25.1-1-0123456789abcdef/index.html', 'assets/app-abc.js']) {
    assert.notEqual(win32.normalize(path), path, 'reproduces the native Windows validation bug');
    assert.equal(safeRelativePath(path), true);
    assert.equal(safeRelativePath(win32.normalize(path)), false);
  }
});

test('rejects absolute, noncanonical, drive and escaping paths', () => {
  for (const path of [undefined, null, 1, '', '.', '..', '/assets/a.js', '../a.js',
    'assets/../a.js', 'assets/./a.js', 'assets//a.js', 'C:/a.js', 'C:a.js',
    String.raw`assets\a.js`, String.raw`\\server\a.js`]) {
    assert.equal(safeRelativePath(path), false, String(path));
  }
});
