const TEST_MODE = true;

const DELAY_TYPES = Object.freeze({
  CALL_DELAY: "CALL_DELAY",
  CALL_DURATION: "CALL_DURATION",
  SMS_DELAY: "SMS_DELAY",
  YT_DURATION: "YT_DURATION"
});

const TIMING_RANGES_MS = Object.freeze({
  CALL_DELAY: TEST_MODE
    ? { min: 2_000, max: 5_000 }
    : { min: 1 * 60 * 1000, max: 5 * 60 * 1000 },
  CALL_DURATION: TEST_MODE
    ? { min: 10_000, max: 30_000 }
    : { min: 20 * 60 * 1000, max: 40 * 60 * 1000 },
  SMS_DELAY: TEST_MODE
    ? { min: 2_000, max: 5_000 }
    : { min: 30 * 1000, max: 120 * 1000 },
  YT_DURATION: TEST_MODE
    ? { min: 60 * 1000, max: 2 * 60 * 1000 }
    : { min: 3 * 60 * 60 * 1000, max: 3 * 60 * 60 * 1000 }
});

const ORCHESTRATION_TICK_INTERVAL_MS = TEST_MODE ? 5_000 : 15_000;

console.log(`[CONFIG] TEST_MODE is ${TEST_MODE ? "ON" : "OFF"}`);

function getRandomDelay(type) {
  const range = TIMING_RANGES_MS[type];

  if (!range) {
    throw new Error(`Unsupported delay type: ${type}`);
  }

  const delayMs = randomBetween(range.min, range.max);
  console.log(`[CONFIG] ${type} delay selected: ${delayMs}ms (TEST_MODE=${TEST_MODE ? "ON" : "OFF"})`);
  return delayMs;
}

function randomBetween(min, max) {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}

module.exports = {
  TEST_MODE,
  DELAY_TYPES,
  ORCHESTRATION_TICK_INTERVAL_MS,
  getRandomDelay
};
