import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const mainSource = await readFile(new URL('../src/main.js', import.meta.url), 'utf8')

test('keeps the V2 preview behind an exact build flag and lazy chunk boundary', () => {
  assert.match(mainSource, /import\.meta\.env\.VITE_CHAT_V2_PREVIEW === 'true'/)
  assert.match(mainSource, /import\('\.\/application\/v2Runtime'\)/)
  assert.doesNotMatch(mainSource, /import\s+[^\n]+\s+from\s+['"]\.\/application\/v2Runtime['"]/)
  assert.match(mainSource, /provide\(V2_RUNTIME_KEY, shallowReadonly\(v2Runtime\)\)/)
  assert.doesNotMatch(mainSource, /provide\(V2_RUNTIME_KEY, readonly\(v2Runtime\)\)/)
  assert.match(mainSource, /pageDisposed/)
  assert.match(mainSource, /runtime\.dispose\(\)/)
})
