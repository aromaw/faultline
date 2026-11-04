[English](README.md) | 简体中文

# faultline

**租户分支落后主线几个月时，解释 cherry-pick 冲突。**

租户代码很早就从主线分了出去。现在要从主线迁移一个功能（一个或几个提交），cherry-pick 时出现冲突。冲突的代码通常是分叉之后主线新增、重构、改名或删除的代码。`git diff` 只显示冲突的行，看不出相关的调用方和上下文依赖是否都正确。

`faultline conflicts` 对每个冲突块回答五个问题：

| | 问题 | faultline 给出 |
|---|---|---|
| **WHERE 在哪** | 冲突在哪里？ | 文件、行范围以及所在成员（方法、字段、MyBatis 语句、import 区）在 **ours**（租户）、**base**（被 pick 提交的父提交）、**theirs**（被 pick 的提交）三边的位置 |
| **WHY 为什么** | 为什么冲突？ | 从分叉点到 pick 的父提交之间，改过这段代码的主线提交（hash、日期、标题）；成员级历史（新增、修改、改名、移动、签名变化，按方法体识别）；base 行的 `git blame -w -M`；以及租户自分叉以来的修改 |
| **TAKE THEIRS 取主线** | 取 pick 这边需要什么？ | 冲突块 theirs 侧用到的所有符号（方法、字段、类型、Bean、配置项、Mapper 语句、第三方库类型），向下最多 4 层：租户有没有，旧名字是什么，由哪个主线提交引入。也会检查被挪走的 import 和 git 自动合并的行 |
| **TAKE OURS 保留租户** | 保留租户这边会坏什么？ | ours 侧成员在租户里的调用方，一直追到入口：HTTP 方法和路径（`@RequestMapping` 等）、`@PreAuthorize` 权限、`@Scheduled`、`@EventListener` 和 MQ 监听、Runner；还有 pick 里会因此悬空的部分 |
| **CHECKLIST 清单** | 按什么顺序处理？ | 依赖优先：建议先 pick 的主线提交、要带过来的文件、要补进租户文件的符号、需要手工适配的地方（如 `jakarta.*` 与 `javax.*`），然后是逐个解决冲突块，以及需要回归测试的入口 |

pick 中能干净合并的文件也会经过依赖分析：干净合并的代码同样可能用到租户没有的符号。

> 只读。离线模式用 `git merge-tree`，执行的是 cherry-pick 本身的那次合并，不动工作区、index 或任何 ref。实时模式读取 index 的各个 stage 和 `.git/sequencer`。不 checkout，不提交，不推送。

## 构建

需要 JDK 17+、Maven 3.8+、git 2.38+（`git merge-tree --write-tree`）。

```bash
mvn -B package   # 编译 + 单元测试 + 打包
# 产物：target/branch-diff-tool.jar（可执行 fat jar）
```

## 使用

### 实时模式：cherry-pick 进行到一半

```bash
git cherry-pick dbe9834e          # 停在冲突上
java -jar target/branch-diff-tool.jar conflicts --repo . --mainline master --html -o conflicts.html
```

自动识别 `CHERRY_PICK_HEAD` / `MERGE_HEAD` / `REVERT_HEAD`。读取未合并的 index 条目（stage 1/2/3，包括 modify/delete、add/add 冲突），以及 sequencer 中还没应用的提交。不带 `--theirs`、也不带 `--pick` 时，默认子命令就是 `conflicts`。

### 离线模式：pick 之前先看

```bash
# 单个提交
java -jar target/branch-diff-tool.jar conflicts --repo . --onto tenant --pick dbe9834e --mainline master --html -o role.html
# 多个提交：范围，或逗号分隔的列表，按顺序应用
java -jar target/branch-diff-tool.jar conflicts --repo . --onto tenant --pick '0728a04b^..d454d972' --mainline master -o notice.md
```

每个 pick 用 `git merge-tree --write-tree --merge-base=<pick>^ <当前状态> <pick>` 模拟。范围有多步时，后一步合到前一步的结果上。这个结果是用 `git commit-tree` 生成的临时提交，没有任何 ref 指向它，git 之后会自行回收。冲突块按步骤加 `s<N>:` 前缀。

| 选项 | 说明 |
|---|---|
| `--repo <path>` | git 仓库（默认 `.`） |
| `--mainline <ref>` | 主线分支。分叉点为 `merge-base(租户, 主线)`，默认 `merge-base(租户, pick^)` |
| `--onto <ref>` | 离线模式的租户 ref（默认 `HEAD`） |
| `--pick <spec>` | 单个提交、`c1,c2,...` 或范围 `A..B` |
| `--format md\|json\|html`、`--json`、`--html` | Markdown（默认）、JSON，或单个自包含 HTML |
| `-o, --output <file>` | 输出到文件，默认 stdout |
| `--no-maven` | 不运行 `mvn dependency:build-classpath`。速度更快，但第三方库类型只能按启发式判断（见局限） |
| `--fail-on-findings` | pick 需要的符号租户缺失时，退出码为 2（适合 CI） |
| `--include-tests`、`--keep-temp`、`-v` | 同时分析 `src/test/java` / 保留临时源码 / 打印进度 |
| `--no-cache` | 不读写摘要缓存（见下文）。每个文件都重新解析，报告内容相同 |

### 输出

- **HTML**：单个离线文件，CSS 和 JS 全部内联。
  - 侧栏先列冲突块，按文件分组，并标出 modify/delete。然后是迁移清单，以及 pick 干净部分的发现。
  - 每个冲突块有 **Conflict / Why / Take theirs / Take ours / Checklist** 五个标签页。Conflict 页是类似 IDE 的 ours | base | theirs 视图，当前冲突块描边，其他冲突块加底色。
  - 点击符号、历史事件或调用链节点，可以看到该成员在三边的版本。
  - 打开上下文标签页时，代码区会自动切换为 ours | theirs，顶栏的开关可以切回三栏。
  - URL hash 保存当前状态，如 `#hunk=<id>&ctab=c-why`；`#hunk=__check` 直接打开清单。
- **JSON**：同样的数据放在 `conflicts` 下，同时保留分支分析的字段。
- **Markdown**：先是清单，然后每个文件、每个冲突块一节（where / why / take theirs / take ours），之后是 git 自动合并的行，最后是干净合并的文件。

![Why 页：冲突背后的租户修改和主线历史](docs/conflict-why.png)
![Take theirs：pick 需要的符号、租户有没有、由哪个提交引入](docs/conflict-take-theirs.png)
![迁移清单，依赖优先](docs/conflict-checklist.png)

### 原理

1. **会话**。
   - 实时模式：读取 `ls-files -u -z` 的 stage、`CHERRY_PICK_HEAD` 和 `.git/sequencer/todo`。
   - 离线模式：读取 `merge-tree` 的输出，包括 tree、冲突 stage 和提示信息。
   - 分叉点为 `merge-base(ours, mainline)`。窗口在主线上是 `fork..base`，在租户上是 `fork..ours`。
2. **冲突块**。对每个冲突文件的三个 stage blob 运行 `git merge-file --diff3`，得到三边精确的行范围。只有换行符不同时，先去掉 CR 再算冲突块，并在报告中建议使用 `-X ignore-cr-at-eol`。
3. **成员**。用 JavaParser 解析 Java，并解析 MyBatis XML 语句和 yml/properties 配置项。每个冲突块映射到它覆盖的成员。
4. **历史**。对 base 侧，沿 `git log fork..base` 跟踪文件。对每个提交前后的成员做 diff，先按名字配对，再按方法体相似度（token Dice ≥ 0.75）配对，以识别改名、移动和签名变化。对 base 行运行 `git blame -w -M`。
5. **取主线**。对 theirs 侧成员求依赖闭包，再与租户的索引对照，判断每个符号是存在、缺失、改名还是不兼容。引入它的提交从成员历史中得到。
6. **保留租户**。在租户的反向调用图中，从 ours 侧成员一直追到入口。
7. **清单**。需要的符号按文件分组，依赖排在前面。

### 摘要缓存

每个版本的索引保存每个 Java 文件的摘要（类型、成员、import、注解；成员文本只保存指纹）。摘要只取决于文件内容，
因此按 git blob id 存到磁盘上重复使用：在 fork、base、ours、theirs 中内容相同的文件只解析一次，之后的运行只解析
没见过的文件。

- 位置：`~/.faultline/cache/summaries/<key>/<2 位十六进制>/<38 位十六进制>`，每个 blob 一个小文件。`<key>` 由摘要格式、
  faultline 版本、提取代码与 JavaParser 版本的哈希组成：代码不变时重新构建 jar 仍沿用缓存，提取逻辑一旦变化就使用新缓存。
- 只读取本次分析涉及的文件对应的条目，逐个读取，不加载其他内容。
- 先写临时文件再重命名到位，并发运行或中途崩溃都不会留下半个条目。每个条目带 blob id、长度和 CRC-32；过短、损坏或
  无法解码的条目会被忽略、删除并重新写入。
- 大小：默认最多 512 MB。某次运行写入了条目、且距上次检查超过一天时，后台线程按最近最少使用删除条目（先删其他构建的），
  直到降到上限的 80%。
- `FAULTLINE_CACHE_DIR`（或 `-Dfaultline.cacheDir=…`）修改位置，`FAULTLINE_CACHE=off`（`-Dfaultline.cache=off`，或
  `--no-cache`）关闭缓存，`FAULTLINE_CACHE_MAX_MB`（`-Dfaultline.cacheMaxMb=…`）设置上限。
- `faultline cache info` 按 key 显示大小，`faultline cache prune` 立即修剪，`faultline cache clear` 全部删除。

在 ruoyi-vue-pro 上（四个版本，解析 17476 个 Java 文件，8679 个不同的 blob），缓存条目共 11 MB；缓存命中时解析
0.5–0.7 s，未命中时 2.6–3.0 s。缓存冷、热或关闭时，报告逐字节相同。

## 桌面 GUI

一个很小的 Swing 窗口（FlatLaf + RSyntaxTextArea；不用 JavaFX，也不内嵌浏览器），直接调用同一套分析代码，
不调用命令行，也不会修改仓库。

```bash
scripts/faultline gui                  # 带调优 JVM 参数的启动脚本（推荐）
java -jar target/branch-diff-tool.jar gui
java -jar target/branch-diff-tool.jar  # 桌面环境下不带参数也会打开 GUI
```

窗口顶部按编号列出四个步骤，完成的步骤会变成绿色对勾。

1. **Project（项目）**：还没打开项目时，侧栏显示 *Open Folder…* 和最近项目（点一下即可打开），也可以把文件夹拖到窗口上。
   之后，顶部的项目按钮会先列出最近项目，再给出 *Open Folder…*。macOS 上的文件夹对话框是系统原生的 Finder 对话框。
   最近项目记在 `~/.faultline/gui.properties`。
2. **Branches（分支）**：*Pick onto (target)* 是 tenant 分支，*Commits from (mainline)* 是提交所在的主线分支。
   点分支按钮（或让按钮获得焦点后直接打字；Ctrl/⌘+T、Ctrl/⌘+M 也能打开）会弹出类似 IntelliJ IDEA 的
   分支搜索框：本地分支和远程分支分两组列出，边输入边过滤（不区分大小写的子串匹配；空格分隔的每个词都要
   匹配，例如 `origin acme` 能找到 `origin/tenant/acme`）。↑/↓ 移动，Enter 选中，Esc 关闭。
3. **Commits（提交）**：Commits 页列出 `target..mainline` 的提交（不含合并提交，最多最近 5000 个）。可以按提交
   信息、作者或哈希过滤（`author:`、`sha:` 只匹配对应字段）。勾选 Pick 列，或选中若干行后按空格 /
   点 *Pick Selected*；*Clear Picks* 取消全部勾选。不管点选顺序如何，都按从旧到新的顺序应用。
4. **Analyze（分析）**：蓝色的 *Analyze N commits* 按钮（Ctrl/⌘+Enter）在后台跑离线模拟（`git merge-tree`）。
   状态栏显示当前阶段、*Cancel* 按钮（在进入下一阶段时生效）和 *Memory X of Y*：上一次垃圾回收后仍在使用的
   Java 堆，以及堆上限。（“已用堆”还包括尚未回收的垃圾，即使实际占用很少也会逐渐涨到接近上限；点击该标签可立即回收。）

结果：

- Conflicts 页是 文件 › hunk 树，带 HIGH / MEDIUM / LOW 标签；上方一行汇总（冲突 hunk 数、冲突文件数、
  干净合并的文件数、检查清单步骤数、需要考虑的提交数）。
- hunk 视图并排显示 Ours（tenant）| Base（pick 的父提交）| Theirs（本次 pick），也可切成 Ours | Theirs，
  滚动同步；下方页签为 *Why it conflicts*（为什么冲突）、*Take theirs → needs*（主线代码的依赖）、
  *Keep ours → callers*（tenant 中受影响的入口和调用方）、*Checklist* 和 *Dependency chain*。三栏上方有一行提示，
  说明各栏是什么（点 *Hide* 永久隐藏）。点击任意一行或 `File.java:123` 这类引用，会在右侧只读打开代码；
  右侧面板在第一次点击时才出现。
- 第一次用？分析结束后树中第一个冲突已被选中：先读 *Why it conflicts*，再按准备保留的一侧看
  *Take theirs → needs* 或 *Keep ours → callers*，最后按 *Checklist* 逐步处理。
- *Export* 导出与命令行相同的 HTML / Markdown / JSON 报告。
- *Dark* / *Light*（或 View › Toggle Light / Dark Theme）切换深色/浅色主题。

侧栏（Commits / Conflicts）可以拖到任意宽度，直到完全隐藏。View › *Show Sidebar*（Ctrl/⌘+B）或分隔条上的箭头可以隐藏
和恢复侧栏，宽度会被记住。

出错时窗口会显示错误，并把详细信息写入 `~/.faultline/logs/gui.log`；通过 `scripts/faultline` 启动时，JVM 的崩溃报告
（`hs_err_pid*.log`）也放在同一目录。报告问题时请附上这些文件。

每个按钮都有文字和提示（tooltip）。没有内容的面板会提示下一步该做什么。窗口在所有平台上都使用 FlatLaf 的
macOS 风格浅色/深色主题：圆角控件、无网格线的表格、浅色底的标签和徽章。字体为内置的 Maple Mono NF CN
（带中文字形的等宽 Nerd Font），窗口、代码和图标都用它（图标取自字体里的 VS Code codicons）。中文字形必须在字体本身里：
macOS 上的 Java 不会为从 jar 加载的字体回退到其他字体。View › *Nerd Font (Maple Mono, with Chinese)* 可切回系统字体，
选择会被记住。字体文件采用 SIL Open Font License（`src/main/resources/com/branchdiff/gui/fonts/OFL.txt`），让 jar 增大约 19 MB。

严重度标签只是 GUI 的启发式标记：HIGH 表示文件被删除，或者有缺失/已改动、但不来自本次 pick 的依赖；
MEDIUM 表示入口点、孤儿代码、tenant 的改动或不确定的依赖。

应用图标（窗口、任务栏、macOS Dock）是手写的 `assets/icon/faultline.svg`；`assets/icon/render.py`
生成 PNG（16–1024 px）、`faultline.icns` 和 `faultline.ico`。macOS 上（`faultline.icns` 和 Dock 图像）图标按苹果的
网格绘制：1024 px 画布上一个 824 px 的圆角方块加柔和阴影，因此和 Dock 里其他图标一样大；窗口图标以及
Windows / Linux 图标则铺满整个方块。

### 内存与启动

`scripts/faultline` 使用
`-XX:+UseG1GC -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30 -XX:GCTimeRatio=4 -Xmx<堆大小> -XX:CICompilerCount=2`
（macOS 上另加 `-Xdock:name=faultline -Xdock:icon=…/faultline.icns`）。可用环境变量：

- `FAULTLINE_XMX`：堆上限，例如 `4g`。默认取物理内存的四分之一，最少 1 GB，最多 8 GB。启动脚本在 macOS 上用
  `sysctl -n hw.memsize` 读内存，在 Linux 上读 `/proc/meminfo` 和 cgroup 限制（容器）。约 7000 个 Java 文件的
  Spring/MyBatis 项目（ruoyi-vue-pro）分析一个提交时存活堆峰值约 0.19 GB，十个提交约 1.3 GB；RuoYi-Vue 用 256 MB 也能跑完。
- `FAULTLINE_GC_OPTS`：替换收集器参数（上面的前四个），例如 `-XX:+UseSerialGC`。
- `FAULTLINE_JAVA_OPTS`：额外参数，放在最后，因此可以覆盖任何默认值。例如：
  - `-Dfaultline.parseThreads=N`：解析（或从摘要缓存读取）一个版本的文件所用的线程数。默认等于处理器数，最多 8。
  - `-Dfaultline.gitThreads=N`：冲突分析时并行读取文件历史（`git log`）的 git 进程数。默认等于处理器数，最多 8。
  - `-XX:ReservedCodeCacheSize=48m` 可少占约 20 MB 空闲 RSS，但缓存未命中时分析慢约 11%。
  - `-XX:TieredStopAtLevel=1` 可少占约 30 MB RSS，但分析慢约 40%。
- `FAULTLINE_CDS=1`：开启 AppCDS，第一次运行时把归档写入 `~/.cache/faultline/`。JDK 19+ 用
  `-XX:+AutoCreateSharedArchive`；JDK 13–18 先用 `-XX:ArchiveClassesAtExit`，之后用 `-XX:SharedArchiveFile`。
  手动方式：先运行 `java -XX:ArchiveClassesAtExit=fl.jsa -jar …jar gui`，
  以后运行 `java -XX:SharedArchiveFile=fl.jsa -jar …jar gui`。

为什么是这些参数（在 ruoyi-vue-pro 上用 GUI 自动化测量，每项 3–5 次；结果见下表）：

- G1 加较低的 free ratio 会在分析结束后把堆还给操作系统，分析后 RSS 约 210 MB。Serial GC 峰值略低（约 445 MB），
  但分析后仍常驻约 440 MB。
- `-XX:GCTimeRatio=4` 允许 G1 在扩大堆之前花最多约 20% 的时间做 GC（默认 12，约 8%）。缓存未命中时峰值 RSS 从约
  1.15 GB 降到约 0.8 GB，缓存命中时从约 0.64 GB 降到约 0.54 GB，分析时间约多 2%。
- 解析线程由 16 个改为 8 个，缓存未命中时峰值再降约 130 MB，且不变慢。
- 没有可测出的效果，因此不采用：`SoftMaxHeapSize`（JDK 21 的 G1 忽略它）、`G1PeriodicGCInterval`（GUI 在分析后
  已主动 GC）、`UseStringDeduplication`、`MaxMetaspaceSize=128m`（metaspace 只用约 20–40 MB）、`-Xms64m`、
  `MALLOC_ARENA_MAX=2`。

直接 `java -jar … gui` 时使用 JVM 默认堆，同样是物理内存的四分之一；如果低于约 900 MB，启动时状态栏会提示。

如果分析仍然内存不足，GUI 会停止分析、释放其数据和缓存，并弹出提示：当前上限是多少，以及如何用更大的堆
重新启动（`FAULTLINE_XMX=2g scripts/faultline gui` 或 `java -Xmx2g -jar …`）。窗口可以继续使用。

分析如何控制内存：

- 代码索引只保留每个 Java 文件的摘要（类型、成员、import、注解）。完整语法树按需重新解析，放进一个按源码大小
  限额的缓存（默认堆的 1/160，至少 200 万、最多 800 万字符，约 250 MB 语法树；`-Dfaultline.astCacheChars=N`）。
  加上 800 万的上限之前，4.9 GB 的堆对应 3200 万字符的额度，ruoyi-vue-pro 的 10 个提交的分析存活堆为 2.9 GB 而不是
  1.3 GB，耗时和报告都相同。修复前，四个版本（fork、base、
  ours、theirs）的所有文件都一直保持解析状态，每个源码字符约占 25–30 字节堆，这就是大项目上
  `Java heap space` 报错的原因。
- 全项目扫描（调用方、类型使用、注入）先在源码文本里查找标识符，只解析提到它的文件。
- symbol solver 的单文件缓存有上限（`-Dfaultline.solverCacheSize=N`，默认 64），各版本之间相同的文件内容共享。
  solver 解析的文件同样丢弃 token。（每个源码根 16 个时，10 个提交的分析再少约 200 MB，但慢 60 s；4 个时 10 分钟内没跑完。）
- symbol solver 把解析结果存放在语法树节点上，而解析出的类型指向它找到的声明，也就是 solver 解析的某个文件。
  因此缓存中的一棵语法树会让它解析过的所有文件一直留在内存里，即使 solver 早已丢弃它们。现在每当语法树缓存约
  一半被替换，就从缓存的语法树上清掉这些数据（需要时重新解析，结果相同）。
- 解析完就丢弃 parser token；反向边（“谁调用了它”）只对需要的符号按需解析。每次分析结束都会清空 JavaParser 缓存。
- 摘要中的成员文本只保存 128 位指纹（报告用到的少数文本从语法树重新打印），注解只保存源码文本。解码出的字符串
  会被 intern，多个文件、多个版本共用的名称和类型只存一份；按 id 查方法时经由其所属的类，不再维护所有方法 id 的 map。
- 见过的文件直接从[摘要缓存](#摘要缓存)读取摘要。
- `-Dfaultline.heapLog=true` 会在分析的每个阶段做一次 full GC 并打印存活堆大小；`-Dfaultline.heapHisto=N`
  另外打印占用最多的 N 个类。`-v` 会打印 git 命令的调用次数和耗时（`git: log 412 calls 61.2 s, …`）。

实测环境：Linux（JDK 21，16 核，19 GB，磁盘缓存已预热），通过 `scripts/faultline` 用 GUI 自动化运行。
ruoyi-vue-pro：把 `5f839f5bb0` pick 到 `tenant`（从 master 的 `91870831` 分叉，3697 个 Java 文件；master 有 7622 个），
主线 `master`，共解析 17476 个 Java 文件。每项 3 次。*之前* 为 `ebf2f14`（用它自己的启动脚本，没有摘要缓存）；
*冷* / *热* 指摘要缓存。

| | 堆 | 窗口显示 | 空闲 RSS | 峰值 RSS | 分析后 RSS | 分析耗时 |
|---|---|---|---|---|---|---|
| 之前 | 默认（4.9 GB） | 0.66–0.78 s | 127 MB | 1663–1705 MB | 267–275 MB | 10.4–10.9 s |
| 现在，冷缓存 | 默认（4.9 GB） | 0.64–0.67 s | 127–128 MB | 630–786 MB | 246–254 MB | 7.3–8.5 s |
| 现在，热缓存 | 默认（4.9 GB） | 0.68–0.71 s | 126–127 MB | 481–613 MB | 204–216 MB | 3.7 s |
| 之前 | `FAULTLINE_XMX=1g` | 0.62–0.71 s | 126 MB | 927–974 MB | 226–230 MB | 10.8–11.4 s |
| 现在，冷缓存 | `FAULTLINE_XMX=1g` | 0.57–0.64 s | 126 MB | 507–538 MB | 211–223 MB | 8.0–8.4 s |
| 现在，热缓存 | `FAULTLINE_XMX=1g` | 0.68–0.70 s | 125–126 MB | 390–467 MB | 183–190 MB | 3.8 s |

窗口显示时间包含启动脚本以及测量程序启动进程的时间。存活堆峰值（命令行，`-Xmx1g -Dfaultline.heapLog=true`）
从 460–461 MB 降到 188 MB，冷热缓存相同。摘要缓存共 8683 个条目：数据 11 MB，按 4 KB 块计占用 35 MB。JSON、
Markdown 和 HTML 报告与之前逐字节相同，缓存冷、热或关闭都一样。

一次分析多个提交：主线最新的 10 个提交（`--pick master~10..master`，pick 到 `tenant`，命令行，`--no-maven`）：
242 个文件中 665 个冲突 hunk，610 条 finding。每项 1 次，同一台机器：

| | 堆上限 | 分析耗时 | 峰值 RSS | 存活堆峰值 |
|---|---|---|---|---|
| `9b43304` | 4.9 GB（默认） | 30 分钟内未完成 | 22 分钟时 5.1 GB | — |
| 只修复冲突分析 | 4.9 GB（默认） | 432 s | 5.3 GB | 3.4 GB |
| 现在 | 4.9 GB（默认） | 199–213 s | 3.7 GB | 1.26 GB |
| 现在 | `FAULTLINE_XMX=4g` | 201 s | 3.8 GB | |
| 现在 | `FAULTLINE_XMX=2g` | 207 s | 2.3 GB | |

G1 会把堆扩展到给它的空间里，所以峰值 RSS 随上限变化；存活堆（分析真正持有的内存）峰值为 1.26 GB，2 GB 堆也一样快。
慢和大的原因：

- 对每个冲突 hunk，“保留 ours”检查都会重新收集 pick 改动的每个方法的依赖（hunk 数 × 方法数次符号解析）。现在每次分析只收集一次。
- 其余时间大多花在 `git` 上：约 1000 次 `git log --follow`（在这段历史上每次约 0.5 s；`--follow` 用不了 commit-graph 的
  changed-path 过滤器）、11000 个 `git cat-file` 进程，以及每个符号一次、没有缓存的 `git diff -M` 和 `git log -S`。现在当普通的
  路径日志只包含修改时就用它（快约 60 倍，提交相同；否则仍用 `--follow`），分析要用的文件历史由最多 8 个 git 进程并行读取
  （`-Dfaultline.gitThreads`），文件版本由一个 `git cat-file --batch` 进程读取（27.5 s → 2 s），重命名和 pickaxe 结果做了缓存。
- 接收者类型未知的调用会和所有类的所有方法比对，而检查 varargs 时还会解析每个候选方法所在的文件；现在有了“名称 → 方法”
  索引，摘要里也记录了 varargs 标志（摘要格式 2：旧格式的缓存条目会重建一次）。
- 内存：语法树缓存额度上限为 800 万字符，缓存的语法树上不再保留 solver 的解析数据，solver 解析的文件丢弃 token（见上文）。

报告与之前相同，有两处预期内的差别。模拟提交（`faultline simulated pick of …`）现在使用其 pick 的日期，因此 SHA 和报告每次
运行都相同。在这个例子上，context 视图的依赖链之前会因 `NullPointerException` 失败（只表现为警告 `context view failed`），
现在可以正常生成。有一条依赖树的边（`RedisMQTemplate#send(T)`）会随缓存状态在“已解析”和 *uncertain* 之间变化，这是
symbol solver 原有的行为，不是新问题。改动后重新测量，上面单个提交的 GUI 数据没有变化（冷缓存 7.4 s、峰值 700–762 MB；
热缓存 3.5–3.6 s、511–527 MB）。

更早，在改为只保留摘要的索引之前，ruoyi-vue-pro 在 1 GB 下报 `Java heap space`，需要 6 GB 堆（RSS 2.8–3.6 GB，
34–37 s）。RuoYi-Vue `dbe9834e`（8 核，16 GB，摘要缓存之前）：约 0.6 s 显示窗口，空闲约 140 MB，峰值 RSS 495–630 MB，
3.5–3.7 s。GUI 让 jar 增大约 2 MB，未用到的语法高亮模式已剔除。以上数据尚未在 macOS 上测过。

## 在 RuoYi-Vue 上的验证

`/workspace/oss-eval/conflict-mode/SCENARIO.md` 记录了在 RuoYi-Vue 克隆上的一次真实运行。租户分支于 2025-10-05 分叉，带有租户自己的修改。从主线 pick 了两个最近的功能：

- 角色权限变更后刷新在线用户权限，它依赖 Spring Boot 4 的 `jakarta` 迁移；
- 通知公告阅读用户列表，它依赖分叉之后新增的整套“已读”子系统。

两种模式（实时和离线）都跑过，其中 3 个冲突块用 `file:line` 和提交逐一手工核对过。

## 局限（冲突模式）

- 离线模式用 `git merge-tree`，而不是临时 worktree。在默认选项下，它的合并结果和 stage 与 `git cherry-pick` 一致；但不会应用自定义 merge driver、`rerere`、`-X` 策略选项和 hook。
- 符号分析只覆盖 Java、MyBatis XML、yml/yaml 和 properties。`.vue`、`.js`、SQL 等文件的冲突块会定位并归因到历史（WHERE、WHY），但不分析内容。
- 使用 `--no-maven` 或 classpath 无法解析时，第三方库类型按启发式判断：租户源码里没有任何文件从该包 import，就算缺失。租户里对应的类型按名字匹配（如 `jakarta.servlet...` 对应 `javax.servlet...`）。这类条目请对照租户的 pom 核实。
- 纯静态分析：反射、AOP、字符串形式的 Bean 名、SpEL 和 XML 装配都不跟踪，仅按名字匹配的调用会标为不确定。依赖链最多 4 层；调用方一直追到入口为止。
- 改名识别要求方法体至少有 6 个 token，过短的成员（getter、单行方法）会显示为删除 + 新增。
- 工具不会替你解决冲突，也不写文件，只负责解释冲突，由你来解决。

---

## 分支模式（次要）

原来的整分支对比仍然可用。它在**方法级**比较 merge-base、ours、theirs，报告 theirs 改了、而 ours 缺失或不一致的代码，包括 git 不报冲突的缺口。

```bash
java -jar target/branch-diff-tool.jar branch --repo . --ours HEAD --theirs origin/feature-x --service-dir order-service --html -o report.html
# （不写子命令、但带 --theirs 时，同样进入分支模式）
```

| 选项 | 说明 |
|---|---|
| `--ours <ref>` / `--theirs <ref>` | 两个分支或提交，merge-base 自动计算 |
| `--service-dir <dir>` | 只分析该子目录 |
| `--include-siblings [deps\|all\|none]`、`--no-siblings` | 把同仓库的兄弟 Maven 模块加入索引（默认 `deps`：服务 pom 依赖的模块） |
| `--mvn <cmd>` / `--maven-timeout <sec>` | Maven 命令和超时 |


### HTML 报告

`--html`（等同 `--format html`）生成**一个自包含的 `.html` 文件**，界面仿 IntelliJ IDEA 的 diff/merge 视图。所有 CSS 和 JavaScript 都内联：不依赖 CDN、不访问网络，可离线打开，也可以直接附到工单或邮件里。

```bash
java -jar target/branch-diff-tool.jar --repo . --theirs feature-x --service-dir order-service --html -o report.html
```

![HTML 报告：双方都修改的方法，三栏视图](docs/html-report.png)

- **侧边栏**：按严重级别（HIGH / MEDIUM / LOW / UNCERTAIN）再按类别分组的问题树，带计数、按组过滤开关和搜索框（符号、文件、消息、状态）。
- **顶栏**：仓库、服务目录、ours / merge-base / theirs 的引用与提交，以及各级别数量。
- **问题卡片**：严重级别、状态、种类、符号、来源、说明、详情，以及所有位置（`needed by` / `used at` / ours / theirs / base），点击即跳转，必要时自动切换文件。
- **差异视图**：三栏 `ours | merge-base | theirs`（可切换为两栏 `ours | theirs`），带行号；所有栏位在同一个滚动容器中，同步滚动；新增（绿）、修改（蓝）、删除（灰）、冲突（红，双方改了同一段 base 行）分块着色，并有词级行内高亮；未改动区域折叠为“expand N lines”行（也可 *Expand all*）。当前问题所在行会高亮并自动滚动到视野中央。
- **语法高亮**：Java、XML（标出 MyBatis 的 `#{}` / `${}`）、YAML、properties（内置的小型分词器）。
- **键盘**：<kbd>↑</kbd>/<kbd>↓</kbd> 或 <kbd>j</kbd>/<kbd>k</kbd> 切换问题，<kbd>F7</kbd> / <kbd>Shift+F7</kbd> 跳到下一处 / 上一处改动。
- **主题**：IDEA Light（默认）和类 Darcula 的深色主题。
- **URL hash** 保存状态，例如 `report.html#finding=3&mode=2col&theme=dark`（`target=N` 选中该问题的第 N 个位置；`tab=deps|impact|check|overview`、`node=<key>`、`group=chain`、`ov=all` 打开上下文视图），方便分享链接和无头浏览器截图。

只嵌入问题涉及的文件，内容直接从 git 的三个版本读取；超过 1 MB 的版本不嵌入并给出说明。行级 diff（Myers 算法，[java-diff-utils](https://github.com/java-diff-utils/java-diff-utils)，Apache-2.0）在 Java 端计算后以 JSON 嵌入；三栏视图按 merge-base 区间把 ours 与 theirs 的改动分组，做法与 `diff3` 相同。`docs/sample-report.html` 是下文验证样例的报告。

#### 上下文与依赖链

每个问题还带有代码上下文，由同一套三方索引计算（JSON 报告中的 `context` 字段，Markdown 第 7 节）：

- **Dependency chain** 页签：从 theirs 中需要该符号的代码（例如 Controller 方法）到缺失或变化的符号，再到该符号*自身*的依赖，逐层展开（方法、构造器、字段与常量、类、Spring Bean、配置项、MyBatis 语句、第三方库类型及提供它的 Maven 构件）。每个节点显示种类、`文件:行号` 和在 ours 中的状态：`missing`（缺失）、`changed`（签名或类型不兼容）、`differs`（存在但 theirs 改过）、`present`、`unknown`，以及由哪一方修改。只展开 ours 缺失或有差异的节点（最多 4 层，环会标出并截断）；存在且未变的依赖折叠为“N more”。
- **Reverse impact** 页签：对被删除、改名、改类型或改值的符号（SIGNATURE_IMPACT、CONSTANT_VALUE、BOTH_MODIFIED 等），列出 ours 中使用它们的代码，最多 3 层调用方，带 “uses it at L..” 链接。
- **Port checklist** 页签：属于同一条链的问题会归为一组（`⛓ C1`，侧边栏 *By chain*），并给出一份按依赖优先排序的移植清单，每一步附动作（“Add constructor”、“Add the dependency to ours' pom.xml”、“Update this method in ours” 等）。勾选状态不保存。
- **Overview** 页签：内联 SVG，展示该链（或全部链）涉及的文件，从需要方指向提供方，按最严重状态着色。
- 点击任意节点，右侧并排显示它所在的完整成员（ours / merge-base / theirs），沿用原有的 diff 高亮；不包含该成员的版本不显示（变为一栏或两栏）。面板与代码之间的分隔条可拖动。

![RuoYi-Vue 真实问题的依赖链](docs/html-report-deps.png)


### 检测内容

1. **方法级变更集**（base→theirs、base→ours）：按 `类全限定名#方法名(参数类型...)` 识别 ADDED / REMOVED / MODIFIED / SIGNATURE_CHANGED（同名不同参）/ RENAMED（方法体相同、名字不同）。比较时去掉注释和格式，因此只改注释、格式或位置不算修改。
2. **依赖闭包**：对 theirs 新增/修改的每个方法，收集其调用的方法、构造器、引用的类型、字段、常量（含枚举常量、方法引用、注解），逐一检查 ours 中是否存在且签名兼容：
   - `MISSING`（不存在）/ `INCONSISTENT`（存在但参数、返回类型、static 等不一致）；
   - 来源 `origin`：`THEIRS_NEW`（theirs 新引入，需要一起迁移）、`OURS_REMOVED` / `OURS_CHANGED`（merge-base 中有、ours 删除或修改了——**git 不会报任何冲突**，最危险）；
   - 整个类在 ours 中缺失时合并成一条 TYPE 记录。
   - 使用 JavaParser SymbolSolver（项目源码 + JDK + `mvn dependency:build-classpath` 得到的 jar）；解析失败时退化为按名称/参数个数匹配，并标记为 **UNCERTAIN**，不会中断。
3. **Spring 专项**
   - 注入点：`@Autowired` / `@Inject` / `@Resource` 字段、构造器注入（含 Lombok `@RequiredArgsConstructor` / `@AllArgsConstructor`）、`@Autowired` setter、`@Bean` 方法参数、`@Qualifier` / `@Resource(name=)` 名称。检查 ours 中是否有对应 bean（`@Component/@Service/...`、`@Bean`、MyBatis Mapper、Spring Data Repository、`@FeignClient`）。
   - 配置键：`@Value("${...}")`（有默认值的不报）、`Environment.getProperty("...")`、`@ConfigurationProperties(prefix)` 的字段，对照 ours 的**所有** `application*.yml|yaml|properties`、`bootstrap*`（所有 profile，含多文档 yml；支持宽松绑定 `max-items` ≈ `maxItems`）。
   - 接口/抽象方法：theirs 在接口（非 default）或抽象类中新增的抽象方法，ours 的哪些实现类没有实现；区分"ours 独有的实现类"（HIGH，合并后必然编译失败）和"theirs 版本已实现、随合并带来"（LOW）。
   - MyBatis：Mapper 接口方法（无 `@Select` 等注解时）与 XML `namespace` + `<select|insert|update|delete id>` 的对应；theirs 新增的 XML id 在 ours 中缺少接口方法或语句。
   - Maven：theirs 的 pom 中新增、ours 没有的依赖。
4. **签名变更反向影响**：theirs 删除、重命名、改参数或改返回类型的方法（以及删除的类型），找出 ours 中仍按旧形式调用的位置（file:line）。调用方是 ours 新增/修改的代码 → HIGH（合并不会帮你改）；调用方未变且 theirs 已更新 → LOW；调用方在 ours 未变、在 theirs 已删除或改名 → LOW（整分支合并会一并删除；cherry-pick 时需复查）；其他 → MEDIUM。
   - **字段/常量反向追踪**（`STILL_USES_REMOVED_FIELD`）：theirs 删除或改名的字段（实例字段、`static final` 常量、接口常量、枚举常量；所在类在 theirs 中仍存在、ours 仍保留该字段），扫描 ours 中仍引用它的位置：方法/构造器体、字段初始化表达式、枚举常量参数、初始化块，支持 `Type.NAME`、`this.x`、无限定名、`import static`。优先用 SymbolSolver 确认引用的就是该声明（同名局部变量、其他类的同名常量不会误报）；解析失败时退化为按 import/作用域匹配并标记 UNCERTAIN。
   - 改名识别：theirs 在同一类中新增的字段类型、static/枚举属性相同且**初始化表达式相同（非空）**且唯一 → `*_RENAMED`（报告给出新名字），否则为 `*_REMOVED`。
   - 严重度规则与方法相同：引用所在的方法/声明是 ours 新增或修改的（或整个类只有 ours 有）→ HIGH；ours 未改动、theirs 版本已不再引用或已删除 → LOW；其他 → MEDIUM。同一成员内的多处引用合并为一条（`moreReferences`）。
   - 反方向（ours 删除了字段/常量，而 theirs 新增/修改的代码仍需要它）由第 2 项依赖闭包报告为 `MISSING … OURS_REMOVED`（样例 G1c），因此两个方向都覆盖。
5. **兄弟模块**（`--include-siblings`，默认 `deps`）：
   - 发现：读取每个版本（base/ours/theirs）的全部 `pom.xml`，从服务 pom 的 `<dependency>` 出发（忽略 `test` scope），按 `groupId:artifactId` 匹配仓库内的模块（groupId 写成 `${project.groupId}` 等属性时按唯一 artifactId 匹配），并沿兄弟模块自己的依赖传递；三个版本的结果取并集，再从每个版本加载这些模块的 `src/main/java`。
   - 兄弟模块的类进入类型解析和符号索引（查找时可见），但**不进入报告范围**：兄弟模块自身的方法变更不会成为"theirs 新增/修改的方法"去做依赖分析，不会进入双方修改列表、附录变更集、注入点/配置键/MyBatis 检查；只有 `--service-dir` 中的代码会作为调用方/需要方被报告。另一方面，兄弟模块的 API 变化会影响服务：theirs 删除/改名兄弟模块中的方法、类型、字段，ours 服务代码仍在使用 → 第 4 项报告；theirs 在兄弟模块接口中新增抽象方法、ours 服务中的实现类未实现 → 第 3 项报告。
   - 兄弟模块中的 `@Component`/`@Bean` 等也计入 ours 已有的 bean。若服务和兄弟模块存在同 FQN 的类，以服务中的为准（给出 warning）。
   - Maven classpath：兄弟模块一般没有安装到本地仓库，`dependency:build-classpath` 会因此整体失败，所以在**临时副本**的服务 pom 中删除对兄弟模块的 `<dependency>`（它们的源码已经交给 SymbolSolver）。
   - 默认开启 `deps` 的理由：只加载服务**声明依赖**的模块，代价是多读几个 pom 和这些模块的源码（样例中 4–5 个文件，耗时可忽略）；不会把无关服务的代码混进来（避免同名类冲突、减少名称匹配噪声）；兄弟模块的代码永远不会单独产生报告，所以开启后已有的结果只会多出真实的跨模块缺口（样例中 `--no-siblings` 与默认相比只少了 G10 的两条）。`all` 适合非 Maven 声明的依赖关系，但在大型 monorepo 中更慢、噪声更多，因此不作为默认。
6. **双方都修改的方法**：单独列出供人工审查，附 base→ours、base→theirs 的方法级 diff，并用 `git merge-file`（在临时文件上）预测该文件**是否会产生文本冲突**——"NO" 意味着 git 会静默合并，只能靠人工审查发现逻辑矛盾。双方做了相同修改的方法不报。
7. **值变更**（三方比较，仅服务目录；报告第 4b 节）：没有方法级变更集的改动。
   - 字段 / 常量初始化表达式（`static final` 常量、数组/集合初始化、实例字段初始化）以及枚举常量参数，去掉注释和格式后比较。类别 `CONSTANT_VALUE`，kind 为 `CONSTANT` / `FIELD` / `ENUM_CONSTANT`。
   - MyBatis mapper XML 元素内容，按 `namespace` + 标签 + `id` 匹配 `<select|insert|update|delete|sql|resultMap>`，去掉 XML 注释、属性排序、压缩空白后比较。类别 `MYBATIS`，kind 为 `MAPPER_<标签>`，给出语句 id 和 `file:line`。
   - 模型与方法一致：

     | base / ours / theirs | 状态 | 严重度 | 说明 |
     |---|---|---|---|
     | base = ours ≠ theirs | `THEIRS_CHANGED_VALUE` / `THEIRS_CHANGED_STATEMENT` | LOW | 整分支合并会自动带上 theirs 的值；向单独维护的分支回移/cherry-pick 时必须一并移植（与同类 `THEIRS_NEW` 依赖的约定相同）。只有 theirs 修改的方法体在附录变更集中可见，值没有附录，所以在这里报告 |
     | base ≠ ours、base ≠ theirs、ours ≠ theirs（或 base 中不存在、双方各自添加了不同的值） | `BOTH_CHANGED_*` / `BOTH_ADDED_*` | HIGH | 冲突修改，同"双方都修改的方法"；附 `git merge-file` 文本冲突预测 |
     | 未改、仅 ours 修改、双方相同修改、仅注释/格式变化、ours 已删除 | — | 不报 | |

报告章节：概览统计 → 缺失依赖 → Spring 配置/Bean 缺口 → 签名变更影响（含已删除/改名字段）→ 4b 值变更（常量、MyBatis 语句内容）→ 双方修改 → 不确定项 → 附录（完整方法变更集，仅服务目录）。JSON 与 Markdown 内容一致。

### 验证样例

`scripts/create-sample.sh [目标目录]` 生成样例仓库（默认 `/workspace/branch-diff-sample`），含 merge-base、`ours`、`theirs` 三个提交，服务目录 `order-service`，并埋入以下问题：

| 编号 | 埋入的问题 | 期望 |
|---|---|---|
| G1 | theirs 新增 `PricingService.calculateFinalPrice`，调用仅 theirs 有的 `MoneyUtils.roundHalfUp`、新常量 `MAX_DISCOUNT_PERCENT`，以及被 ours 删除的 `DEFAULT_CURRENCY` | 报告（后者为 OURS_REMOVED / HIGH） |
| G2 | 新 `@Autowired CouponService`（仅 theirs 有）、`@Qualifier("smsNotifier")` | 报告 |
| G3 | `@Value("${order.coupon.expire-days}")`、`@ConfigurationProperties("order.payment")`，键只在 theirs 的 yml | 报告 |
| G4 | 接口新增 `refundOrder`，ours 独有的 `CachedOrderService` 未实现 | 报告（HIGH） |
| G5 | Mapper 新方法 `selectByCustomerId` + XML id | 报告 |
| G6 | `generate` 重命名为 `nextOrderNo`、`applyDiscount` 加参数，ours 新代码仍用旧形式 | 报告（HIGH） |
| G7 | `cancelOrder`（有文本冲突）、`calculatePrice`（git 可干净合并但双方都加了税 → 重复计税） | 列入双方修改 |
| G8 | theirs 新增 lombok 依赖 | 报告 |
| G9a | theirs 把常量 `OrderConstants.EXPORT_SEPARATOR` 改名为 `CSV_SEPARATOR`；ours 新代码 `OrderExportService#exportLine` 和 ours 独有类 `ExportLimits.SEP` 的字段初始化仍用旧名 | 报告（CONSTANT_RENAMED / HIGH，两处） |
| G9b | theirs 删除枚举常量 `OrderStatus.CLOSED`，ours 独有的 `CachedOrderService#isClosed` 仍在用 | 报告（ENUM_CONSTANT_REMOVED / HIGH） |
| G9c | theirs 把实例字段 `ShippingService.freeThreshold` 改名为 `freeShippingThreshold` 并更新 `fee()`；ours 新增的 `isFreeShipping()` 仍用旧名（git 能干净合并该文件，合并后编译失败） | 报告（FIELD_RENAMED：`isFreeShipping` HIGH，`fee` LOW） |
| G10a | 兄弟模块 `common`（服务 pom 依赖 `order-common`）：theirs 在 `TextUtils` 新增 `maskPhone`，theirs 服务代码 `refundOrder` 调用它，ours 的 `common` 没有 | 报告（MISSING METHOD / THEIRS_NEW，theirs 位置指向 `common/...`） |
| G10b | theirs 删除兄弟模块常量 `CommonConstants.LEGACY_TENANT`，ours 新代码 `OrderExportService` 仍在用 | 报告（CONSTANT_REMOVED / HIGH） |
| N1–N12 | 仅注释改动、调用已存在的方法、带默认值的 `@Value`、只在 profile 文件中的键、注入已存在的 bean、ours 自己的改动、库调用、双方相同修改、其他服务目录、带 `@Select` 的 Mapper 方法、default 接口方法、有 Java 默认值的配置字段 | **不**报告 |
| N13 | theirs 删除 ours 中无人使用的常量 `LEGACY_CHANNEL` | **不**报告 |
| N14 | theirs 删除 `OrderConstants.MAX_ITEMS`，ours 只通过 `import static` 使用自己的 `ExportLimits.MAX_ITEMS`（同名不同声明） | **不**报告 |
| N15 | theirs 服务代码调用兄弟模块中两边完全相同的 `IdUtils.isValid`、已存在的 `TextUtils.trimToEmpty` | **不**报告 |
| N16 | 兄弟模块内部的变化：theirs 新增仅被 `common` 自己使用的 `maskEmail`/`MaskingSupport`；`DateUtils.today()` 被双方不同地修改 | **不**报告（不在 `--service-dir` 范围） |

```bash
scripts/create-sample.sh
java -jar target/branch-diff-tool.jar --repo /workspace/branch-diff-sample \
  --ours ours --theirs theirs --service-dir order-service -o docs/sample-report.md
```

`SampleRepoEndToEndTest` 在 `mvn package` 时自动执行上述流程（离线、`--no-maven` 模式），断言每个 G 项都被报告、每个 N 项都没有被报告；并分别以 `--include-siblings none` / `all` 再跑一次：`none` 时 G10 两条消失、其余不变，`all` 时额外索引 `other-service` 但结果与默认相同（N10 仍不报告）。生成的报告见 `docs/sample-report.md` / `docs/sample-report.json`。

### 已知局限（分支模式）

- **反射**：`Class.forName`、`Method.invoke`、字符串形式的类名/方法名无法追踪。
- **动态代理 / AOP / 运行时生成代码**：JDK/CGLIB 代理、Feign、Spring Data 派生查询方法（`findByXxx`）、MyBatis-Plus `BaseMapper` 的继承方法、Lombok 生成的 getter/setter 等不在源码中，只能按"外部"处理；对它们的缺失无法确认。
- **字符串形式的 bean 名称**：`getBean("xxx")`、XML 配置的 bean、`@Import` / 自动配置 / `spring.factories` 提供的 bean，以及 `@Conditional*` 条件是否满足，都不在分析范围；`@Qualifier` 找不到时标为 UNCERTAIN。
- **双方都修改的方法中的逻辑矛盾**：工具只能列出并给出 diff 和是否有文本冲突，**无法判断语义是否矛盾**（例如样例中两边都加税），必须人工审查。
- 配置：只读服务目录内 `application*/bootstrap*` 文件；环境变量、配置中心（Nacos/Apollo）、命令行参数、`@PropertySource` 指定的其他文件不在范围（此类键标为 UNCERTAIN）。
- 多模块：
  - `deps` 模式只认 Maven pom 中声明的依赖；Gradle、`build-helper` 额外源码目录、非 `src/main/java` 布局不识别（可用 `all`，但 `all` 也只认 `<dir>/src/main/java`）。版本号/`<classifier>` 不参与匹配。
  - 兄弟模块的 `application*.yml`、MyBatis XML 不读取（配置键只对照服务目录）；兄弟模块中的 bean 一律算作"存在"，不判断是否被组件扫描/自动配置覆盖。
  - 临时 pom 去掉兄弟模块依赖后，兄弟模块**自己**声明的第三方依赖不会进入 classpath，兄弟模块代码中引用的库类型可能解析失败（只影响对兄弟模块内部的解析，必要时退化为名称匹配）。
  - 兄弟模块中与服务无关的变化（兄弟模块内部缺口、兄弟模块中双方都修改的方法）按设计不报告；兄弟模块之外仍未被选中的模块依旧视为外部库。Maven 多模块的 classpath 解析若失败会降级为名称匹配。
- 方法级匹配基于简单类型名（擦除泛型），同名不同包的参数类型可能误判为相同；类整体改包/改名会表现为"删除 + 新增"。
- 字段反向追踪：只处理"删除/改名"，不报告字段**类型变化**或 static/final 变化；改名识别依赖相同的非空初始化表达式，无初始化表达式的字段和无参枚举常量改名会报告为 `*_REMOVED`（不给出新名字）；字段移动到其他（非父类）类视为删除。方法内**局部类**（local class）中的引用、字符串形式的引用（反射、SpEL、MyBatis XML 中的 `#{field}`）不扫描。
- 值变更：只比较初始化表达式 / XML 文本本身。初始化表达式未变、但引用了另一个已变常量的常量不单独报告（被引用的那个会报）；在 static 块或构造器中赋值、运行时计算的值，以及兄弟模块或服务目录之外的 XML 不在范围内。兄弟模块中仅 theirs 修改的值不报告（需把该模块作为 `--service-dir` 运行）。`<include>` 引用的片段按其自身的 `<sql>` 元素比较，不做内联展开。
- HTML 报告：diff 基于整个文件的文本行（不是 AST），方法移动位置会显示为删除 + 新增；行内高亮是词级的，并把块内第 k 个改动行与另一侧第 k 行配对；三栏分组是 `diff3` 式的近似，个别边界情况可能与 `git merge-file` 不同（权威的文本冲突预测仍以卡片详情为准）。只嵌入问题涉及的文件；超大报告不分页。
- 依赖链：沿用分析器的静态解析，名称匹配的调用标为不确定，反射、AOP、XML/字符串装配不跟踪；依赖树最多 4 层（反向影响最多 3 层调用方），每个节点最多 40 个子节点；反向影响只覆盖范围内的 ours 代码。第三方库类型只在两侧 Maven classpath 都解析成功时检测（`--no-maven` 下没有），且只有本地仓库布局下的 jar 才能映射到构件；ours 也能解析到的库类型（例如来自别的构件）不会报告。缺少 classpath 时，无法解析的继承调用（例如继承 commons-lang3 `StringUtils` 的工具类）可能产生误报，并被合并成一条大链。问题之间通过共同的调用方，或 ours 缺失/不兼容的依赖关联；仅内容不同的共享依赖不会把它们连成一条链。成员视图最多 1500 个成员，每个最多 400 行。
- 未实现"对合并结果执行 `mvn compile`"的可选检查（需求中注明非必须）。
