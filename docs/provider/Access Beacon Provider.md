# Beacon Provider Channel 接入说明

本文记录 Bukkit 插件内如何对接 Beacon Provider Mod，包含 Channel 注册、请求/响应结构以及预置 action builder 的使用方式，方便后续把 MTR/Fabric 侧的数据写入 SQLite。

## 1. Netty Gateway 初始化

- Forge/Fabric 端自 0.1.6 起默认开启 Netty Gateway（参见 Provider 仓库 `docs/Netty Gateway.md`），并在 `config/hydroline/beacon-provider.json` 输出监听地址、端口与 `authToken`。
- Bukkit 插件会在 `onEnable` 时读取同一份配置文件，主动建立 TCP 连接并完成握手，无需玩家在线也能调用 action。若 `listenPort <= 0` 或文件缺失，客户端会跳过连接并在日志中提示。
- **配置要求**：首次运行请在 Provider 侧修改 `authToken`，然后将相同文件部署到 Bukkit 服务器根目录（通常与 Forge/Mohist 共用目录即可）。
- 握手成功后日志会输出：`[Beacon] Beacon Gateway connected (connectionId=..., modVersion=...)` 和 `Beacon Provider channel ready, latency=XXms`。
- `BeaconProviderClient` 位于 `com.hydroline.beacon.provider.channel`，通过 `BeaconPlugin#getBeaconProviderClient()` 取得实例即可发起请求；连接断线时会自动重连并对所有挂起的请求返回异常。

```java
BeaconProviderClient client = plugin.getBeaconProviderClient();
if (client != null && client.isStarted()) {
    client.sendAction(BeaconProviderActions.ping("web"))
        .thenAccept(response -> {
            if (response.isOk()) {
                BeaconPingResponse payload = response.getPayload();
                plugin.getLogger().info("Beacon Provider 延迟=" + payload.getLatencyMs() + "ms");
            } else {
                plugin.getLogger().warning("Beacon Provider ping 失败: " + response.getMessage());
            }
        });
} else {
    plugin.getLogger().warning("Beacon Provider gateway 尚未就绪或配置缺失");
}
```

> `sendAction` 返回 `CompletableFuture<BeaconActionResponse<T>>`，内部已根据 `requestId` 映射响应；默认超时 10s，可通过 `BeaconActionCall#withTimeout(Duration)` 自定义。

## 2. Action Builder

`com.hydroline.beacon.provider.actions.BeaconProviderActions` 提供了当前 Provider 文档列出的 action 便捷构造器：

| 方法                                                    | 对应 action                 | 说明                                                                |
| ------------------------------------------------------- | --------------------------- | ------------------------------------------------------------------- |
| `ping(echo)`                                            | `beacon:ping`               | 通道连通性/延迟探测，`payload` 可选 `echo` 字段。                   |
| `listNetworkOverview(dimension)`                        | `mtr:list_network_overview` | 可选过滤维度；响应包含每个维度的线路、车厂、收费区概览。            |
| `getRouteDetail(dimension, routeId)`                    | `mtr:get_route_detail`      | 维度 + 路线 ID，返回节点序列、颜色、类型等。                        |
| `listDepots(dimension)`                                 | `mtr:list_depots`           | 可选维度过滤，输出 `departures`、`routeIds` 等信息。                |
| `listFareAreas(dimension)`                              | `mtr:list_fare_areas`       | 需要指定维度，返回站点/收费区多边形。                               |
| `listNodesPaginated(dimension, cursor, limit)`          | `mtr:list_nodes_paginated`  | 维度必填，支持 `cursor` + `limit` 分页同步节点。                    |
| `getStationTimetable(dimension, stationId, platformId)` | `mtr:get_station_timetable` | 维度 + 站点 ID，`platformId` 可选，响应含 `entries`（到站、延误）。 |

调用示例（分页拉取节点）：

```java
client.sendAction(BeaconProviderActions.listNodesPaginated("minecraft:overworld", null, 512))
        .thenAccept(response -> {
            if (!response.isOk()) {
                plugin.getLogger().warning("list_nodes_paginated 失败: " + response.getMessage());
                return;
            }
            MtrNodePageResponse page = response.getPayload();
            page.getNodes().forEach(node -> {
                // TODO: 写入 SQLite
            });
            if (page.isHasMore()) {
                // 递归请求下一页
            }
        });
```

## 3. DTO 与扩展

- 所有响应 DTO 位于 `com.hydroline.beacon.provider.actions.dto`，字段结构与 Provider `MtrJsonWriter` 输出一致并标记 `@JsonIgnoreProperties(ignoreUnknown = true)`，Protocol 扩展时无需立刻改动 Bukkit 端。
- 若 Provider 新增 action，可：
  1. 在 Bukkit 端自定义 `ObjectNode` payload；
  2. 通过 `BeaconActionCall.of("action:name", payload, ResponseClass.class)` 直接发送；
  3. 按需在 `actions/dto` 包新增响应 DTO，或直接将 payload 映射到 `JsonNode`/`Map`。
- `BeaconProviderClient` 暴露的 `sendAction` 支持任意 `BeaconActionCall`，因此新增 action 只需在 Bukkit 项目内追加 builder 与 DTO 即可。

## 4. Socket.IO 事件映射

- `SocketServerManager` 已注册以下事件并直接调用 `BeaconProviderClient`：
  - `beacon_ping`
  - `get_mtr_network_overview`
  - `get_mtr_route_detail`
  - `list_mtr_nodes_paginated`
  - `list_mtr_depots`
  - `list_mtr_fare_areas`
  - `get_mtr_station_timetable`
  - `list_mtr_stations`
  - `get_mtr_route_trains`
  - `get_mtr_depot_trains`
- 事件响应统一包含 `success/result/message/request_id/payload` 字段；详见 `docs/Socket IO API.md` 中的“Beacon Provider 透传事件”章节。

## 5. 注意事项

- Gateway 配置位于 `config/hydroline/beacon-provider.json`，若 Provider 尚未生成该文件，Bukkit 客户端会在日志中提示并跳过连接。请确保两端共享同一份配置并修改 `authToken`。
- 旧版 Plugin Messaging 已废弃；在 `listenPort > 0` 的情况下 Bukkit 不再注册 `hydroline:beacon_provider` Channel。
- 如需排查连接问题，可查看 Bukkit 控制台是否反复输出 “Beacon Gateway connection lost…”，或检查 Provider 端的 `Netty Gateway` 日志。
