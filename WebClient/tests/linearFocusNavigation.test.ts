import assert from "node:assert/strict";
import test from "node:test";

import { nextWrappingFocusIndex } from "../src/ui/linearFocusNavigation";

test("moves through a linear focus collection and wraps at both ends", () => {
  assert.equal(nextWrappingFocusIndex("ArrowDown", 0, 3), 1);
  assert.equal(nextWrappingFocusIndex("ArrowDown", 2, 3), 0);
  assert.equal(nextWrappingFocusIndex("ArrowUp", 0, 3), 2);
  assert.equal(nextWrappingFocusIndex("ArrowUp", 2, 3), 1);
});

test("resolves boundary keys and contains invalid collections", () => {
  assert.equal(nextWrappingFocusIndex("Home", 2, 3), 0);
  assert.equal(nextWrappingFocusIndex("End", 0, 3), 2);
  assert.equal(nextWrappingFocusIndex("ArrowDown", -1, 3), 0);
  assert.equal(nextWrappingFocusIndex("ArrowUp", -1, 3), 2);
  assert.equal(nextWrappingFocusIndex("Home", 0, 0), null);
  assert.equal(nextWrappingFocusIndex("End", 0, Number.NaN), null);
});
