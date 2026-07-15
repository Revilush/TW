const WebSocket = require("ws");
const { randomUUID } = require("crypto");

const DEFAULT_URL = process.env.SIMULATOR_URL || "ws://localhost:3000/ws";
const HEARTBEAT_INTERVAL_MS = 60_000;
const RECONNECT_DELAY_MS = 5_000;

function main() {
  const args = parseArgs(process.argv.slice(2));
  const clients = Number(args.clients || 1);
  const serverUrl = args.url || DEFAULT_URL;

  if (!Number.isInteger(clients) || clients <= 0) {
    throw new Error("--clients must be a positive integer");
  }

  console.log(`Starting ${clients} simulated devices -> ${serverUrl}`);

  const simulators = Array.from({ length: clients }, (_, index) => {
    const simulator = new DeviceSimulator({
      index: index + 1,
      serverUrl
    });
    simulator.start();
    return simulator;
  });

  const shutdown = () => {
    console.log("\nShutting down simulators...");
    simulators.forEach((simulator) => simulator.stop());
    process.exit(0);
  };

  process.on("SIGINT", shutdown);
  process.on("SIGTERM", shutdown);
}

class DeviceSimulator {
  constructor({ index, serverUrl }) {
    this.index = index;
    this.serverUrl = serverUrl;
    this.deviceId = `sim-${index}-${randomUUID().slice(0, 8)}`;
    this.phoneNumber = randomPhoneNumber(index);
    this.planDueDate = futurePlanDueDate();
    this.batteryLevel = randomInt(30, 100);
    this.socket = null;
    this.heartbeatTimer = null;
    this.reconnectTimer = null;
    this.stopped = false;
  }

  start() {
    this.connectWithDelay(randomInt(250, 2_000));
  }

  stop() {
    this.stopped = true;
    clearTimeout(this.reconnectTimer);
    clearInterval(this.heartbeatTimer);

    if (this.socket && this.socket.readyState === WebSocket.OPEN) {
      this.socket.close(1000, "Simulator stopped");
    }
  }

  connectWithDelay(delayMs) {
    clearTimeout(this.reconnectTimer);
    this.reconnectTimer = setTimeout(() => {
      if (this.stopped) {
        return;
      }

      this.connect();
    }, delayMs);
  }

  connect() {
    console.log(`[${this.deviceId}] connecting`);
    this.socket = new WebSocket(this.serverUrl);

    this.socket.on("open", () => {
      console.log(`[${this.deviceId}] connected`);
      this.sendRegistration();
      this.startHeartbeat();
    });

    this.socket.on("message", (raw) => {
      this.handleMessage(raw.toString());
    });

    this.socket.on("close", (code, reason) => {
      console.log(`[${this.deviceId}] disconnected ${code} ${reason || ""}`.trim());
      this.stopHeartbeat();

      if (!this.stopped) {
        this.connectWithDelay(RECONNECT_DELAY_MS);
      }
    });

    this.socket.on("error", (error) => {
      console.error(`[${this.deviceId}] socket error: ${error.message}`);
    });
  }

  handleMessage(raw) {
    let payload;

    try {
      payload = JSON.parse(raw);
    } catch (error) {
      console.error(`[${this.deviceId}] invalid JSON from server: ${raw}`);
      return;
    }

    switch (payload.type) {
      case "registered":
        console.log(`[${this.deviceId}] registered`);
        return;

      case "heartbeat_ack":
        return;

      case "command":
        this.handleCommand(payload.command_id, payload.command || {});
        return;

      case "error":
        console.error(`[${this.deviceId}] server error: ${payload.message}`);
        return;

      default:
        console.log(`[${this.deviceId}] unhandled message: ${payload.type}`);
    }
  }

  handleCommand(commandId, command) {
    const delayMs = randomInt(300, 2_500);

    setTimeout(() => {
      let resultMessage = "completed";

      switch ((command.type || "").toUpperCase()) {
        case "CALL":
          resultMessage = `calling ${command.phone_number || "unknown"}`;
          console.log(`[${this.deviceId}] calling ${command.phone_number || "unknown"}`);
          break;

        case "SMS":
          resultMessage = `sms "${command.message || ""}"`;
          console.log(`[${this.deviceId}] sms ${command.phone_number || "unknown"} -> ${command.message || ""}`);
          break;

        case "YT":
          resultMessage = `playback ${command.url || "unknown"}`;
          console.log(`[${this.deviceId}] playback ${command.url || "unknown"}`);
          break;

        case "END_CALL":
          resultMessage = "call ended";
          console.log(`[${this.deviceId}] call ended`);
          break;

        case "RING":
          resultMessage = "ringing";
          console.log(`[${this.deviceId}] ringing`);
          break;

        default:
          resultMessage = `unsupported command ${command.type || "UNKNOWN"}`;
          console.log(`[${this.deviceId}] unsupported command ${command.type || "UNKNOWN"}`);
      }

      this.sendResponse(commandId, resultMessage);
    }, delayMs);
  }

  sendRegistration() {
    this.send({
      type: "register",
      device_id: this.deviceId,
      phone_number: this.phoneNumber,
      battery_level: this.batteryLevel,
      plan_due_date: this.planDueDate,
      last_seen: new Date().toISOString()
    });
  }

  startHeartbeat() {
    this.stopHeartbeat();
    this.sendHeartbeat();

    this.heartbeatTimer = setInterval(() => {
      this.sendHeartbeat();
    }, HEARTBEAT_INTERVAL_MS + randomInt(0, 10_000));
  }

  stopHeartbeat() {
    clearInterval(this.heartbeatTimer);
    this.heartbeatTimer = null;
  }

  sendHeartbeat() {
    this.batteryLevel = Math.max(30, this.batteryLevel - randomInt(0, 2));

    this.send({
      type: "heartbeat",
      device_id: this.deviceId,
      phone_number: this.phoneNumber,
      battery_level: this.batteryLevel,
      plan_due_date: this.planDueDate,
      last_seen: new Date().toISOString()
    });
  }

  sendResponse(commandId, message) {
    this.send({
      type: "response",
      device_id: this.deviceId,
      command_id: commandId,
      status: "SUCCESS",
      result: {
        message
      }
    });
  }

  send(payload) {
    if (!this.socket || this.socket.readyState !== WebSocket.OPEN) {
      return;
    }

    this.socket.send(JSON.stringify(payload));
  }
}

function parseArgs(argv) {
  return argv.reduce((accumulator, current) => {
    if (!current.startsWith("--")) {
      return accumulator;
    }

    const [key, value] = current.slice(2).split("=");
    accumulator[key] = value === undefined ? true : value;
    return accumulator;
  }, {});
}

function randomPhoneNumber(index) {
  const suffix = String(10_000_000 + index).slice(-7);
  return `+1555${suffix}`;
}

function futurePlanDueDate() {
  const daysAhead = randomInt(7, 120);
  return new Date(Date.now() + daysAhead * 24 * 60 * 60 * 1000).toISOString();
}

function randomInt(min, max) {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}

main();
