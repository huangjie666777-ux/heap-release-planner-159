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
| DELETE | `/api/analyses/{id}` | 删除分析并释放资源 |

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

# 释放
curl -s -X DELETE "http://localhost:7717/api/analyses/<analysisId>"
```

## 代码结构

- `heapx.parse.HprofParser` — NetBeans 读取器 → 对象/浅堆/边/根，执行限制
- `heapx.model.HeapModel` — 不可变图（CSR 正/反邻接，id 索引）
- `heapx.graph.DominatorAnalysis` — 虚拟根 + 立即支配者 + 保留量 + 不可达标记
- `heapx.graph.PathFinder` — 反向 BFS 求到根的最短强引用路径
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
  不可达对象，并走完整 HTTP 上传 → 排行 → 根路径 → 删除流程；
  另验证对象/边超限与损坏文件拒绝。
