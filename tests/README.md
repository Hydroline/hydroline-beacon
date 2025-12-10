# Hydroline Beacon Socket.IO 测试脚本

`tests/test-socketio.js` 是一个基于 `socket.io-client@2.4.0` 的轻量脚本，用于批量验证 Beacon 插件提供的 Socket.IO 事件。脚本运行前会清空输出目录，并把所有响应结果以 JSON 写入 `./output`（可用 `OUTPUT_DIR` 自定义）。

## 运行方式

```bash
cd tests
pnpm install   # 首次运行需要先安装依赖
BEACON_PORT=48080 BEACON_KEY=... node test-socketio.js            # 默认跑全部事件
BEACON_PORT=48080 BEACON_KEY=... node test-socketio.js core        # 仅运行核心事件
BEACON_PORT=48080 BEACON_KEY=... node test-socketio.js mtr         # 仅运行 MTR 事件
```

- 若命令行未带参数，将执行 `core` + `mtr` 两个分类。
- 传入一个或多个分类（`core mtr`）时，只清空一次输出目录，然后按顺序执行指定分类。
- 未知分类会直接报错，当前支持：`core`、`mtr`。

## 环境变量

| 变量名                   | 说明                                                    | 默认值/必填           |
| ------------------------ | ------------------------------------------------------- | --------------------- |
| `BEACON_HOST`            | Beacon 插件监听地址                                     | `127.0.0.1`           |
| `BEACON_PORT`            | Beacon 端口                                             | **必填**              |
| `BEACON_KEY`             | Socket.IO 请求密钥                                      | **必填**              |
| `OUTPUT_DIR`             | 输出目录                                                | `tests/output`        |
| `BEACON_PLAYER_UUID`     | 玩家 UUID，用于 player 相关事件                         | 可选                  |
| `BEACON_PLAYER_NAME`     | 玩家名称（当 UUID 缺失时可用）                          | 可选                  |
| `BEACON_MTR_DIMENSION`   | MTR 相关 action 用的维度                                | `minecraft:overworld` |
| `BEACON_MTR_ROUTE_ID`    | `get_mtr_route_detail/get_mtr_route_trains` 用的线路 ID；缺省时自动从 `get_mtr_network_overview` 选取 | 可选 |
| `BEACON_MTR_STATION_ID`  | `get_mtr_station_timetable` 用的站点 ID；缺省时自动从 `list_mtr_stations` 选取，与上面线路匹配优先 | 可选 |
| `BEACON_MTR_PLATFORM_ID` | 指定站台 ID（配合 `BEACON_MTR_STATION_ID`）；缺省时会自动挑选对应站点首个站台/线路匹配站台       | 可选 |
| `BEACON_MTR_DEPOT_ID`    | `get_mtr_depot_trains` 用的车厂 ID；缺省时根据线路寻找关联车厂，若无则取列表首条                 | 可选 |

> 提示：`BEACON_PLAYER_UUID/NAME` 未提供时，会跳过玩家专属事件但仍执行其他测试；MTR 相关 ID 未设置时会自动发现可用样本，再写入 `mtr_auto_targets.json` 供核验。

## 事件分类

### core 分类

- `get_status`
- `get_server_time`
- `list_online_players`
- `get_player_advancements`（需玩家信息）
- `get_player_stats`（需玩家信息）
- `get_player_nbt`（需玩家信息）
- `lookup_player_identity`（需玩家信息）
- `force_update`
- `get_player_mtr_logs`（含分页、按日期、多日范围三种变体）
- `get_mtr_log_detail`
- `get_player_sessions`（分页、按日、JOIN only、按玩家）
- `list_player_identities`

所有响应都会写入 `output/*.json`，文件名包含事件名或参数摘要，方便后续 diff。

### mtr 分类

- `beacon_ping`
- `get_mtr_network_overview`
- `list_mtr_nodes_paginated`（脚本会自动分页拉取直至 `hasMore=false`，结果写入 `output/mtr_nodes_<dimension>/nodes_page_XXXX.json`）
- `list_mtr_depots`
- `list_mtr_fare_areas`
- `list_mtr_stations`
- `get_mtr_route_detail`
- `get_mtr_route_trains`
- `get_mtr_station_timetable`
- `get_mtr_depot_trains`

脚本会自动遍历当前维度下发现的所有路线、站点、车厂，逐一调用上述四个 action，并把响应写入下列目录（若设置了 `BEACON_MTR_*`，仍会优先使用显式 ID，仅对未覆盖的条目进行自动遍历）：

- `output/mtr_routes_<dimension>/route_<id>_detail.json`
- `output/mtr_routes_<dimension>/route_<id>_trains.json`
- `output/mtr_station_timetables_<dimension>/station_<id>_timetable.json`
- `output/mtr_depot_trains_<dimension>/depot_<id>_trains.json`

若某条数据无法获取（例如 Provider 暂无响应），对应文件会记录 `success: false` 与错误信息，方便排查。

### 自动发现逻辑

1. `list_mtr_stations`、`list_mtr_depots`、`get_mtr_network_overview` 成功后，会统计所有可用线路/站点/车厂并寻找与指定维度匹配的条目。
2. 线路优先选择第一条 `hidden=false` 的线路；若未找到则使用列表第一项。
3. 站点/站台优先挑选包含上述线路 ID 的站台，找不到则退回列表首个有站台的站点。
4. 车厂优先挑选关联该线路的车厂，找不到则使用第一条记录。
5. 自动选择的 ID（含名称）写入 `output/mtr_auto_targets.json`，方便对照 Provider 数据；若某类数据为空，会在脚本里提示跳过对应事件。

## 全量导出结构

- `output/mtr_nodes_<dimension>/nodes_page_XXXX.json`：按 `limit=512` 逐页遍历 `list_mtr_nodes_paginated`，直到 `hasMore=false`。同时保留旧的 `output/mtr_nodes_page1.json` 便于快速查看第一页。
- `output/mtr_routes_<dimension>/...`：所有线路的 `get_mtr_route_detail` 与 `get_mtr_route_trains`。
- `output/mtr_station_timetables_<dimension>/...`：每个站点的 `get_mtr_station_timetable`（不带 platformId，默认返回站内全部站台）。
- `output/mtr_depot_trains_<dimension>/...`：每个车厂的 `get_mtr_depot_trains`。

> 注意：如果线路/车站数量较多，导出过程会持续一段时间并生成大量文件，请预留足够磁盘空间再运行。

## 输出格式

每个 JSON 文件包含：

```json
{
  "timestamp": "2025-12-10T12:34:56.789Z",
  "data": { ...socketAckPayload }
}
```

这样方便比较同一事件在不同时刻的响应，也能导入后端进行调试。

## 注意事项

1. 运行前必须先启动 Beacon 插件，并确认 `BEACON_KEY` 与 `config.yml` 保持一致。
2. 如果 Socket.IO 服务端未开启或凭证错误，脚本会在控制台输出错误并退出码 1。
3. 建议将 `OUTPUT_DIR` 指向临时目录，避免覆盖实际生产数据。
4. 需要测试更多事件时，可扩展 `KNOWN_CATEGORIES` 或在现有分类里追加逻辑。
