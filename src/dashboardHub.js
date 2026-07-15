const WebSocket = require("ws");

const DASHBOARD_BROADCAST_INTERVAL_MS = 5 * 1000;

function createDashboardHub({
  deviceHealthMonitor,
  orchestrationEngine,
  schedulerEngine,
  smsController,
  mediaController,
  devicePoolManager
}) {
  const wss = new WebSocket.Server({ noServer: true });

  const broadcastSnapshot = () => {
    const payload = JSON.stringify({
      type: "dashboard_snapshot",
      data: buildDashboardSnapshot({
        deviceHealthMonitor,
        orchestrationEngine,
        schedulerEngine,
        smsController,
        mediaController,
        devicePoolManager
      })
    });

    for (const client of wss.clients) {
      if (client.readyState === WebSocket.OPEN) {
        client.send(payload);
      }
    }
  };

  const interval = setInterval(broadcastSnapshot, DASHBOARD_BROADCAST_INTERVAL_MS);

  wss.on("connection", (socket) => {
    socket.send(
      JSON.stringify({
        type: "dashboard_snapshot",
        data: buildDashboardSnapshot({
          deviceHealthMonitor,
          orchestrationEngine,
          schedulerEngine,
          smsController,
          mediaController,
          devicePoolManager
        })
      })
    );
  });

  wss.on("close", () => {
    clearInterval(interval);
  });

  return {
    broadcastSnapshot,
    wss
  };
}

function buildDashboardSnapshot({
  deviceHealthMonitor,
  orchestrationEngine,
  schedulerEngine,
  smsController,
  mediaController,
  devicePoolManager
}) {
  const deviceHealth = deviceHealthMonitor.getAllDevices();
  const deviceStates = orchestrationEngine.getDeviceStates();
  const activeCalls = schedulerEngine.getActiveCalls();

  const inCallIds = new Set(
    activeCalls.flatMap((call) => [call.deviceA.device_id, call.deviceB.device_id])
  );

  const devices = deviceHealth.map((device) => {
    const phaseState = deviceStates[device.device_id];
    return {
      ...device,
      current_phase: phaseState?.current_phase || "IDLE",
      dashboard_status: mapDashboardStatus(device, inCallIds.has(device.device_id))
    };
  });

  return {
    orchestration_running: orchestrationEngine.isRunning(),
    pool_config: devicePoolManager?.getConfig() || {
      enabled: false,
      phone_numbers: [],
      count: 0,
      updated_at: null
    },
    devices,
    active_calls: activeCalls,
    active_sms_batches: smsController.getActiveBatches(),
    active_media_sessions: mediaController.getActiveSessions()
  };
}

function mapDashboardStatus(device, isInCall) {
  if (device.status === "OFFLINE") {
    return "OFFLINE";
  }

  if (device.status === "LOW_POWER") {
    return "LOW_POWER";
  }

  if (device.pool_enabled && !device.in_pool) {
    return "OUT_OF_POOL";
  }

  if (isInCall) {
    return "IN_CALL";
  }

  if (device.is_active) {
    return "ACTIVE";
  }

  return device.status;
}

module.exports = {
  createDashboardHub,
  buildDashboardSnapshot,
  DASHBOARD_BROADCAST_INTERVAL_MS
};
