import { test } from "node:test";
import assert from "node:assert/strict";
import { validateCommandRequest, isValidCommand, VALID_COMMANDS } from "./validation";

test("accepts a valid START_RECORDING request", () => {
  const result = validateCommandRequest({ deviceId: "uid-1", command: "START_RECORDING" });
  assert.equal(result.ok, true);
  if (result.ok) {
    assert.equal(result.value.deviceId, "uid-1");
    assert.equal(result.value.command, "START_RECORDING");
    assert.equal(result.value.requestId, undefined);
  }
});

test("accepts a valid STOP_RECORDING request with requestId", () => {
  const result = validateCommandRequest({
    deviceId: "  uid-2 ",
    command: "STOP_RECORDING",
    requestId: " req-9 ",
  });
  assert.equal(result.ok, true);
  if (result.ok) {
    assert.equal(result.value.deviceId, "uid-2");
    assert.equal(result.value.requestId, "req-9");
  }
});

test("rejects a missing deviceId", () => {
  const result = validateCommandRequest({ command: "START_RECORDING" });
  assert.equal(result.ok, false);
});

test("rejects a blank deviceId", () => {
  const result = validateCommandRequest({ deviceId: "   ", command: "START_RECORDING" });
  assert.equal(result.ok, false);
});

test("rejects an unknown command", () => {
  const result = validateCommandRequest({ deviceId: "uid-1", command: "PAUSE" });
  assert.equal(result.ok, false);
});

test("rejects a non-string requestId", () => {
  const result = validateCommandRequest({ deviceId: "uid-1", command: "START_RECORDING", requestId: 42 });
  assert.equal(result.ok, false);
});

test("rejects a non-object body", () => {
  assert.equal(validateCommandRequest(null).ok, false);
  assert.equal(validateCommandRequest("nope").ok, false);
});

test("isValidCommand matches the shared wire values", () => {
  assert.deepEqual([...VALID_COMMANDS], ["START_RECORDING", "STOP_RECORDING"]);
  assert.equal(isValidCommand("START_RECORDING"), true);
  assert.equal(isValidCommand("nope"), false);
});
