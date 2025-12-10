import io from "socket.io-client";
import fs from "node:fs";
import path from "node:path";
import dotenv from "dotenv";

dotenv.config();

const KNOWN_CATEGORIES = ["core", "mtr"];

function requireEnv(name) {
  const value = process.env[name];
  if (!value) {
    throw new Error(`Missing required environment variable: ${name}`);
  }
  return value;
}

async function main() {
  const host = process.env.BEACON_HOST || "127.0.0.1";
  const port = requireEnv("BEACON_PORT");
  const key = requireEnv("BEACON_KEY");
  const playerUuid = process.env.BEACON_PLAYER_UUID;
  const playerName = process.env.BEACON_PLAYER_NAME;
  const mtrDimension = process.env.BEACON_MTR_DIMENSION || "minecraft:overworld";
  const mtrRouteId = envLong("BEACON_MTR_ROUTE_ID");
  const mtrStationId = envLong("BEACON_MTR_STATION_ID");
  const mtrPlatformId = envLong("BEACON_MTR_PLATFORM_ID");
  const mtrDepotId = envLong("BEACON_MTR_DEPOT_ID");
  const outDir =
    process.env.OUTPUT_DIR || path.resolve(process.cwd(), "output");
  await prepareOutputDir(outDir);

  const requestedCategories = parseCategories(process.argv.slice(2));
  console.log(
    "Running categories:",
    requestedCategories.length === KNOWN_CATEGORIES.length
      ? "all"
      : requestedCategories.join(", ")
  );

  const url = `http://${host}:${port}`;
  console.log(`Connecting to ${url} ...`);

  const socket = io(url, {
    transports: ["websocket"],
    reconnectionAttempts: 3,
    timeout: 10000,
  });

  socket.on("connect", async () => {
    console.log("Connected, socket id =", socket.id);

    try {
      for (const category of requestedCategories) {
        if (category === "core") {
          await runCoreTests({
            socket,
            key,
            playerUuid,
            playerName,
            outDir,
          });
        } else if (category === "mtr") {
          await runMtrTests({
            socket,
            key,
            outDir,
            dimension: mtrDimension,
            routeId: mtrRouteId,
            stationId: mtrStationId,
            platformId: mtrPlatformId,
            depotId: mtrDepotId,
          });
        }
      }
    } catch (err) {
      console.error("Test error:", err.message);
    } finally {
      socket.close();
    }
  });

  socket.on("connect_error", (err) => {
    console.error("Connect error:", err.message);
  });

  socket.on("error", (err) => {
    console.error("Socket error:", err);
  });
}

async function prepareOutputDir(dir) {
  if (fs.existsSync(dir)) {
    const entries = await fs.promises.readdir(dir);
    await Promise.all(
      entries.map((name) =>
        fs.promises.rm(path.join(dir, name), { recursive: true, force: true })
      )
    );
  } else {
    await fs.promises.mkdir(dir, { recursive: true });
  }
}

function sanitize(name) {
  return (name || "").replace(/[^a-zA-Z0-9_.-]/g, "_");
}

async function writeJson(outDir, fileName, data) {
  const target = path.join(outDir, fileName);
  const payload = {
    timestamp: new Date().toISOString(),
    data: data === undefined ? null : data,
  };
  await fs.promises.writeFile(
    target,
    JSON.stringify(payload, null, 2) + "\n",
    "utf8"
  );
  console.log(`Wrote ${target}`);
}

async function ensureSubdir(baseDir, name) {
  const target = path.join(baseDir, name);
  await fs.promises.mkdir(target, { recursive: true });
  return target;
}

function emitWithAck(socket, event, payload, label) {
  return new Promise((resolve, reject) => {
    console.log(`\n>>> Emitting ${event} with payload:`, payload);
    const timer = setTimeout(() => {
      reject(new Error(`${label} ack timeout`));
    }, 10000);

    // socket.io v2 ack callback receives only the response args (no error-first)
    socket.emit(event, payload, (...args) => {
      clearTimeout(timer);
      if (args.length === 0) {
        console.log(`<<< [${label}] ACK response: <no-args>`);
        resolve(undefined);
        return;
      }
      console.log(`<<< [${label}] ACK response args:`, args);
      resolve(args[0]);
    });
  });
}

function formatDate(d) {
  const yyyy = d.getFullYear();
  const mm = String(d.getMonth() + 1).padStart(2, "0");
  const dd = String(d.getDate()).padStart(2, "0");
  return `${yyyy}-${mm}-${dd}`;
}

function addDays(d, n) {
  const copy = new Date(d.getTime());
  copy.setDate(copy.getDate() + n);
  return copy;
}

function parseCategories(argv) {
  if (!argv || argv.length === 0) {
    return [...KNOWN_CATEGORIES];
  }
  const normalized = new Set();
  for (const raw of argv) {
    const value = raw.trim().toLowerCase();
    if (!KNOWN_CATEGORIES.includes(value)) {
      throw new Error(
        `Unknown category "${raw}". Available: ${KNOWN_CATEGORIES.join(", ")}`
      );
    }
    normalized.add(value);
  }
  return [...normalized];
}

function envLong(name) {
  const value = process.env[name];
  if (!value) return undefined;
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) {
    throw new Error(`Environment variable ${name} must be numeric`);
  }
  return parsed;
}

function ensureArray(value) {
  return Array.isArray(value) ? value : [];
}

async function runCoreTests({
  socket,
  key,
  playerUuid,
  playerName,
  outDir,
}) {
  console.log("\n=== Running core events ===");
  const status = await emitWithAck(socket, "get_status", { key }, "get_status");
  await writeJson(outDir, "server_status.json", status);

  const serverTime = await emitWithAck(
    socket,
    "get_server_time",
    { key },
    "get_server_time"
  );
  await writeJson(outDir, "server_time.json", serverTime);

  const onlinePlayers = await emitWithAck(
    socket,
    "list_online_players",
    { key },
    "list_online_players"
  );
  await writeJson(outDir, "online_players.json", onlinePlayers);

  if (playerUuid || playerName) {
    const adv = await emitWithAck(
      socket,
      "get_player_advancements",
      { key, playerUuid, playerName },
      "get_player_advancements"
    );
    await writeJson(
      outDir,
      `advancements_${sanitize(playerUuid || playerName || "unknown")}.json`,
      adv
    );

    const stats = await emitWithAck(
      socket,
      "get_player_stats",
      { key, playerUuid, playerName },
      "get_player_stats"
    );
    await writeJson(
      outDir,
      `stats_${sanitize(playerUuid || playerName || "unknown")}.json`,
      stats
    );

    const nbt = await emitWithAck(
      socket,
      "get_player_nbt",
      { key, playerUuid, playerName },
      "get_player_nbt"
    );
    await writeJson(
      outDir,
      `nbt_${sanitize(playerUuid || playerName || "unknown")}.json`,
      nbt
    );

    const identity = await emitWithAck(
      socket,
      "lookup_player_identity",
      { key, playerUuid, playerName },
      "lookup_player_identity"
    );
    await writeJson(
      outDir,
      `player_identity_${sanitize(playerUuid || playerName || "unknown")}.json`,
      identity
    );
  } else {
    console.log(
      "BEACON_PLAYER_UUID/BEACON_PLAYER_NAME not set, skip player-specific queries."
    );
  }

  const force = await emitWithAck(
    socket,
    "force_update",
    { key },
    "force_update"
  );
  await writeJson(outDir, `force_update.json`, force);

  const today = formatDate(new Date());
  await runMtrLogTests({
    socket,
    key,
    outDir,
    playerUuid,
    playerName,
    today,
  });
}

async function runMtrLogTests({
  socket,
  key,
  outDir,
  playerUuid,
  playerName,
  today,
}) {
  const basePayload = {
    key,
    ...(playerUuid ? { playerUuid } : {}),
    ...(playerName ? { playerName } : {}),
  };

  const mtrList = await emitWithAck(
    socket,
    "get_player_mtr_logs",
    { ...basePayload, page: 1, pageSize: 20 },
    "get_player_mtr_logs"
  );
  await writeJson(outDir, `mtr_logs_page1.json`, mtrList);

  if (mtrList && mtrList.records && mtrList.records.length > 0) {
    const firstId = mtrList.records[0].id;
    const mtrDetail = await emitWithAck(
      socket,
      "get_mtr_log_detail",
      { key, id: firstId },
      "get_mtr_log_detail"
    );
    await writeJson(outDir, `mtr_log_${firstId}.json`, mtrDetail);
  }

  const mtrToday = await emitWithAck(
    socket,
    "get_player_mtr_logs",
    { ...basePayload, singleDate: today, page: 1, pageSize: 50 },
    "get_player_mtr_logs(singleDate)"
  );
  await writeJson(outDir, `mtr_logs_${today}.json`, mtrToday);

  const endDate = today;
  const startDate = formatDate(addDays(new Date(), -6));
  const mtrLast7 = await emitWithAck(
    socket,
    "get_player_mtr_logs",
    { ...basePayload, startDate, endDate, page: 1, pageSize: 50 },
    "get_player_mtr_logs(range7d)"
  );
  await writeJson(outDir, `mtr_logs_${startDate}_to_${endDate}.json`, mtrLast7);

  const sessionsPage1 = await emitWithAck(
    socket,
    "get_player_sessions",
    { key, page: 1, pageSize: 50 },
    "get_player_sessions(page1)"
  );
  await writeJson(outDir, `player_sessions_page1.json`, sessionsPage1);

  const identitiesPage1 = await emitWithAck(
    socket,
    "list_player_identities",
    { key, page: 1, pageSize: 200 },
    "list_player_identities(page1)"
  );
  await writeJson(outDir, `player_identities_page1.json`, identitiesPage1);

  const sessionsToday = await emitWithAck(
    socket,
    "get_player_sessions",
    { key, singleDate: today, page: 1, pageSize: 100 },
    "get_player_sessions(today)"
  );
  await writeJson(outDir, `player_sessions_${today}.json`, sessionsToday);

  const sessionsJoinToday = await emitWithAck(
    socket,
    "get_player_sessions",
    { key, eventType: "JOIN", singleDate: today, page: 1, pageSize: 100 },
    "get_player_sessions(join_today)"
  );
  await writeJson(
    outDir,
    `player_sessions_JOIN_${today}.json`,
    sessionsJoinToday
  );

  if (playerUuid || playerName) {
    const sessionsByPlayer = await emitWithAck(
      socket,
      "get_player_sessions",
      { ...basePayload, page: 1, pageSize: 100 },
      "get_player_sessions(by_player)"
    );
    await writeJson(
      outDir,
      `player_sessions_${sanitize(playerUuid || playerName || "unknown")}.json`,
      sessionsByPlayer
    );
  }
}

async function runMtrTests({
  socket,
  key,
  outDir,
  dimension,
  routeId,
  stationId,
  platformId,
  depotId,
}) {
  console.log("\n=== Running MTR events ===");

  const ping = await emitWithAck(
    socket,
    "beacon_ping",
    { key, echo: "socketio-test" },
    "beacon_ping"
  );
  await writeJson(outDir, "mtr_beacon_ping.json", ping);

  const overview = await emitWithAck(
    socket,
    "get_mtr_network_overview",
    { key, dimension },
    "get_mtr_network_overview"
  );
  await writeJson(outDir, `mtr_network_overview.json`, overview);

  const depots = await emitWithAck(
    socket,
    "list_mtr_depots",
    { key, dimension },
    "list_mtr_depots"
  );
  await writeJson(outDir, `mtr_depots.json`, depots);

  const fareAreas = await emitWithAck(
    socket,
    "list_mtr_fare_areas",
    { key, dimension },
    "list_mtr_fare_areas"
  );
  await writeJson(outDir, `mtr_fare_areas.json`, fareAreas);

  const stations = await emitWithAck(
    socket,
    "list_mtr_stations",
    { key, dimension },
    "list_mtr_stations"
  );
  await writeJson(outDir, `mtr_stations.json`, stations);
  const routesList = collectRoutesForDimension(overview, dimension);
  const stationsList = extractStationsList(stations);
  const depotsList = extractDepotsList(depots);

  await dumpAllNodesPaginated({
    socket,
    key,
    dimension,
    outDir,
    limit: 512,
  });

  await dumpAllRouteExports({
    socket,
    key,
    dimension,
    routes: routesList,
    outDir,
  });

  await dumpAllStationTimetables({
    socket,
    key,
    dimension,
    stations: stationsList,
    outDir,
  });

  await dumpAllDepotTrains({
    socket,
    key,
    dimension,
    depots: depotsList,
    outDir,
  });

  const autoSelection = selectMtrTargets({
    dimension,
    overview,
    stations: stationsList,
    depots: depotsList,
  });
  await writeJson(outDir, "mtr_auto_targets.json", autoSelection.summary);

  const effectiveRouteId = resolveRouteId(routeId, autoSelection);
  if (effectiveRouteId) {
    const routeDetail = await emitWithAck(
      socket,
      "get_mtr_route_detail",
      { key, dimension, routeId: effectiveRouteId },
      "get_mtr_route_detail"
    );
    await writeJson(
      outDir,
      `mtr_route_${effectiveRouteId}_detail.json`,
      routeDetail
    );

    const routeTrains = await emitWithAck(
      socket,
      "get_mtr_route_trains",
      { key, dimension, routeId: effectiveRouteId },
      "get_mtr_route_trains"
    );
    await writeJson(
      outDir,
      `mtr_route_${effectiveRouteId}_trains.json`,
      routeTrains
    );
  } else {
    console.log(
      "无法确定 routeId（既未通过环境变量提供，也未能从数据中自动发现），跳过 get_mtr_route_detail/get_mtr_route_trains。"
    );
  }

  const stationResolution = resolveStationAndPlatform({
    explicitStationId: stationId,
    explicitPlatformId: platformId,
    stations: stationsList,
    preferredRouteId: effectiveRouteId,
    autoSelection,
  });

  if (stationResolution.stationId) {
    const timetable = await emitWithAck(
      socket,
      "get_mtr_station_timetable",
      {
        key,
        dimension,
        stationId: stationResolution.stationId,
        ...(stationResolution.platformId
          ? { platformId: stationResolution.platformId }
          : {}),
      },
      "get_mtr_station_timetable"
    );
    await writeJson(
      outDir,
      `mtr_station_${stationResolution.stationId}_timetable.json`,
      timetable
    );
  } else {
    console.log(
      "无法确定 stationId（既未通过环境变量提供，也未能从数据中自动发现），跳过 get_mtr_station_timetable。"
    );
  }

  const effectiveDepotId = resolveDepotId(depotId, autoSelection);
  if (effectiveDepotId) {
    const depotTrains = await emitWithAck(
      socket,
      "get_mtr_depot_trains",
      { key, dimension, depotId: effectiveDepotId },
      "get_mtr_depot_trains"
    );
    await writeJson(
      outDir,
      `mtr_depot_${effectiveDepotId}_trains.json`,
      depotTrains
    );
  } else {
    console.log(
      "无法确定 depotId（既未通过环境变量提供，也未能从数据中自动发现），跳过 get_mtr_depot_trains。"
    );
  }
}

function extractStationsList(response) {
  if (!response || typeof response !== "object") {
    return [];
  }
  const payload = response.payload;
  if (!payload) {
    return [];
  }
  return ensureArray(payload.stations);
}

function extractDepotsList(response) {
  if (!response || typeof response !== "object") {
    return [];
  }
  const payload = response.payload;
  if (!payload) {
    return [];
  }
  return ensureArray(payload.depots);
}

function extractDimensions(overview) {
  if (!overview || typeof overview !== "object") {
    return [];
  }
  const payload = overview.payload;
  if (!payload) {
    return [];
  }
  return ensureArray(payload.dimensions);
}

function collectRoutesForDimension(overview, dimension) {
  const dimensions = extractDimensions(overview);
  if (!dimensions.length) {
    return [];
  }
  const matched =
    dimensions.find((dim) => dim && dim.dimension === dimension) || dimensions[0];
  return ensureArray(matched?.routes);
}

function selectMtrTargets({ dimension, overview, stations, depots }) {
  const dimensionEntries = extractDimensions(overview);
  const matchedDimension =
    dimensionEntries.find(
      (entry) => entry && (!dimension || entry.dimension === dimension)
    ) || dimensionEntries[0];

  const routes = ensureArray(matchedDimension?.routes);
  const preferredRoute =
    routes.find((route) => route && route.hidden === false) || routes[0] || null;

  const dimensionDepots = ensureArray(matchedDimension?.depots);
  const mergedDepots = mergeDepots(depots, dimensionDepots);

  const stationCandidate = pickStationCandidate(stations, preferredRoute?.routeId);
  const depotCandidate = pickDepotCandidate(mergedDepots, preferredRoute?.routeId);

  return {
    summary: {
      dimensionRequested: dimension || null,
      dimensionResolved: matchedDimension?.dimension || null,
      totals: {
        dimensions: dimensionEntries.length,
        stations: stations.length,
        depots: mergedDepots.length,
      },
      candidates: {
        route: preferredRoute
          ? { routeId: preferredRoute.routeId, name: preferredRoute.name }
          : null,
        station: stationCandidate.station
          ? {
              stationId: stationCandidate.station.stationId,
              name: stationCandidate.station.name,
            }
          : null,
        platform: stationCandidate.platform
          ? {
              platformId: stationCandidate.platform.platformId,
              platformName: stationCandidate.platform.platformName,
              stationId: stationCandidate.station?.stationId || null,
            }
          : null,
        depot: depotCandidate
          ? { depotId: depotCandidate.depotId, name: depotCandidate.name }
          : null,
      },
    },
    route: preferredRoute
      ? { routeId: preferredRoute.routeId, name: preferredRoute.name }
      : null,
    station: stationCandidate.station
      ? {
          stationId: stationCandidate.station.stationId,
          name: stationCandidate.station.name,
        }
      : null,
    platform: stationCandidate.platform
      ? {
          platformId: stationCandidate.platform.platformId,
          platformName: stationCandidate.platform.platformName,
          stationId: stationCandidate.station?.stationId || null,
        }
      : null,
    depot: depotCandidate
      ? { depotId: depotCandidate.depotId, name: depotCandidate.name }
      : null,
  };
}

function mergeDepots(primary, secondary) {
  const merged = [];
  const seen = new Set();
  for (const list of [ensureArray(primary), ensureArray(secondary)]) {
    for (const depot of list) {
      if (!depot || typeof depot.depotId !== "number") {
        continue;
      }
      if (seen.has(depot.depotId)) {
        continue;
      }
      seen.add(depot.depotId);
      merged.push(depot);
    }
  }
  return merged;
}

function pickStationCandidate(stations, preferredRouteId) {
  const stationList = ensureArray(stations);
  if (!stationList.length) {
    return { station: null, platform: null };
  }

  if (preferredRouteId) {
    for (const station of stationList) {
      const platform = pickPlatform(station, preferredRouteId);
      if (platform) {
        return { station, platform };
      }
    }
  }

  for (const station of stationList) {
    const platform = pickPlatform(station);
    if (platform) {
      return { station, platform };
    }
  }

  return { station: stationList[0], platform: null };
}

function pickPlatform(station, preferredRouteId) {
  if (!station) {
    return null;
  }
  const platforms = ensureArray(station.platforms);
  if (!platforms.length) {
    return null;
  }
  if (preferredRouteId) {
    const match = platforms.find((platform) => {
      const routes = ensureArray(platform?.routeIds);
      return routes.some((route) => Number(route) === Number(preferredRouteId));
    });
    if (match) {
      return match;
    }
  }
  return platforms[0];
}

function pickDepotCandidate(depots, preferredRouteId) {
  if (!depots || !depots.length) {
    return null;
  }
  if (preferredRouteId) {
    const match = depots.find((depot) =>
      ensureArray(depot.routeIds).some(
        (routeId) => Number(routeId) === Number(preferredRouteId)
      )
    );
    if (match) {
      return match;
    }
  }
  return depots[0];
}

function resolveRouteId(explicitRouteId, autoSelection) {
  if (explicitRouteId) {
    console.log(`[MTR] 使用环境变量 routeId=${explicitRouteId}`);
    return explicitRouteId;
  }
  if (autoSelection.route?.routeId) {
    const label = autoSelection.route.name
      ? ` (${autoSelection.route.name})`
      : "";
    console.log(
      `[MTR] 自动选择 routeId=${autoSelection.route.routeId}${label}`
    );
    return autoSelection.route.routeId;
  }
  return undefined;
}

function resolveStationAndPlatform({
  explicitStationId,
  explicitPlatformId,
  stations,
  preferredRouteId,
  autoSelection,
}) {
  let stationId = explicitStationId;
  if (stationId) {
    console.log(`[MTR] 使用环境变量 stationId=${stationId}`);
  } else if (autoSelection.station?.stationId) {
    stationId = autoSelection.station.stationId;
    const label = autoSelection.station.name
      ? ` (${autoSelection.station.name})`
      : "";
    console.log(`[MTR] 自动选择 stationId=${stationId}${label}`);
  }

  let platformId = explicitPlatformId;
  if (platformId) {
    console.log(`[MTR] 使用环境变量 platformId=${platformId}`);
    return { stationId, platformId };
  }

  if (!stationId) {
    return { stationId: undefined, platformId: undefined };
  }

  const stationRecord = findStationById(stations, stationId);
  if (stationRecord) {
    const platform = pickPlatform(stationRecord, preferredRouteId);
    if (platform) {
      console.log(
        `[MTR] 自动选择 platformId=${platform.platformId}` +
          (platform.platformName ? ` (${platform.platformName})` : "")
      );
      return { stationId, platformId: platform.platformId };
    }
  }

  if (autoSelection.platform?.platformId && autoSelection.station?.stationId) {
    if (autoSelection.station.stationId === stationId) {
      const label = autoSelection.platform.platformName
        ? ` (${autoSelection.platform.platformName})`
        : "";
      console.log(
        `[MTR] 自动选择 platformId=${autoSelection.platform.platformId}${label}`
      );
      return {
        stationId,
        platformId: autoSelection.platform.platformId,
      };
    }
  }

  return { stationId, platformId: undefined };
}

function findStationById(stations, stationId) {
  const numericId = Number(stationId);
  if (!Number.isFinite(numericId)) {
    return null;
  }
  return ensureArray(stations).find(
    (station) => Number(station?.stationId) === numericId
  );
}

function resolveDepotId(explicitDepotId, autoSelection) {
  if (explicitDepotId) {
    console.log(`[MTR] 使用环境变量 depotId=${explicitDepotId}`);
    return explicitDepotId;
  }
  if (autoSelection.depot?.depotId) {
    const label = autoSelection.depot.name
      ? ` (${autoSelection.depot.name})`
      : "";
    console.log(
      `[MTR] 自动选择 depotId=${autoSelection.depot.depotId}${label}`
    );
    return autoSelection.depot.depotId;
  }
  return undefined;
}

async function dumpAllNodesPaginated({ socket, key, dimension, outDir, limit }) {
  const tag = getDimensionTag(dimension);
  const dir = await ensureSubdir(outDir, `mtr_nodes_${tag}`);
  console.log(`[MTR] 正在导出节点数据（目录 ${dir}，limit=${limit}）...`);
  let cursor;
  for (let page = 1; page <= 10000; page++) {
    const payload = {
      key,
      dimension,
      limit,
      ...(cursor ? { cursor } : {}),
    };
    let response;
    try {
      response = await emitWithAck(
        socket,
        "list_mtr_nodes_paginated",
        payload,
        `list_mtr_nodes_paginated(page${page})`
      );
    } catch (err) {
      console.error(
        `[MTR] list_mtr_nodes_paginated page${page} failed: ${err.message}`
      );
      await writeJson(
        dir,
        `nodes_page_${String(page).padStart(4, "0")}.json`,
        { success: false, error: err.message }
      );
      break;
    }
    await writeJson(
      dir,
      `nodes_page_${String(page).padStart(4, "0")}.json`,
      response
    );
    if (page === 1) {
      await writeJson(outDir, `mtr_nodes_page1.json`, response);
    }
    const payloadData = response?.payload;
    if (!payloadData || !payloadData.hasMore || !payloadData.nextCursor) {
      break;
    }
    cursor = payloadData.nextCursor;
  }
}

async function dumpAllRouteExports({ socket, key, dimension, routes, outDir }) {
  if (!routes || routes.length === 0) {
    console.log("[MTR] 未发现线路，跳过线路详情/列车导出。");
    return;
  }
  const tag = getDimensionTag(dimension);
  const dir = await ensureSubdir(outDir, `mtr_routes_${tag}`);
  console.log(`[MTR] 导出 ${routes.length} 条线路的节点和列车状态（${dir}）。`);
  for (const route of routes) {
    const routeId = Number(route?.routeId);
    if (!Number.isFinite(routeId) || routeId <= 0) {
      continue;
    }
    const display = route?.name ? `${routeId} (${route.name})` : `${routeId}`;
    console.log(`[MTR] -> 线路 ${display}`);
    await emitWriteSafe({
      socket,
      event: "get_mtr_route_detail",
      payload: { key, dimension, routeId },
      label: `get_mtr_route_detail#${routeId}`,
      outDir: dir,
      fileName: `route_${routeId}_detail.json`,
    });
    await emitWriteSafe({
      socket,
      event: "get_mtr_route_trains",
      payload: { key, dimension, routeId },
      label: `get_mtr_route_trains#${routeId}`,
      outDir: dir,
      fileName: `route_${routeId}_trains.json`,
    });
  }
}

async function dumpAllStationTimetables({
  socket,
  key,
  dimension,
  stations,
  outDir,
}) {
  if (!stations || stations.length === 0) {
    console.log("[MTR] 未发现站点，跳过站点时刻表导出。");
    return;
  }
  const tag = getDimensionTag(dimension);
  const dir = await ensureSubdir(outDir, `mtr_station_timetables_${tag}`);
  console.log(`[MTR] 导出 ${stations.length} 个车站的时刻表（${dir}）。`);
  for (const station of stations) {
    const stationId = Number(station?.stationId);
    if (!Number.isFinite(stationId) || stationId <= 0) {
      continue;
    }
    const display = station?.name
      ? `${stationId} (${station.name})`
      : `${stationId}`;
    console.log(`[MTR] -> 车站 ${display}`);
    await emitWriteSafe({
      socket,
      event: "get_mtr_station_timetable",
      payload: { key, dimension, stationId },
      label: `get_mtr_station_timetable#${stationId}`,
      outDir: dir,
      fileName: `station_${stationId}_timetable.json`,
    });
  }
}

async function dumpAllDepotTrains({ socket, key, dimension, depots, outDir }) {
  if (!depots || depots.length === 0) {
    console.log("[MTR] 未发现车厂，跳过车厂列车导出。");
    return;
  }
  const tag = getDimensionTag(dimension);
  const dir = await ensureSubdir(outDir, `mtr_depot_trains_${tag}`);
  console.log(`[MTR] 导出 ${depots.length} 个车厂的列车状态（${dir}）。`);
  for (const depot of depots) {
    const depotId = Number(depot?.depotId);
    if (!Number.isFinite(depotId) || depotId <= 0) {
      continue;
    }
    const display = depot?.name ? `${depotId} (${depot.name})` : `${depotId}`;
    console.log(`[MTR] -> 车厂 ${display}`);
    await emitWriteSafe({
      socket,
      event: "get_mtr_depot_trains",
      payload: { key, dimension, depotId },
      label: `get_mtr_depot_trains#${depotId}`,
      outDir: dir,
      fileName: `depot_${depotId}_trains.json`,
    });
  }
}

async function emitWriteSafe({
  socket,
  event,
  payload,
  label,
  outDir,
  fileName,
}) {
  try {
    const response = await emitWithAck(socket, event, payload, label);
    await writeJson(outDir, fileName, response);
  } catch (err) {
    console.error(`[MTR] ${label} failed: ${err.message}`);
    await writeJson(outDir, fileName, { success: false, error: err.message });
  }
}

function getDimensionTag(dimension) {
  return sanitize(dimension || "all");
}

main().catch((err) => {
  console.error("Fatal error:", err);
  process.exit(1);
});
