import assert from "node:assert/strict";
import test from "node:test";

import { modalLoopTarget } from "../src/ui/useModalKeyboardBoundary";

test("wraps forward and backward focus at modal boundaries", () => {
  const first = { id: "first" };
  const middle = { id: "middle" };
  const last = { id: "last" };
  const values = [first, middle, last];
  assert.equal(modalLoopTarget(values, last, false), "first");
  assert.equal(modalLoopTarget(values, first, true), "last");
  assert.equal(modalLoopTarget(values, middle, false), null);
  assert.equal(modalLoopTarget(values, middle, true), null);
})

test("recovers unknown focus and contains an empty modal", () => {
  const first = { id: "first" };
  const last = { id: "last" };
  assert.equal(modalLoopTarget([first, last], {}, false), "first");
  assert.equal(modalLoopTarget([first, last], {}, true), "last");
  assert.equal(modalLoopTarget([], {}, false), null);
})
