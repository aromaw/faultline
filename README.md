English | [简体中文](README.zh-CN.md)

# faultline

Find what a clean git merge silently leaves behind in Spring/Maven services.

*(Semantic diff for branch migration — MVP. The build artifact is still named `branch-diff-tool`.)*

When you migrate or merge code from a **theirs** branch into an **ours** branch (a Java Spring Boot microservice, Maven, a single service directory), `git diff` / `git merge` only surface **textual conflicts**.
This tool compares the merge-base, ours and theirs versions **at method level** and finds code that theirs changed but that ours is **missing or inconsistent with** — including semantic gaps and dependency gaps for which git reports no conflict at all.

> Read-only: the three versions are read via `git rev-parse / merge-base / ls-tree / cat-file`. **No checkout, no working-tree changes, no index changes, no refs created, no remote access.**
> Sources are copied to a system temp directory for symbol resolution (and the optional `mvn dependency:build-classpath`) and deleted automatically afterwards.
> With sibling modules enabled, only the service pom in the **temp copy** is modified (dependencies on sibling modules are removed, see below); the repository itself is untouched.

## Build

Requires JDK 17+, Maven 3.8+, git.

```bash
mvn -B package   # compile + unit tests + package
# output: target/branch-diff-tool.jar (executable fat jar)
```

## Usage

```bash
java -jar target/branch-diff-tool.jar \
  --repo /path/to/repo \
  --ours HEAD \
  --theirs origin/feature-x \
  --service-dir order-service \
  -o report.md

# JSON output
java -jar target/branch-diff-tool.jar --repo . --theirs feature-x --json -o report.json

# self-contained HTML report (IDEA-style diff viewer, see below)
java -jar target/branch-diff-tool.jar --repo . --theirs feature-x --html -o report.html
```

`--ours` defaults to `HEAD`; `--theirs` is required; `--service-dir` defaults to the repo root; without `-o` the report goes to stdout.

| Option | Description |
|---|---|
| `--repo <path>` | Path to the git repository (default `.`) |
| `--ours <ref>` / `--theirs <ref>` | The two branches/commits; the merge-base is computed automatically |
| `--service-dir <dir>` | Analyze only this subdirectory (changes in other directories do not enter the report) |
| `--format md\|json\|html`, `--json`, `--html` | Output format, Markdown by default; `html` is a single self-contained page (see [HTML report](#html-report)) |
| `-o, --output <file>` | Output file |
| `--no-maven` | Skip `mvn dependency:build-classpath` (faster, but third-party library types cannot be resolved, so more entries become UNCERTAIN) |
| `--mvn <cmd>` / `--maven-timeout <sec>` | Maven command and timeout (defaults `mvn`, 600 seconds) |
| `--include-tests` | Also analyze `src/test/java` |
| `--include-siblings [deps\|all\|none]` | Add the `src/main/java` of sibling modules in the same repo to symbol resolution and the index (loaded for all three versions), so classes in sibling modules are no longer treated as external libraries, and methods/types/constants that theirs depends on but that are missing from the ours version of a sibling module are reported as well. **Default `deps`**: load only modules the service pom depends on (including transitive dependencies via sibling modules); `all`: every other `<dir>/src/main/java` in the repo (including modules listed in the parent pom's `<modules>` and non-Maven directories); `none`: disabled (old behavior). Without a value it means `deps`; `--include-siblings=all` is also supported |
| `--no-siblings` | Same as `--include-siblings none` |
| `--fail-on-findings` | Exit code 2 when there are certain HIGH findings (useful in CI) |
| `--keep-temp`, `-v` | Keep the temp directory / print progress |

## HTML report

`--html` (same as `--format html`) writes **one self-contained `.html` file** in the style of IntelliJ IDEA's diff/merge viewer. All CSS and JavaScript are inline: no CDN, no network access, it works offline and can be attached to a ticket or sent by mail.

```bash
java -jar target/branch-diff-tool.jar --repo . --theirs feature-x --service-dir order-service --html -o report.html
```

![HTML report: three-column view of a both-modified finding](docs/html-report.png)

- **Sidebar**: findings grouped by severity (HIGH / MEDIUM / LOW / UNCERTAIN), then by category, with counts, per-group filter toggles and a search box (symbol, file, message, status).
- **Header**: repo, service dir, ours / merge-base / theirs refs and summary counts.
- **Finding card**: severity, status, kind, symbol, origin, message, details, and every location (`needed by` / `used at` / ours / theirs / base) as a clickable jump that switches file when needed.
- **Diff view**: three columns `ours | merge-base | theirs` (toggle to two columns `ours | theirs`), line numbers, one scroll container so all columns scroll together, added (green), changed (blue), deleted (grey) and conflicting (red, both sides changed the same base lines) blocks, word-level inline highlights, unchanged regions collapsed with "expand N lines" rows (and *Expand all*). The finding's line is highlighted and scrolled into view.
- **Syntax highlighting** for Java, XML (MyBatis `#{}` / `${}` marked), YAML and properties (small built-in tokenizer).
- **Keyboard**: <kbd>↑</kbd>/<kbd>↓</kbd> or <kbd>j</kbd>/<kbd>k</kbd> move between findings, <kbd>F7</kbd> / <kbd>Shift+F7</kbd> next / previous change.
- **Themes**: IDEA Light (default) and a Darcula-like dark theme.
- **URL hash** holds the state, e.g. `report.html#finding=3&mode=2col&theme=dark` (`target=N` selects the N-th location of the finding; `tab=deps|impact|check|overview`, `node=<key>`, `group=chain`, `ov=all` select the context views), so links and headless screenshots can open a specific view.

Only files referenced by findings are embedded, read from git at the three revisions; a version larger than 1 MB is skipped with a note. Line diffs (Myers, [java-diff-utils](https://github.com/java-diff-utils/java-diff-utils), Apache-2.0) are computed in Java and embedded as JSON; the three-column view groups ours and theirs changes per merge-base range the way `diff3` does. `docs/sample-report.html` is the report of the verification sample below.

### Context and dependency chains

Each finding also carries its code context, computed from the same three indexes (also in the JSON report as `context` and in Markdown as section 7):

- **Dependency chain** tab: a tree from theirs' code that needs the finding (e.g. the controller method) down to the missing or changed symbol and *its* dependencies, transitively (methods, constructors, fields and constants, classes, Spring beans, config keys, MyBatis statements, library types and the Maven artifacts that provide them). Each node shows its kind, `file:line` and its status in ours: `missing`, `changed` (incompatible signature or type), `differs` (present, but theirs changed it), `present` or `unknown`, plus who changed it. Only nodes that ours lacks or that differ are expanded (at most 4 levels, cycles are marked and cut); dependencies that are present and unchanged are folded into "N more".
- **Reverse impact** tab: for removed, renamed, re-typed or changed symbols (SIGNATURE_IMPACT, CONSTANT_VALUE, BOTH_MODIFIED, ...), the code in ours that uses them, up to 3 levels of callers, with "uses it at L.." links.
- **Port checklist** tab: findings that belong to the same chain are grouped (`⛓ C1`, sidebar *By chain*) and get one ordered list, dependencies first, with the action per step ("Add constructor", "Add the dependency to ours' pom.xml", "Update this method in ours", ...). Checkboxes are not saved.
- **Overview** tab: an inline SVG of the files of the chain (or all chains), from the code that needs something to the files that provide it, colored by the worst status.
- Clicking any node shows its whole enclosing member side by side, ours / merge-base / theirs, with the usual diff highlighting; versions that lack the member are dropped (one or two columns). The bar between the panel and the code can be dragged.

![Dependency chain of a real RuoYi-Vue finding](docs/html-report-deps.png)


## What it detects

1. **Method-level change sets** (base→theirs, base→ours): identified by `fully.qualified.Class#method(ParamType...)` as ADDED / REMOVED / MODIFIED / SIGNATURE_CHANGED (same name, different parameters) / RENAMED (same body, different name). Comments and formatting are stripped before comparison, so changes to comments, formatting or position alone do not count as modifications.
2. **Dependency closure**: for every method theirs added or modified, collect the methods, constructors, referenced types, fields and constants it uses (including enum constants, method references and annotations), and check each one exists in ours with a compatible signature:
   - `MISSING` (does not exist) / `INCONSISTENT` (exists but parameters, return type, static, etc. differ);
   - `origin`: `THEIRS_NEW` (newly introduced by theirs, must be migrated along with it), `OURS_REMOVED` / `OURS_CHANGED` (present in the merge-base, removed or changed by ours — **git reports no conflict at all**; the most dangerous case);
   - when an entire class is missing in ours, the findings are merged into a single TYPE entry.
   - Uses the JavaParser SymbolSolver (project sources + JDK + jars from `mvn dependency:build-classpath`); when resolution fails it falls back to matching by name/argument count and marks the entry **UNCERTAIN** instead of aborting.
3. **Spring-specific checks**
   - Injection points: `@Autowired` / `@Inject` / `@Resource` fields, constructor injection (including Lombok `@RequiredArgsConstructor` / `@AllArgsConstructor`), `@Autowired` setters, `@Bean` method parameters, `@Qualifier` / `@Resource(name=)` names. Checks whether ours has a matching bean (`@Component/@Service/...`, `@Bean`, MyBatis Mapper, Spring Data Repository, `@FeignClient`).
   - Configuration keys: `@Value("${...}")` (not reported when it has a default), `Environment.getProperty("...")`, fields of `@ConfigurationProperties(prefix)`, checked against **all** of ours' `application*.yml|yaml|properties` and `bootstrap*` (all profiles, including multi-document yml; relaxed binding supported, `max-items` ≈ `maxItems`).
   - Interface/abstract methods: abstract methods theirs added to an interface (non-default) or abstract class, and which implementations in ours do not implement them; distinguishes "implementation only in ours" (HIGH, the merge will certainly fail to compile) from "already implemented in theirs, brought in by the merge" (LOW).
   - MyBatis: correspondence between Mapper interface methods (without annotations such as `@Select`) and XML `namespace` + `<select|insert|update|delete id>`; XML ids added by theirs that lack an interface method or statement in ours.
   - Maven: dependencies added in theirs' pom that ours does not have.
4. **Reverse impact of signature changes**: for methods theirs removed, renamed, or whose parameters or return type it changed (and removed types), find the places in ours that still call the old form (file:line). Caller is code added/modified by ours → HIGH (the merge won't fix it for you); caller unchanged and theirs already updated it → LOW; caller unchanged in ours but removed/renamed in theirs → LOW (a whole-branch merge removes it too; re-check when cherry-picking); otherwise → MEDIUM.
   - **Field/constant reverse tracking** (`STILL_USES_REMOVED_FIELD`): for fields theirs removed or renamed (instance fields, `static final` constants, interface constants, enum constants; the declaring class still exists in theirs and ours still has the field), scan ours for places that still reference it: method/constructor bodies, field initializers, enum constant arguments, initializer blocks; supports `Type.NAME`, `this.x`, unqualified names and `import static`. SymbolSolver is used first to confirm the reference really points to that declaration (same-named local variables or same-named constants in other classes are not false positives); if resolution fails it falls back to matching by import/scope and marks the entry UNCERTAIN.
   - Rename detection: if theirs added, in the same class, exactly one field with the same type, the same static/enum nature and the **same (non-empty) initializer** → `*_RENAMED` (the report gives the new name); otherwise `*_REMOVED`.
   - Severity rules are the same as for methods: the referencing method/declaration was added or modified by ours (or the whole class exists only in ours) → HIGH; unchanged in ours and the theirs version no longer references it, or removed in theirs → LOW; otherwise → MEDIUM. Multiple references within the same member are merged into one entry (`moreReferences`).
   - The opposite direction (ours removed a field/constant that code added/modified by theirs still needs) is reported by the dependency closure in item 2 as `MISSING … OURS_REMOVED` (sample G1c), so both directions are covered.
5. **Sibling modules** (`--include-siblings`, default `deps`):
   - Discovery: read all `pom.xml` files of each version (base/ours/theirs), start from the service pom's `<dependency>` entries (ignoring `test` scope), match in-repo modules by `groupId:artifactId` (when groupId is written as a property such as `${project.groupId}`, match by unique artifactId), and follow the sibling modules' own dependencies transitively; take the union over the three versions, then load these modules' `src/main/java` from each version.
   - Sibling-module classes take part in type resolution and the symbol index (visible to lookups) but are **not in reporting scope**: method changes inside sibling modules are not treated as "methods added/modified by theirs" for dependency analysis, and do not enter the both-modified list, the appendix change set, or the injection point/config key/MyBatis checks; only code in `--service-dir` is reported as a caller/requirer. On the other hand, API changes in sibling modules do affect the service: theirs removes/renames a method, type or field in a sibling module that ours' service code still uses → reported by item 4; theirs adds an abstract method to a sibling-module interface that an implementation in ours' service does not implement → reported by item 3.
   - `@Component`/`@Bean` etc. in sibling modules also count as beans ours already has. If the service and a sibling module have a class with the same FQN, the service's class wins (with a warning).
   - Maven classpath: sibling modules are usually not installed in the local repository, which makes `dependency:build-classpath` fail as a whole, so the `<dependency>` entries on sibling modules are removed from the service pom in the **temp copy** (their sources are already handed to SymbolSolver).
   - Why `deps` is on by default: it loads only modules the service **declares as dependencies**, at the cost of reading a few more poms and those modules' sources (4–5 files in the sample, negligible time); it doesn't mix in code from unrelated services (avoiding same-name class clashes and reducing name-matching noise); sibling-module code never produces reports on its own, so enabling it can only add real cross-module gaps to existing results (in the sample, `--no-siblings` drops only the two G10 entries compared to the default). `all` suits dependency relationships not declared in Maven, but is slower and noisier in large monorepos, so it is not the default.
6. **Methods modified on both sides**: listed separately for manual review, with method-level base→ours and base→theirs diffs, and `git merge-file` (on temp files) is used to predict **whether the file will have a textual conflict** — "NO" means git will merge silently and logical contradictions can only be found by manual review. Methods where both sides made the identical change are not reported.
7. **Value changes** (three-way, service directory only; report section 4b): changes that have no method-level change set.
   - Field / constant initializers (`static final` constants, array/collection initializers, instance field initializers) and enum constant arguments, compared after stripping comments and formatting. Category `CONSTANT_VALUE`, kind `CONSTANT` / `FIELD` / `ENUM_CONSTANT`.
   - MyBatis mapper XML element bodies, matched by `namespace` + tag + `id` for `<select|insert|update|delete|sql|resultMap>`, compared after dropping XML comments, sorting attributes and collapsing whitespace. Category `MYBATIS`, kind `MAPPER_<TAG>`, with the statement id and `file:line`.
   - Model, the same as for methods:

     | base / ours / theirs | Status | Severity | Why |
     |---|---|---|---|
     | base = ours ≠ theirs | `THEIRS_CHANGED_VALUE` / `THEIRS_CHANGED_STATEMENT` | LOW | A whole-branch merge carries theirs' value; a backport/cherry-pick to a separately maintained line must port it (same convention as a same-class `THEIRS_NEW` dependency). Theirs-only method-body edits are visible in the appendix change set; values have no appendix, so they are reported here |
     | base ≠ ours, base ≠ theirs, ours ≠ theirs (or absent in base and added differently on both sides) | `BOTH_CHANGED_*` / `BOTH_ADDED_*` | HIGH | Conflicting edits, like methods modified on both sides; includes the `git merge-file` textual-conflict prediction |
     | unchanged, ours-only change, identical change on both sides, comment/format-only change, ours removed it | — | not reported | |

Report sections: summary statistics → missing dependencies → Spring config/bean gaps → signature change impact (including removed/renamed fields) → 4b value changes (constants, MyBatis statement bodies) → modified on both sides → uncertain items → appendix (full method change set, service directory only). JSON and Markdown have the same content.

## Verification sample

`scripts/create-sample.sh [target-dir]` generates a sample repository (default `/workspace/branch-diff-sample`) with three commits — merge-base, `ours`, `theirs` — and service directory `order-service`, with the following issues planted:

| ID | Planted issue | Expected |
|---|---|---|
| G1 | theirs adds `PricingService.calculateFinalPrice`, which calls `MoneyUtils.roundHalfUp` (only in theirs), the new constant `MAX_DISCOUNT_PERCENT`, and `DEFAULT_CURRENCY`, which ours removed | Reported (the latter as OURS_REMOVED / HIGH) |
| G2 | New `@Autowired CouponService` (only in theirs), `@Qualifier("smsNotifier")` | Reported |
| G3 | `@Value("${order.coupon.expire-days}")`, `@ConfigurationProperties("order.payment")`, keys only in theirs' yml | Reported |
| G4 | Interface gains `refundOrder`; `CachedOrderService`, which exists only in ours, does not implement it | Reported (HIGH) |
| G5 | New Mapper method `selectByCustomerId` + XML id | Reported |
| G6 | `generate` renamed to `nextOrderNo`, `applyDiscount` gains a parameter; new code in ours still uses the old forms | Reported (HIGH) |
| G7 | `cancelOrder` (textual conflict), `calculatePrice` (git merges cleanly, but both sides add tax → tax applied twice) | Listed under modified on both sides |
| G8 | theirs adds a lombok dependency | Reported |
| G9a | theirs renames constant `OrderConstants.EXPORT_SEPARATOR` to `CSV_SEPARATOR`; new ours code `OrderExportService#exportLine` and the field initializer of ours-only class `ExportLimits.SEP` still use the old name | Reported (CONSTANT_RENAMED / HIGH, two places) |
| G9b | theirs removes enum constant `OrderStatus.CLOSED`; ours-only `CachedOrderService#isClosed` still uses it | Reported (ENUM_CONSTANT_REMOVED / HIGH) |
| G9c | theirs renames instance field `ShippingService.freeThreshold` to `freeShippingThreshold` and updates `fee()`; `isFreeShipping()`, added by ours, still uses the old name (git merges this file cleanly; the result fails to compile) | Reported (FIELD_RENAMED: `isFreeShipping` HIGH, `fee` LOW) |
| G10a | Sibling module `common` (service pom depends on `order-common`): theirs adds `maskPhone` to `TextUtils`, theirs' service code `refundOrder` calls it, ours' `common` doesn't have it | Reported (MISSING METHOD / THEIRS_NEW, theirs location points into `common/...`) |
| G10b | theirs removes sibling-module constant `CommonConstants.LEGACY_TENANT`; new ours code `OrderExportService` still uses it | Reported (CONSTANT_REMOVED / HIGH) |
| N1–N12 | Comment-only changes, calls to existing methods, `@Value` with a default, keys only in profile files, injecting existing beans, ours' own changes, library calls, identical changes on both sides, other service directories, Mapper methods with `@Select`, default interface methods, config fields with Java default values | **Not** reported |
| N13 | theirs removes constant `LEGACY_CHANNEL`, unused in ours | **Not** reported |
| N14 | theirs removes `OrderConstants.MAX_ITEMS`; ours only uses its own `ExportLimits.MAX_ITEMS` via `import static` (same name, different declaration) | **Not** reported |
| N15 | theirs' service code calls `IdUtils.isValid`, identical on both sides in the sibling module, and the existing `TextUtils.trimToEmpty` | **Not** reported |
| N16 | Changes internal to the sibling module: theirs adds `maskEmail`/`MaskingSupport`, used only within `common`; `DateUtils.today()` modified differently by both sides | **Not** reported (outside `--service-dir` scope) |

```bash
scripts/create-sample.sh
java -jar target/branch-diff-tool.jar --repo /workspace/branch-diff-sample \
  --ours ours --theirs theirs --service-dir order-service -o docs/sample-report.md
```

`SampleRepoEndToEndTest` runs this flow automatically during `mvn package` (offline, `--no-maven` mode), asserting that every G item is reported and no N item is; it then runs again with `--include-siblings none` and `all`: with `none` the two G10 entries disappear and everything else is unchanged; with `all`, `other-service` is additionally indexed but the results match the default (N10 is still not reported). The generated reports are in `docs/sample-report.md` / `docs/sample-report.json`.

## Known limitations

- **Reflection**: `Class.forName`, `Method.invoke`, and class/method names given as strings cannot be traced.
- **Dynamic proxies / AOP / runtime-generated code**: JDK/CGLIB proxies, Feign, Spring Data derived query methods (`findByXxx`), methods inherited from MyBatis-Plus `BaseMapper`, Lombok-generated getters/setters, etc. are not in the source and can only be treated as "external"; their absence cannot be confirmed.
- **Bean names as strings**: `getBean("xxx")`, beans configured in XML, beans provided via `@Import` / auto-configuration / `spring.factories`, and whether `@Conditional*` conditions hold are all out of scope; an unresolvable `@Qualifier` is marked UNCERTAIN.
- **Logical contradictions in methods modified on both sides**: the tool can only list them with diffs and whether there is a textual conflict; it **cannot tell whether the semantics contradict** (e.g. both sides adding tax in the sample) — manual review is required.
- Configuration: only `application*/bootstrap*` files inside the service directory are read; environment variables, config centers (Nacos/Apollo), command-line arguments, and other files specified via `@PropertySource` are out of scope (such keys are marked UNCERTAIN).
- Multi-module:
  - `deps` mode only recognizes dependencies declared in Maven poms; Gradle, extra source directories via `build-helper`, and non-`src/main/java` layouts are not recognized (`all` can be used, but `all` also only recognizes `<dir>/src/main/java`). Versions/`<classifier>` are not used for matching.
  - Sibling modules' `application*.yml` and MyBatis XML are not read (config keys are checked against the service directory only); beans in sibling modules always count as "present", without checking whether component scanning/auto-configuration covers them.
  - After sibling-module dependencies are removed from the temp pom, third-party dependencies declared **by the sibling modules themselves** are not on the classpath, so library types referenced in sibling-module code may fail to resolve (this only affects resolution inside sibling modules; falls back to name matching when needed).
  - Changes in sibling modules unrelated to the service (gaps internal to a sibling module, methods modified on both sides within a sibling module) are by design not reported; modules outside the sibling set that were not selected are still treated as external libraries. If Maven multi-module classpath resolution fails, it degrades to name matching.
- Method-level matching is based on simple type names (generics erased), so parameter types with the same name in different packages may be wrongly considered identical; moving/renaming a whole class shows up as "removed + added".
- Field reverse tracking: only handles "removed/renamed"; does not report field **type changes** or static/final changes; rename detection relies on an identical non-empty initializer, so renames of fields without initializers and of enum constants without arguments are reported as `*_REMOVED` (no new name given); a field moved to another (non-superclass) class counts as removed. References inside method-**local classes** and string-form references (reflection, SpEL, `#{field}` in MyBatis XML) are not scanned.
- Value changes: only literal initializer / XML text is compared. A constant whose initializer is unchanged but refers to another changed constant is not reported separately (the referenced one is); values assigned in static blocks or constructors, values computed at runtime, and XML in sibling modules or outside the service directory are not covered. Theirs-only value changes in sibling modules are not reported (run with that module as `--service-dir`). `<include>`d fragments are compared as their own `<sql>` element, not inlined.
- HTML report: the diff is line-based on the full file versions (not the AST), so a moved method shows up as delete + add; inline highlights are word-level and pair the k-th changed line with the k-th line of the other side; the three-column grouping is a `diff3`-style approximation and may differ from `git merge-file` in edge cases (the authoritative textual-conflict prediction stays in the card details). Only files referenced by findings are embedded; very large reports are not paginated.
- Dependency chains: they follow the same static resolution as the analyzers, so name-matched calls are marked uncertain and reflection, AOP and XML/string wiring are not followed; trees stop at 4 levels (reverse impact at 3 levels of callers) and at 40 children per node; reverse impact only covers in-scope ours code. Library types are detected only when both Maven classpaths resolve (not with `--no-maven`), and mapped to an artifact only for jars in the local-repository layout. A library type that ours also resolves (e.g. through another artifact) is not reported. When the symbol solver lacks a classpath, unresolved inherited calls (e.g. commons-lang3 `StringUtils` subclasses) can produce false findings that then merge into one large chain. Findings are linked through shared callers or through dependencies that ours lacks or has incompatibly; a shared dependency that only differs in content does not link them. Member views are capped at 1500 members and 400 lines each.
- The optional check "run `mvn compile` on the merge result" is not implemented (the requirements marked it as optional).
