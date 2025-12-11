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

| 变量名                 | 说明                                                                                         | 默认值/必填           |
| ---------------------- | -------------------------------------------------------------------------------------------- | --------------------- |
| `BEACON_HOST`          | Beacon 插件监听地址                                                                          | `127.0.0.1`           |
| `BEACON_PORT`          | Beacon 端口                                                                                  | **必填**              |
| `BEACON_KEY`           | Socket.IO 请求密钥                                                                           | **必填**              |
| `OUTPUT_DIR`           | 输出目录                                                                                     | `tests/output`        |
| `BEACON_PLAYER_UUID`   | 玩家 UUID，用于 player 相关事件                                                              | 可选                  |
| `BEACON_PLAYER_NAME`   | 玩家名称（当 UUID 缺失时可用）                                                               | 可选                  |
| `BEACON_MTR_DIMENSION` | MTR 相关 action 用的维度（例如 `minecraft:overworld`），用于 `get_mtr_railway_snapshot` 请求 | `minecraft:overworld` |

> 提示：`BEACON_PLAYER_UUID/NAME` 未提供时，会跳过玩家专属事件但仍执行其他测试；`BEACON_MTR_DIMENSION` 只决定 `get_mtr_railway_snapshot` 拉取哪一维度，只要 provider/Beakon 多侧处于运行即可取到快照。

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
- `get_mtr_railway_snapshot`

`get_mtr_railway_snapshot` 会调用 Provider 通道返回 `stations`/`platforms`/`routes`/`depots` 四个结构，然后写出：

- `output/mtr_railway_snapshot.json`（ACK 包含 `success`、`result`、`request_id`、`snapshots`）
- `output/mtr_railway_snapshot_<dimension>.json`（每个快照只保留 `dimension`、`length`、`payload`，其中 `payload` 即 `stations`/`platforms`/`routes`/`depots`）

> 注意：MTR 相关数据来自 Provider 提供的 `RailwayData`，需要 Provider 端与 Bukkit 通道都在线才能成功；`BEACON_MTR_DIMENSION` 决定拉取哪一个维度。

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
