import assert from 'node:assert/strict'
import test from 'node:test'

import { calculateVirtualWindow } from '../src/ui/virtualWindow.js'

test('renders all short conversations without spacers', () => {
  assert.deepEqual(calculateVirtualWindow({
    heights: [40, 60, 80], scrollTop: 0, viewportHeight: 100, threshold: 10
  }), { start: 0, end: 3, top: 0, bottom: 0, totalHeight: 180 })
})

test('windows long variable-height conversations with exact spacer totals', () => {
  const result = calculateVirtualWindow({
    heights: [50, 100, 50, 200, 50, 100],
    scrollTop: 210,
    viewportHeight: 100,
    overscan: 0,
    threshold: 3
  })
  assert.deepEqual(result, {
    start: 3,
    end: 4,
    top: 200,
    bottom: 150,
    totalHeight: 550
  })
  assert.equal(result.top + 200 + result.bottom, result.totalHeight)
})

test('clamps invalid dimensions and keeps an item rendered near the end', () => {
  const result = calculateVirtualWindow({
    heights: Array(100).fill(20),
    scrollTop: 100000,
    viewportHeight: -1,
    overscan: 0,
    threshold: 10
  })
  assert.equal(result.start, 99)
  assert.equal(result.end, 100)
  assert.equal(result.top, 1980)
  assert.equal(result.bottom, 0)
})

test('bounds the rendered slice independently of retained conversation length', () => {
  const result = calculateVirtualWindow({
    heights: Array(500).fill(80),
    scrollTop: 20000,
    viewportHeight: 800,
    overscan: 700,
    threshold: 80
  })
  assert.ok(result.end - result.start <= 29)
  assert.ok(result.start > 0)
  assert.ok(result.end < 500)
})
