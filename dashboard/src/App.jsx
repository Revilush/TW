import { useEffect, useMemo, useState } from "react";

const API_BASE = import.meta.env.VITE_API_BASE_URL || "http://localhost:3000";

function App() {
  const [devices, setDevices] = useState([]);
  const [poolConfig, setPoolConfig] = useState({
    enabled: false,
    phone_numbers: [],
    count: 0,
    updated_at: null
  });
  const [orchestrationRunning, setOrchestrationRunning] = useState(false);
  const [activeCalls, setActiveCalls] = useState([]);
  const [activeSmsBatches, setActiveSmsBatches] = useState([]);
  const [activeMediaSessions, setActiveMediaSessions] = useState([]);
  const [poolText, setPoolText] = useState("");
  const [lastUpdated, setLastUpdated] = useState("");
  const [error, setError] = useState("");
  const [savingPool, setSavingPool] = useState(false);

  useEffect(() => {
    fetchSnapshot();

    const wsBase = API_BASE.replace(/^http/, "ws");
    const socket = new WebSocket(`${wsBase}/dashboard/ws`);

    socket.onopen = () => setError("");

    socket.onmessage = (event) => {
      const payload = JSON.parse(event.data);
      if (payload.type === "dashboard_snapshot") {
        applySnapshot(payload.data);
      }
    };

    socket.onerror = () => {
      setError("Live updates disconnected. Manual refresh still works.");
    };

    socket.onclose = () => {
      setError("Live updates disconnected. Refresh to resync.");
    };

    return () => {
      socket.close();
    };
  }, []);

  const applySnapshot = (snapshot) => {
    const nextPoolConfig = snapshot.pool_config || {
      enabled: false,
      phone_numbers: [],
      count: 0,
      updated_at: null
    };

    setDevices(snapshot.devices || []);
    setPoolConfig(nextPoolConfig);
    setPoolText((nextPoolConfig.phone_numbers || []).join("\n"));
    setOrchestrationRunning(Boolean(snapshot.orchestration_running));
    setActiveCalls(snapshot.active_calls || []);
    setActiveSmsBatches(snapshot.active_sms_batches || []);
    setActiveMediaSessions(snapshot.active_media_sessions || []);
    setLastUpdated(new Date().toLocaleTimeString());
    setError("");
  };

  const fetchSnapshot = async () => {
    try {
      const response = await fetch(`${API_BASE}/dashboard/snapshot`);
      if (!response.ok) {
        throw new Error(`Snapshot failed: ${response.status}`);
      }
      const snapshot = await response.json();
      applySnapshot(snapshot);
    } catch (fetchError) {
      setError(fetchError.message);
    }
  };

  const savePool = async ({ enabled = poolConfig.enabled } = {}) => {
    setSavingPool(true);
    try {
      const phoneNumbers = poolText
        .split(/\r?\n|,/)
        .map((value) => value.trim())
        .filter(Boolean);

      const response = await fetch(`${API_BASE}/pool`, {
        method: "PUT",
        headers: {
          "Content-Type": "application/json"
        },
        body: JSON.stringify({
          enabled,
          phone_numbers: phoneNumbers
        })
      });

      if (!response.ok) {
        const payload = await response.json().catch(() => ({}));
        throw new Error(payload.error || `Pool update failed: ${response.status}`);
      }

      await fetchSnapshot();
    } catch (saveError) {
      setError(saveError.message);
    } finally {
      setSavingPool(false);
    }
  };

  const importPoolFile = async (event) => {
    const file = event.target.files?.[0];
    if (!file) {
      return;
    }

    try {
      const content = await file.text();
      const numbers = extractPhoneNumbers(content);
      if (!numbers.length) {
        throw new Error("No phone numbers found in the selected file");
      }

      setPoolText(numbers.join("\n"));
      setError("");
    } catch (importError) {
      setError(importError.message);
    } finally {
      event.target.value = "";
    }
  };

  const toggleOrchestration = async () => {
    const path = orchestrationRunning ? "/orchestration/stop" : "/orchestration/start";
    await fetch(`${API_BASE}${path}`, { method: "POST" });
    await fetchSnapshot();
  };

  const forceRing = async (deviceId) => {
    await fetch(`${API_BASE}/devices/${deviceId}/ring`, { method: "POST" });
    await fetchSnapshot();
  };

  const sendYoutube = async (deviceId) => {
    await fetch(`${API_BASE}/devices/${deviceId}/commands`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json"
      },
      body: JSON.stringify({
        command: {
          type: "YT",
          url: "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        }
      })
    });
    await fetchSnapshot();
  };

  const metrics = useMemo(() => {
    const active = devices.filter((device) => device.dashboard_status === "ACTIVE").length;
    const inCall = devices.filter((device) => device.dashboard_status === "IN_CALL").length;
    const offline = devices.filter((device) => device.dashboard_status === "OFFLINE").length;
    const blocked = devices.filter((device) => !device.is_active).length;
    const inPool = devices.filter((device) => device.in_pool).length;

    return {
      total: devices.length,
      active,
      inCall,
      offline,
      blocked,
      inPool
    };
  }, [devices]);

  const sortedDevices = useMemo(() => {
    return [...devices].sort((left, right) => {
      const statusRank = statusWeight(left.dashboard_status) - statusWeight(right.dashboard_status);
      if (statusRank !== 0) {
        return statusRank;
      }
      return (left.phone_number || left.device_id).localeCompare(right.phone_number || right.device_id);
    });
  }, [devices]);

  const runnableNumbers = useMemo(() => {
    return sortedDevices
      .filter((device) => device.is_active)
      .map((device) => device.phone_number || "(missing phone number)");
  }, [sortedDevices]);

  return (
    <main className="shell">
      <section className="hero">
        <div>
          <p className="eyebrow">Live Control Room</p>
          <h1>Device Pool Dashboard</h1>
          <p className="subtitle">
            Manage which mobile numbers can run in orchestration, watch device health, and trigger
            quick test commands from one place.
          </p>
        </div>
        <div className="toolbar">
          <button className={orchestrationRunning ? "danger" : "primary"} onClick={toggleOrchestration}>
            {orchestrationRunning ? "Stop Orchestration" : "Start Orchestration"}
          </button>
          <button className="secondary" onClick={fetchSnapshot}>
            Refresh
          </button>
        </div>
      </section>

      <section className="meta">
        <span className={orchestrationRunning ? "live-pill on" : "live-pill"}>
          {orchestrationRunning ? "Running" : "Stopped"}
        </span>
        <span>Last update: {lastUpdated || "Waiting..."}</span>
        <span>API: {API_BASE}</span>
        {error ? <span className="error">{error}</span> : null}
      </section>

      <section className="metrics">
        <MetricCard label="Total Devices" value={metrics.total} tone="ink" />
        <MetricCard label="Runnable Now" value={metrics.active} tone="green" />
        <MetricCard label="In Call" value={metrics.inCall} tone="blue" />
        <MetricCard label="Blocked / Idle" value={metrics.blocked} tone="amber" />
        <MetricCard label="Offline" value={metrics.offline} tone="gray" />
      </section>

      <section className="workspace">
        <article className="panel pool-panel">
          <div className="panel-heading">
            <div>
              <p className="eyebrow">Pool Control</p>
              <h2>Numbers allowed to run</h2>
            </div>
            <span className={poolConfig.enabled ? "badge status-active" : "badge status-offline"}>
              {poolConfig.enabled ? "Pool Enforced" : "All Healthy Devices"}
            </span>
          </div>

          <p className="helper">
            Add one number per line. When pool enforcement is on, only matching registered phone
            numbers are used for calls, SMS, and media.
          </p>

          <textarea
            value={poolText}
            onChange={(event) => setPoolText(event.target.value)}
            placeholder="+15550100001&#10;+15550100002&#10;+15550100003"
          />

          <div className="pool-actions">
            <label className="file-button">
              Import CSV / Excel Export
              <input type="file" accept=".csv,.txt,.tsv" onChange={importPoolFile} />
            </label>
            <button className="primary" disabled={savingPool} onClick={() => savePool({ enabled: true })}>
              Save + Enforce Pool
            </button>
            <button className="secondary" disabled={savingPool} onClick={() => savePool({ enabled: false })}>
              Save Without Enforcing
            </button>
            <button className="ghost" disabled={savingPool} onClick={() => setPoolText(runnableNumbers.join("\n"))}>
              Use Runnable Numbers
            </button>
          </div>

          <div className="mini-list">
            <strong>Runnable mobile numbers right now</strong>
            {runnableNumbers.length ? (
              runnableNumbers.map((number) => <span key={number}>{number}</span>)
            ) : (
              <span>No runnable devices yet</span>
            )}
          </div>
        </article>

        <article className="panel activity-panel">
          <p className="eyebrow">Activity</p>
          <h2>Current workload</h2>
          <div className="activity-grid">
            <ActivityStat label="Active calls" value={activeCalls.length} />
            <ActivityStat label="SMS batches" value={activeSmsBatches.length} />
            <ActivityStat label="Media sessions" value={activeMediaSessions.length} />
            <ActivityStat label="Pool matches" value={`${metrics.inPool}/${metrics.total}`} />
          </div>
          <p className="helper">
            Devices become runnable only when health checks pass and, if enabled, the phone number
            is present in the pool list.
          </p>
        </article>
      </section>

      <section className="device-section">
        <div className="section-title">
          <div>
            <p className="eyebrow">Devices</p>
            <h2>Registered nodes</h2>
          </div>
          <span>{sortedDevices.length} total</span>
        </div>

        <div className="device-grid">
          {sortedDevices.map((device) => (
            <DeviceCard
              key={device.device_id}
              device={device}
              onRing={() => forceRing(device.device_id)}
              onYoutube={() => sendYoutube(device.device_id)}
            />
          ))}
        </div>
      </section>
    </main>
  );
}

function MetricCard({ label, value, tone }) {
  return (
    <article className={`metric metric-${tone}`}>
      <span>{label}</span>
      <strong>{value}</strong>
    </article>
  );
}

function ActivityStat({ label, value }) {
  return (
    <div className="activity-stat">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function DeviceCard({ device, onRing, onYoutube }) {
  const lastSeen = device.last_seen ? new Date(device.last_seen).toLocaleString() : "Unknown";
  const planDate = device.plan_due_date ? new Date(device.plan_due_date).toLocaleDateString() : "Not set";

  return (
    <article className="card">
      <div className="card-top">
        <div>
          <h3>{device.phone_number || "No phone number"}</h3>
          <p className="muted">{device.device_id}</p>
        </div>
        <span className={`badge status-${device.dashboard_status.toLowerCase()}`}>
          {device.dashboard_status.replaceAll("_", " ")}
        </span>
      </div>

      <div className="battery-row">
        <span>Battery</span>
        <strong>{device.battery_level}%</strong>
      </div>
      <div className="battery-track">
        <span style={{ width: `${Math.max(0, Math.min(100, Number(device.battery_level) || 0))}%` }} />
      </div>

      <dl className="stats">
        <div>
          <dt>Phase</dt>
          <dd>{device.current_phase}</dd>
        </div>
        <div>
          <dt>Pool</dt>
          <dd>{device.in_pool ? "Included" : "Excluded"}</dd>
        </div>
        <div>
          <dt>Health</dt>
          <dd>{device.health_reason}</dd>
        </div>
        <div>
          <dt>Pool Reason</dt>
          <dd>{device.pool_reason}</dd>
        </div>
        <div>
          <dt>Plan Due</dt>
          <dd>{planDate}</dd>
        </div>
        <div>
          <dt>Last Seen</dt>
          <dd>{lastSeen}</dd>
        </div>
      </dl>

      <div className="card-actions">
        <button className="secondary" onClick={onRing}>
          Ring
        </button>
        <button className="secondary" onClick={onYoutube}>
          YouTube Test
        </button>
      </div>
    </article>
  );
}

function statusWeight(status) {
  const weights = {
    ACTIVE: 1,
    IN_CALL: 2,
    LOW_POWER: 3,
    OUT_OF_POOL: 4,
    BLOCKED: 5,
    OFFLINE: 6
  };
  return weights[status] || 9;
}

function extractPhoneNumbers(content) {
  const candidates = content
    .split(/[\r\n,;\t]+/)
    .map((value) => normalizePhoneNumber(value))
    .filter(Boolean);

  return [...new Set(candidates)];
}

function normalizePhoneNumber(value) {
  const normalized = String(value || "")
    .trim()
    .replace(/[^\d+]/g, "");

  if (!normalized) {
    return "";
  }

  const digitCount = normalized.replace(/\D/g, "").length;
  return digitCount >= 7 ? normalized : "";
}

export default App;
