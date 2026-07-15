const { DELAY_TYPES, getRandomDelay } = require("../config");

const TARGETS_PER_CYCLE = 3;
const MIN_CONVERSATION_MESSAGES = 2;
const MAX_CONVERSATION_MESSAGES = 4;
const GREETING_MESSAGES = ["Hey", "Hi", "Hello"];
const FOLLOW_UP_MESSAGES = [
  "How are you?",
  "What's up?",
  "All good here",
  "Just checking in",
  "Same here",
  "You good?",
  "Talk soon"
];

class SmsController {
  constructor({ commandDispatcher, onBatchCompleted } = {}) {
    this.commandDispatcher = commandDispatcher;
    this.onBatchCompleted = onBatchCompleted || (() => {});
    this.activeBatches = new Map();
  }

  sendSmsBatch(device, targetInput, messages = []) {
    if (!device?.device_id) {
      throw new Error("device.device_id is required");
    }

    if (this.activeBatches.has(device.device_id)) {
      throw new Error(`SMS batch already active for ${device.device_id}`);
    }

    const targetDevices = normalizeTargets(targetInput, device.device_id);
    if (targetDevices.length === 0) {
      throw new Error("At least one unique target device is required");
    }

    const conversations = targetDevices
      .slice(0, TARGETS_PER_CYCLE)
      .map((targetDevice) => createConversation(device, targetDevice, messages));

    const batchRecord = {
      batch_id: `${device.device_id}::${Date.now()}`,
      device_id: device.device_id,
      target_devices: conversations.map((conversation) => ({
        device_id: conversation.target.device_id,
        phone_number: conversation.target.phone_number
      })),
      total_messages: conversations.reduce(
        (total, conversation) => total + conversation.messages.length,
        0
      ),
      sent_messages: 0,
      conversations,
      conversation_index: 0,
      message_index: 0,
      next_delay_ms: getRandomDelay(DELAY_TYPES.SMS_DELAY),
      status: "RUNNING",
      started_at: new Date().toISOString(),
      completed_at: null,
      last_error: null,
      timer: null
    };

    this.activeBatches.set(device.device_id, batchRecord);
    this.scheduleNextMessage(device, batchRecord);
    return this.serializeBatch(batchRecord);
  }

  getBatch(deviceId) {
    const batchRecord = this.activeBatches.get(deviceId);
    return batchRecord ? this.serializeBatch(batchRecord) : null;
  }

  getActiveBatches() {
    return Array.from(this.activeBatches.values()).map((batchRecord) => this.serializeBatch(batchRecord));
  }

  hasActiveBatch(deviceId) {
    return this.activeBatches.has(deviceId);
  }

  clearBatch(deviceId) {
    const batchRecord = this.activeBatches.get(deviceId);
    if (!batchRecord) {
      return;
    }

    clearTimeout(batchRecord.timer);
    this.activeBatches.delete(deviceId);
  }

  scheduleNextMessage(device, batchRecord) {
    const currentConversation = batchRecord.conversations[batchRecord.conversation_index];
    if (!currentConversation) {
      this.completeBatch(device.device_id);
      return;
    }

    const delayMs = batchRecord.next_delay_ms;
    console.log(
      `[SMS_BATCH] ${batchRecord.batch_id} next_message_delay_ms=${delayMs} sent=${batchRecord.sent_messages}/${batchRecord.total_messages}`
    );
    batchRecord.timer = setTimeout(() => {
      this.dispatchMessage(device, batchRecord);
    }, delayMs);
  }

  dispatchMessage(device, batchRecord) {
    const currentConversation = batchRecord.conversations[batchRecord.conversation_index];
    if (!currentConversation) {
      this.completeBatch(device.device_id);
      return;
    }

    if (batchRecord.message_index === 0 && !currentConversation.started) {
      currentConversation.started = true;
      console.log(
        `[SMS_START] ${currentConversation.source.device_id} ↔ ${currentConversation.target.device_id}`
      );
    }

    const nextMessage = currentConversation.messages[batchRecord.message_index];
    if (!nextMessage) {
      console.log(
        `[SMS_END] ${currentConversation.source.device_id} ↔ ${currentConversation.target.device_id}`
      );
      batchRecord.conversation_index += 1;
      batchRecord.message_index = 0;
      batchRecord.next_delay_ms = getRandomDelay(DELAY_TYPES.SMS_DELAY);
      this.scheduleNextMessage(device, batchRecord);
      return;
    }

    let sendError = null;
    try {
      this.commandDispatcher.sendCommand(nextMessage.sender.device_id, {
        type: "SMS",
        phone_number: nextMessage.receiver.phone_number,
        message: nextMessage.text
      });
    } catch (error) {
      sendError = error.message;
      batchRecord.last_error = error.message;
      console.warn(
        `[SMS_SENT] ${nextMessage.sender.device_id} → ${nextMessage.receiver.device_id}: ${nextMessage.text} (logged only: ${error.message})`
      );
    }

    if (!sendError) {
      batchRecord.last_error = null;
      console.log(
        `[SMS_SENT] ${nextMessage.sender.device_id} → ${nextMessage.receiver.device_id}: ${nextMessage.text}`
      );
    } else {
      batchRecord.status = "COMPLETED_WITH_WARNINGS";
    }

    batchRecord.sent_messages += 1;
    batchRecord.message_index += 1;
    batchRecord.next_delay_ms = getRandomDelay(DELAY_TYPES.SMS_DELAY);

    this.scheduleNextMessage(device, batchRecord);
  }

  completeBatch(deviceId) {
    const batchRecord = this.activeBatches.get(deviceId);
    if (!batchRecord) {
      return;
    }

    if (batchRecord.status === "RUNNING") {
      batchRecord.status = "COMPLETED";
    }
    batchRecord.completed_at = new Date().toISOString();
    this.activeBatches.delete(deviceId);
    this.onBatchCompleted(this.serializeBatch(batchRecord));
  }

  serializeBatch(batchRecord) {
    return {
      batch_id: batchRecord.batch_id,
      device_id: batchRecord.device_id,
      target_devices: [...batchRecord.target_devices],
      total_messages: batchRecord.total_messages,
      sent_messages: batchRecord.sent_messages,
      remaining_messages: Math.max(0, batchRecord.total_messages - batchRecord.sent_messages),
      next_delay_ms:
        batchRecord.sent_messages < batchRecord.total_messages
          ? batchRecord.next_delay_ms
          : null,
      status: batchRecord.status,
      started_at: batchRecord.started_at,
      completed_at: batchRecord.completed_at,
      last_error: batchRecord.last_error
    };
  }
}

function createConversation(sourceDevice, targetDevice, fallbackMessages = []) {
  const messageCount = randomBetween(MIN_CONVERSATION_MESSAGES, MAX_CONVERSATION_MESSAGES);
  const scriptedMessages = Array.isArray(fallbackMessages)
    ? fallbackMessages.filter((message) => typeof message === "string" && message.trim())
    : [];
  const messages = [];

  for (let index = 0; index < messageCount; index += 1) {
    const sender = index % 2 === 0 ? sourceDevice : targetDevice;
    const receiver = index % 2 === 0 ? targetDevice : sourceDevice;
    const text = index === 0
      ? pickRandom(GREETING_MESSAGES)
      : scriptedMessages[index - 1] || pickRandom(FOLLOW_UP_MESSAGES);

    messages.push({ sender, receiver, text });
  }

  return {
    source: sourceDevice,
    target: targetDevice,
    messages,
    started: false
  };
}

function normalizeTargets(targetInput, sourceDeviceId) {
  const rawTargets = Array.isArray(targetInput)
    ? targetInput
    : targetInput
      ? [{ device_id: `target-${targetInput}`, phone_number: targetInput }]
      : [];

  const seenTargetIds = new Set();
  return rawTargets
    .filter((target) => target?.device_id && target.phone_number && target.device_id !== sourceDeviceId)
    .filter((target) => {
      if (seenTargetIds.has(target.device_id)) {
        return false;
      }
      seenTargetIds.add(target.device_id);
      return true;
    });
}

function pickRandom(items) {
  return items[Math.floor(Math.random() * items.length)];
}

function randomBetween(min, max) {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}

module.exports = {
  SmsController,
  createConversation,
  TARGETS_PER_CYCLE,
  MIN_CONVERSATION_MESSAGES,
  MAX_CONVERSATION_MESSAGES
};
