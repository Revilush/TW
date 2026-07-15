const WebSocket = require("ws");
const { HEARTBEAT_TIMEOUT_MS } = require("./deviceManager");

const HEARTBEAT_INTERVAL_MS = 60 * 1000;

function createWebSocketHandler({ deviceManager, deviceHealthMonitor, commandDispatcher }) {
  const wss = new WebSocket.Server({ noServer: true });

  const heartbeatTimer = setInterval(() => {
    for (const socket of wss.clients) {
      if (socket.readyState === WebSocket.OPEN) {
        socket.ping();
      }
    }

    const changedDevices = deviceManager.markStaleDevicesOffline();
    changedDevices.forEach((device) => {
      console.warn(`[STALE_DEVICE] ${device.device_id} marked ${device.status}`);
    });

    deviceHealthMonitor.updateDeviceStatuses().forEach((device) => {
      if (!device.is_active) {
        console.warn(`[DEVICE_HEALTH] ${device.device_id} -> ${device.status} (${device.health_reason})`);
      }
    });
  }, HEARTBEAT_INTERVAL_MS);

  wss.on("connection", (socket, request) => {
    socket.isAlive = true;
    socket.deviceId = null;

    console.log(`[WS_CONNECTED] ${request.socket.remoteAddress || "unknown"}`);

    socket.on("pong", () => {
      socket.isAlive = true;

      if (socket.deviceId) {
        deviceManager.touch(socket.deviceId);
      }
    });

    socket.on("message", (raw) => {
      let payload;

      try {
        payload = JSON.parse(raw.toString());
      } catch (error) {
        socket.send(
          JSON.stringify({
            type: "error",
            message: "Invalid JSON payload"
          })
        );
        return;
      }

      handleIncomingMessage({ socket, payload, deviceManager, commandDispatcher });
    });

    socket.on("close", () => {
      const disconnected = deviceManager.markConnectionClosed(socket);

      if (disconnected) {
        console.log(`[WS_DISCONNECTED] ${disconnected.device_id}`);
      }
    });

    socket.on("error", (error) => {
      console.error(`[WS_ERROR] ${error.message}`);
    });
  });

  wss.on("close", () => {
    clearInterval(heartbeatTimer);
  });

  return wss;
}

function handleIncomingMessage({ socket, payload, deviceManager, commandDispatcher }) {
  switch (payload.type) {
    case "register": {
      if (!payload.device_id || !payload.phone_number) {
        socket.send(
          JSON.stringify({
            type: "error",
            message: "device_id and phone_number are required for registration"
          })
        );
        return;
      }

      socket.deviceId = payload.device_id;
      const device = deviceManager.registerDevice(payload, socket);

      socket.send(
        JSON.stringify({
          type: "registered",
          device
        })
      );
      console.log(`[DEVICE_REGISTERED] ${device.device_id}`);
      return;
    }

    case "heartbeat": {
      const deviceId = payload.device_id || socket.deviceId;

      if (!deviceId) {
        socket.send(
          JSON.stringify({
            type: "error",
            message: "device_id is required before heartbeat"
          })
        );
        return;
      }

      socket.deviceId = deviceId;
      const device = deviceManager.attachConnection(deviceId, socket);

      if (!device) {
        socket.send(
          JSON.stringify({
            type: "error",
            message: "Device is not registered"
          })
        );
        return;
      }

      const updatedDevice = deviceManager.updateHeartbeat(deviceId, payload) || device;
      socket.send(
        JSON.stringify({
          type: "heartbeat_ack",
          device: updatedDevice,
          heartbeat_timeout_ms: HEARTBEAT_TIMEOUT_MS
        })
      );
      return;
    }

    case "response": {
      const deviceId = payload.device_id || socket.deviceId;

      if (!deviceId) {
        socket.send(
          JSON.stringify({
            type: "error",
            message: "device_id is required for command responses"
          })
        );
        return;
      }

      socket.deviceId = deviceId;
      deviceManager.updateHeartbeat(deviceId);
      commandDispatcher.logResponse({
        ...payload,
        device_id: deviceId
      });
      return;
    }

    default:
      socket.send(
        JSON.stringify({
          type: "error",
          message: `Unsupported message type: ${payload.type}`
        })
      );
  }
}

module.exports = {
  createWebSocketHandler,
  HEARTBEAT_INTERVAL_MS
};
