# heapx — Java 堆保留内存分析后端

定位 Java 堆中保留大块内存的对象。纯后端：Java 17 + Javalin 6.5.0，
HPROF 读取使用 NetBeans Profiler 库（RELEASE230，仅用于读取对象与浅堆字节），
图构建、支配分析与保留量计算均为自研实现。

## 构建与启动

```bash
mvn -B package          # 编译 + 跑测试 + 生成可执行 jar
java -jar target/heapx-1.0.0-jar-with-dependencies.jar
# 默认端口 7070，可用 PORT 环境变量覆盖：PORT=7717 java -jar ...
```

## 分析口径

- **节点**：普通实例与数组（对象数组、原始数组）。不模拟类加载器。
- **根**：转储中的 GC 根（线程栈、JNI、监视器等）以及类静态引用指向的对象。
  分析时加入一个虚拟根，指向所有根对象。
- **边**：实例引用字段（含继承字段，标签为 `声明类#字段名`）与对象数组元素
  （标签为 `[下标]`）。排除 `java.lang.ref.Reference` 声明的 `referent`
  （软/弱/虚引用不算强引用），忽略 null 引用。
- **支配**：对象 d 支配 o 当且仅当从虚拟根到 o 的每条路径都经过 d。
  立即支配者用 Cooper–Harvey–Kennedy 迭代算法在逆后序上计算，
  正确处理环、共享子图与多个根。
- **保留字节**：支配子树中所有对象的浅堆字节之和（不是可达总和）。
  不可达对象单独标记（`unreachable: true`），不参与保留排行。
- **浅堆字节**：取自 NetBeans 读取器的 `Instance.getSize()`；保留量不使用
  任何库计算结果。
- **对象 ID**：HPROF 原始对象 ID 的十六进制字符串（`0x` 前缀），无损传递；
  查询时带不带 `0x` 前缀均可。

## 限制

- 文件 ≤ 100 MiB（超限 422，请求体超限 413）
- 对象 ≤ 50,000（`HEAPX_MAX_OBJECTS` 可调）
- 边 ≤ 200,000（`HEAPX_MAX_EDGES` 可调）
- 断引用规划：目标 ≤ 32、候选 ≤ 1000、单条代价 ≤ 1,000,000,000。
- 损坏文件返回 400；任何失败都不会发布半份分析。
- 每次上传得到独立 `analysisId`，并发上传/查询互不影响；
  `DELETE` 释放全部资源，无持久化。

## API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/analyses` | 上传 HPROF（multipart 字段 `file` 或原始请求体），返回 `analysisId` |
| GET | `/api/analyses/{id}` | 分析概要（对象/边/不可达数） |
| GET | `/api/analyses/{id}/retained?limit=50&offset=0` | 按保留字节降序的对象列表 |
| GET | `/api/analyses/{id}/objects/{hexId}` | 单个对象：类、浅堆、保留量、立即支配者 |
| GET | `/api/analyses/{id}/objects/{hexId}/path` | 到任一根的最短强引用路径（逐边字段/下标） |
| POST | `/api/analyses/{id}/release-plan` | 最低代价断引用规划（见下节） |
| DELETE | `/api/analyses/{id}` | 删除分析并释放资源 |

## 断引用规划（release-plan）

`POST /api/analyses/{id}/release-plan` 计算：要让指定目标对象全部不可达，
在允许断开的强引用清单中，断开哪些引用总代价最小。

**请求体**（JSON）：

```json
{
  "targets": ["0x708c176d0"],
  "candidates": [
    {"from": "0x708c67720", "via": "[0]", "to": "0x708c176d0", "cost": 3}
  ]
}
```

- `targets`：1–32 个目标对象 ID（十六进制，可不带 `0x`），必须存在且不重复。
- `candidates`：0–1000 条允许断开的引用。`from`/`via`/`to` 精确标识一条真实存在的边，
  `via` 与根路径接口的标签一致（`声明类#字段名` 或 `[下标]`）；`cost` 为 1–1,000,000,000
  的正整数。重复候选、非法代价、不存在的对象或引用一律返回 400。
- 所有 GC 根与静态引用目标是固定根，只有清单中的强引用可断开。

**求解语义**：建模为最小割（虚拟源 → 各根 ∞ 容量；非候选边 ∞ 容量；候选边容量为代价；
各可达目标 → 汇点 ∞ 容量），用 Dinic 最大流整体求解——不是逐目标独立求解再拼接，
也不是只断最短根路径，因此共享路径、多根、环、同对象间不同字段都按全局最优处理。
等价最优方案返回任意一个。规划只读：不改变原图、排名与路径查询结果。

**成功响应**（200）：

```json
{
  "analysisId": "...",
  "feasible": true,
  "totalCost": 3,
  "cuts": [{"from": "0x708c67720", "via": "[0]", "to": "0x708c176d0", "cost": 3}],
  "newlyUnreachable": {"count": 1, "shallowBytes": 65560, "objectIds": ["0x708c176d0"]}
}
```

`newlyUnreachable` 是断开前后根可达集合之差：只统计原本可达、断开后变为不可达的对象
（ID 列表、数量、浅堆字节总和），原本就不可达的对象不计入，也不使用任何旧保留值。
目标本来不可达时无需付费（代价 0、cuts 为空）。

**无解响应**（422）：无法释放全部目标时返回 `feasible: false`、原因、
涉事目标，以及一条完全由不可断开引用组成的根到目标证据路径 `evidence`
（逐边字段/下标）。目标本身是 GC 根/静态引用目标时 `targetIsRoot: true` 并明确说明。

删除分析后该 `analysisId` 的规划返回 404。

## curl 演示（真实堆转储）

```bash
# 制造一个真实堆转储
cat > Leaky.java <<'J'
import java.util.*;
public class Leaky {
  static List<byte[]> cache = new ArrayList<>();
  public static void main(String[] a) throws Exception {
    for (int i = 0; i < 20; i++) cache.add(new byte[64 * 1024]);
    Thread.sleep(120_000);
  }
}
J
javac Leaky.java && java Leaky &
jmap -dump:live,format=b,file=real.hprof $!

# 上传（4.7 MiB / 24,066 对象 / 36,691 边）
curl -s http://localhost:7717/api/analyses -F "file=@real.hprof"
# => {"analysisId":"001c721c-...","objects":24066,"edges":36691,"unreachableObjects":207}

# 保留排行：Leaky.cache 的 ArrayList 保留 1.31 MB（20 个 64 KiB 字节数组）
curl -s "http://localhost:7717/api/analyses/<analysisId>/retained?limit=5"

# 根路径：ArrayList --elementData--> Object[] --[0]--> byte[]
curl -s "http://localhost:7717/api/analyses/<analysisId>/objects/0xfc01ded0/path"

# 断引用规划：断开 elementData[0] 释放一个 64 KiB 字节数组（总代价 3）
curl -s -X POST "http://localhost:7717/api/analyses/<analysisId>/release-plan" \
  -H 'Content-Type: application/json' \
  -d '{"targets":["<byte[] id>"],"candidates":[{"from":"<Object[] id>","via":"[0]","to":"<byte[] id>","cost":3}]}'
# => {"feasible":true,"totalCost":3,"cuts":[...],
#     "newlyUnreachable":{"count":1,"shallowBytes":65560,"objectIds":[...]}}

# 断 ArrayList#elementData 字段一次释放整个缓存（21 对象 / 1,311,400 字节）
curl -s -X POST "http://localhost:7717/api/analyses/<analysisId>/release-plan" \
  -H 'Content-Type: application/json' \
  -d '{"targets":["<Object[] id>"],"candidates":[{"from":"<ArrayList id>","via":"java.util.ArrayList#elementData","to":"<Object[] id>","cost":10}]}'

# 释放
curl -s -X DELETE "http://localhost:7717/api/analyses/<analysisId>"
```

## 代码结构

- `heapx.parse.HprofParser` — NetBeans 读取器 → 对象/浅堆/边/根，执行限制
- `heapx.model.HeapModel` — 不可变图（CSR 正/反邻接，id 索引）
- `heapx.graph.DominatorAnalysis` — 虚拟根 + 立即支配者 + 保留量 + 不可达标记
- `heapx.graph.PathFinder` — 反向 BFS 求到根的最短强引用路径
- `heapx.graph.ReleasePlanner` — 最小割（Dinic）整体求解最低代价断引用方案，只读
- `heapx.service.AnalysisService` — 分析注册表、限制、并发隔离、删除
- `heapx.http.Api` / `heapx.Main` — Javalin 路由与启动

## 测试

```bash
mvn -B test
```

- `DominatorAnalysisTest`：合成图验证支配语义（链上共享节点、环、多根共享、
  不可达标记、最短路径用真实边而非支配树边）。
- `EndToEndTest`：用自写 HPROF 生成器（4 字节 ID）产出真实转储文件，
  覆盖静态根、JNI 根、继承字段、对象/原始数组、`Reference.referent` 排除、
  不可达对象，并走完整 HTTP 上传 → 排行 → 根路径 → 规划 → 删除流程；
  另验证对象/边超限与损坏文件拒绝。
- `ReleasePlannerTest`：合成图验证最小割语义（共享前缀只断一次、环与多根、
  同对象不同字段、不可解证据路径、根目标固定、已不可达目标免费、
  新增不可达集合不含原不可达对象、最短路不等于最优割）。
