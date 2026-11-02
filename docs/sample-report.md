# Branch merge gap report

| | |
|---|---|
| repo | `/workspace/branch-diff-sample` |
| service dir | `order-service` |
| ours | `ours (43c6a1825b63967f252d4a7a3a3681310cca85de)` |
| theirs | `theirs (ab4163f5d43e82d5a66bedc65a83ded18af3ba1c)` |
| mergeBase | `8b9a36de7eb117333574ea3c9a54c20e0f221538` |
| generated | 2026-11-02T15:26:21.132594968+08:00 |

Legend: **origin** `THEIRS_NEW` = theirs introduced the symbol (port it together with the code that needs it; a full merge brings it only if its file merges cleanly) · `OURS_REMOVED`/`OURS_CHANGED` = the merge-base had it and ours removed/changed it (semantic conflict git will NOT report) · `THEIRS_CHANGED` = theirs changed an API ours still uses.

## 1. Summary

| Category | Certain | Uncertain |
|---|---:|---:|
| MISSING_DEPENDENCY | 13 | 0 |
| SPRING_BEAN | 2 | 0 |
| SPRING_CONFIG | 2 | 0 |
| INTERFACE_IMPL | 2 | 0 |
| MYBATIS | 1 | 0 |
| MAVEN_DEPENDENCY | 1 | 0 |
| SIGNATURE_IMPACT | 9 | 0 |
| CONSTANT_VALUE | 0 | 0 |
| BOTH_MODIFIED | 2 | 0 |
| **total** | 32 | 0 |

- Method changes base→theirs: {ADDED=17, REMOVED=0, MODIFIED=5, SIGNATURE_CHANGED=1, RENAMED=1}
- Method changes base→ours: {ADDED=10, REMOVED=0, MODIFIED=3, SIGNATURE_CHANGED=0, RENAMED=0}
- HIGH severity findings: **15**
- Methods changed identically on both sides (not flagged): 1
- Files git would report as textual conflicts (`git merge-file`): `order-service/src/main/java/com/example/order/service/impl/OrderServiceImpl.java (1 hunk(s))`, `order-service/src/main/resources/application.yml (1 hunk(s))`
- Analysis: oursClasspath=ok (43 jars); theirsClasspath=ok (44 jars); theirsMethodsAnalyzed=24; callsResolvedBySymbolSolver=28; callsNameMatchedFallback=0; callsToExternalLibraries=19; siblingMode=deps
- Sibling modules indexed for lookups (their own code is never reported): `common` (java files base/ours/theirs: 4/4/5)

## 2. Missing / inconsistent dependencies

Symbols that methods added/modified by theirs need, checked against ours (certain only; see §6 for name-matched guesses).

| # | Sev | Category | Status | Kind | Symbol | Origin | Needed by / used at | Theirs | Ours | Notes |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | HIGH | MISSING_DEPENDENCY | MISSING | CONSTANT | `com.example.order.constants.OrderConstants.DEFAULT_CURRENCY` | OURS_REMOVED | `com.example.order.service.PricingService#calculateFinalPrice(Order,String,int) [ADDED in theirs]` @ `order-service/src/main/java/com/example/order/service/PricingService.java:35` | `order-service/src/main/java/com/example/order/constants/OrderConstants.java:7` |  | Missing in ours (existed in the merge-base: ours removed it, git will not report this) |
| 2 | MEDIUM | MISSING_DEPENDENCY | MISSING | METHOD | `com.example.common.text.TextUtils#maskPhone(String)` | THEIRS_NEW | `com.example.order.service.impl.OrderServiceImpl#refundOrder(Long,BigDecimal) [ADDED in theirs]` @ `order-service/src/main/java/com/example/order/service/impl/OrderServiceImpl.java:70` | `common/src/main/java/com/example/common/text/TextUtils.java:11` |  | Missing in ours (introduced by theirs; must be ported together) |
| 3 | MEDIUM | MISSING_DEPENDENCY | MISSING | CONSTANT | `com.example.order.constants.OrderConstants.MAX_DISCOUNT_PERCENT` | THEIRS_NEW | `com.example.order.service.PricingService#applyDiscount(BigDecimal,int,boolean) [SIGNATURE_CHANGED in theirs]` @ `order-service/src/main/java/com/example/order/service/PricingService.java:24` | `order-service/src/main/java/com/example/order/constants/OrderConstants.java:13` |  | Missing in ours (introduced by theirs; must be ported together) |
| 4 | MEDIUM | MISSING_DEPENDENCY | MISSING | TYPE | `com.example.order.coupon.CouponService` | THEIRS_NEW | `com.example.order.service.impl.OrderServiceImpl#refundOrder(Long,BigDecimal) [ADDED in theirs]` @ `order-service/src/main/java/com/example/order/service/impl/OrderServiceImpl.java:69` | `order-service/src/main/java/com/example/order/coupon/CouponService.java:10` |  | Type does not exist in ours (added by theirs)<br>membersUsed: [releaseCoupons(Long)] |
| 5 | MEDIUM | MISSING_DEPENDENCY | MISSING | METHOD | `com.example.order.mapper.OrderMapper#selectByCustomerId(Long)` | THEIRS_NEW | `com.example.order.service.impl.OrderServiceImpl#listCustomerOrders(Long) [ADDED in theirs]` @ `order-service/src/main/java/com/example/order/service/impl/OrderServiceImpl.java:77` | `order-service/src/main/java/com/example/order/mapper/OrderMapper.java:18` |  | Missing in ours (introduced by theirs; must be ported together) |
| 6 | MEDIUM | MISSING_DEPENDENCY | MISSING | CONSTANT | `com.example.order.model.OrderStatus.REFUNDED` | THEIRS_NEW | `com.example.order.service.impl.OrderServiceImpl#refundOrder(Long,BigDecimal) [ADDED in theirs]` @ `order-service/src/main/java/com/example/order/service/impl/OrderServiceImpl.java:68` | `order-service/src/main/java/com/example/order/model/OrderStatus.java:7` |  | Missing in ours (introduced by theirs; must be ported together) |
| 7 | MEDIUM | MISSING_DEPENDENCY | INCONSISTENT | METHOD | `com.example.order.service.PricingService#applyDiscount(BigDecimal,int,boolean)` | THEIRS_NEW | `com.example.order.service.PricingService#calculateFinalPrice(Order,String,int) [ADDED in theirs]` @ `order-service/src/main/java/com/example/order/service/PricingService.java:33`<br>`com.example.order.coupon.CouponService#preview(BigDecimal,int) [ADDED in theirs]` @ `order-service/src/main/java/com/example/order/coupon/CouponService.java:26` | `order-service/src/main/java/com/example/order/service/PricingService.java:23` | `order-service/src/main/java/com/example/order/service/PricingService.java:20` | Exists in ours with an incompatible signature: only applyDiscount(BigDecimal,int) exists |
| 8 | MEDIUM | MISSING_DEPENDENCY | MISSING | METHOD | `com.example.order.util.MoneyUtils#roundHalfUp(BigDecimal)` | THEIRS_NEW | `com.example.order.service.PricingService#calculateFinalPrice(Order,String,int) [ADDED in theirs]` @ `order-service/src/main/java/com/example/order/service/PricingService.java:38` | `order-service/src/main/java/com/example/order/util/MoneyUtils.java:14` |  | Missing in ours (introduced by theirs; must be ported together) |
| 9 | MEDIUM | MISSING_DEPENDENCY | MISSING | METHOD | `com.example.order.util.OrderNumberGenerator#nextOrderNo(Long)` | THEIRS_NEW | `com.example.order.service.impl.OrderServiceImpl#createOrder(Order) [MODIFIED in theirs]` @ `order-service/src/main/java/com/example/order/service/impl/OrderServiceImpl.java:42` | `order-service/src/main/java/com/example/order/util/OrderNumberGenerator.java:9` |  | Missing in ours (introduced by theirs; must be ported together) |
| 10 | LOW | MISSING_DEPENDENCY | MISSING | FIELD | `com.example.order.service.ShippingService.freeShippingThreshold` | THEIRS_NEW | `com.example.order.service.ShippingService#fee(Order) [MODIFIED in theirs]` @ `order-service/src/main/java/com/example/order/service/ShippingService.java:11` | `order-service/src/main/java/com/example/order/service/ShippingService.java:8` |  | Missing in ours (introduced by theirs in the same class; arrives with that class if it merges cleanly, must be ported together when cherry-picking) |
| 11 | LOW | MISSING_DEPENDENCY | MISSING | METHOD | `com.example.order.service.impl.OrderServiceImpl#refundOrder(Long,BigDecimal)` | THEIRS_NEW | `com.example.order.service.impl.OrderServiceImpl#cancelOrder(Long) [MODIFIED in theirs]` @ `order-service/src/main/java/com/example/order/service/impl/OrderServiceImpl.java:61` | `order-service/src/main/java/com/example/order/service/impl/OrderServiceImpl.java:66` |  | Missing in ours (introduced by theirs in the same class; arrives with that class if it merges cleanly, must be ported together when cherry-picking) |
| 12 | LOW | MISSING_DEPENDENCY | MISSING | FIELD | `com.example.order.service.impl.OrderServiceImpl.couponService` | THEIRS_NEW | `com.example.order.service.impl.OrderServiceImpl#refundOrder(Long,BigDecimal) [ADDED in theirs]` @ `order-service/src/main/java/com/example/order/service/impl/OrderServiceImpl.java:69` | `order-service/src/main/java/com/example/order/service/impl/OrderServiceImpl.java:27` |  | Missing in ours (introduced by theirs in the same class; arrives with that class if it merges cleanly, must be ported together when cherry-picking) |
| 13 | LOW | MISSING_DEPENDENCY | MISSING | FIELD | `com.example.order.service.impl.OrderServiceImpl.smsSender` | THEIRS_NEW | `com.example.order.service.impl.OrderServiceImpl#refundOrder(Long,BigDecimal) [ADDED in theirs]` @ `order-service/src/main/java/com/example/order/service/impl/OrderServiceImpl.java:70` | `order-service/src/main/java/com/example/order/service/impl/OrderServiceImpl.java:31` |  | Missing in ours (introduced by theirs in the same class; arrives with that class if it merges cleanly, must be ported together when cherry-picking) |
| 14 | MEDIUM | MAVEN_DEPENDENCY | MISSING | MAVEN_DEPENDENCY | `org.projectlombok:lombok` | THEIRS_NEW |  | `order-service/pom.xml` |  | Dependency declared in theirs' pom but not in ours' |

## 3. Spring config / bean gaps

### 3.1 Injected beans without a definition in ours

| # | Sev | Status | Kind | Symbol | Origin | Needed by / used at | Theirs | Ours | Notes |
|---|---|---|---|---|---|---|---|---|---|
| 1 | HIGH | MISSING | BEAN_NAME | `@Qualifier/@Resource("smsNotifier") NotificationSender` | THEIRS_NEW | `com.example.order.service.impl.OrderServiceImpl (field smsSender, field)` @ `order-service/src/main/java/com/example/order/service/impl/OrderServiceImpl.java:31` | `order-service/src/main/java/com/example/order/notify/SmsNotificationSender.java:5` |  | Bean named 'smsNotifier' is defined only in theirs (component) |
| 2 | HIGH | MISSING | BEAN | `com.example.order.coupon.CouponService` | THEIRS_NEW | `com.example.order.service.impl.OrderServiceImpl (field couponService, field)` @ `order-service/src/main/java/com/example/order/service/impl/OrderServiceImpl.java:27` | `order-service/src/main/java/com/example/order/coupon/CouponService.java:10` |  | No bean of type CouponService in ours (the type itself does not exist in ours); theirs defines it via component |

### 3.2 Config keys (@Value / Environment / @ConfigurationProperties) missing from ours' application*.yml|properties

| # | Sev | Status | Kind | Symbol | Origin | Needed by / used at | Theirs | Ours | Notes |
|---|---|---|---|---|---|---|---|---|---|
| 1 | HIGH | MISSING | PROPERTY | `order.coupon.expire-days` | THEIRS_NEW | `com.example.order.coupon.CouponService (field expireDays, @Value ${order.coupon.expire-days})` @ `order-service/src/main/java/com/example/order/coupon/CouponService.java:17` | `order-service/src/main/resources/application.yml:18` |  | Property is required (no default) and is defined in theirs' config but in none of ours' application*/bootstrap* files<br>theirsProfile: default<br>ours config files: [order-service/src/main/resources/application-prod.properties, order-service/src/main/resources/application.yml] |
| 2 | MEDIUM | MISSING | CONFIGURATION_PROPERTIES | `@ConfigurationProperties(prefix="order.payment") com.example.order.payment.PaymentProperties` | THEIRS_NEW | `com.example.order.payment.PaymentProperties` @ `order-service/src/main/java/com/example/order/payment/PaymentProperties.java:6` | `order-service/src/main/resources/application.yml:20` |  | Keys bound by this properties class are configured in theirs but missing from ours' config files (ours has nothing under prefix 'order.payment')<br>missingKeys: [order.payment.gateway-url (theirs: order-service/src/main/resources/application.yml:20), order.payment.timeout-ms (theirs: order-service/src/main/resources/application.yml:21)] |

### 3.3 Abstract/interface methods added by theirs that ours' implementations lack

| # | Sev | Status | Kind | Symbol | Origin | Needed by / used at | Theirs | Ours | Notes |
|---|---|---|---|---|---|---|---|---|---|
| 1 | HIGH | MISSING | IMPLEMENTATION | `com.example.order.service.impl.CachedOrderService must implement com.example.order.service.OrderService#refundOrder(Long,BigDecimal)` | THEIRS_NEW | `com.example.order.service.OrderService#refundOrder(Long,BigDecimal) [ADDED in theirs]` @ `order-service/src/main/java/com/example/order/service/OrderService.java:14` | `order-service/src/main/java/com/example/order/service/OrderService.java:14` | `order-service/src/main/java/com/example/order/service/impl/CachedOrderService.java:13` | Implementation exists only in ours (theirs never saw it) and does not implement the new abstract method: will not compile after merge |
| 2 | LOW | MISSING | IMPLEMENTATION | `com.example.order.service.impl.OrderServiceImpl must implement com.example.order.service.OrderService#refundOrder(Long,BigDecimal)` | THEIRS_NEW | `com.example.order.service.OrderService#refundOrder(Long,BigDecimal) [ADDED in theirs]` @ `order-service/src/main/java/com/example/order/service/OrderService.java:14` | `order-service/src/main/java/com/example/order/service/OrderService.java:14` | `order-service/src/main/java/com/example/order/service/impl/OrderServiceImpl.java:11` | Ours' version of this implementation lacks the method; theirs' version of the same class implements it, so it should arrive with the merge of that class (verify it merges cleanly)<br>theirsImplementation: order-service/src/main/java/com/example/order/service/impl/OrderServiceImpl.java:66 |

### 3.4 MyBatis mapper methods vs XML statement ids

| # | Sev | Status | Kind | Symbol | Origin | Needed by / used at | Theirs | Ours | Notes |
|---|---|---|---|---|---|---|---|---|---|
| 1 | HIGH | MISSING | MAPPER_STATEMENT | `com.example.order.mapper.OrderMapper#selectByCustomerId` | THEIRS_NEW | `com.example.order.mapper.OrderMapper#selectByCustomerId (theirs mapper method)` @ `order-service/src/main/java/com/example/order/mapper/OrderMapper.java:18` | `order-service/src/main/resources/mapper/OrderMapper.xml:22` |  | Ours is missing: interface method OrderMapper.selectByCustomerId(..) + XML statement id="selectByCustomerId" in order-service/src/main/resources/mapper/OrderMapper.xml<br>theirsXml: order-service/src/main/resources/mapper/OrderMapper.xml:22<br>statementKind: select |

## 4. Signature-change impact (places in ours still using an old signature, a deleted type, or a deleted/renamed field)

| # | Sev | Status | Kind | Symbol | Origin | Needed by / used at | Theirs | Ours | Notes |
|---|---|---|---|---|---|---|---|---|---|
| 1 | HIGH | STILL_USES_REMOVED_FIELD | CONSTANT_REMOVED | `com.example.common.CommonConstants.LEGACY_TENANT` | THEIRS_CHANGED | `com.example.order.export.OrderExportService#exportLine(Long) [in ours]` @ `order-service/src/main/java/com/example/order/export/OrderExportService.java:33` | `common/src/main/java/com/example/common/CommonConstants.java:3` | `order-service/src/main/java/com/example/order/export/OrderExportService.java:33` | Theirs removed constant CommonConstants.LEGACY_TENANT but ours still references it; the referencing code was ADDED by ours: the merge will not update it<br>reference: CommonConstants.LEGACY_TENANT<br>base: common/src/main/java/com/example/common/CommonConstants.java:6 |
| 2 | HIGH | STILL_USES_REMOVED_FIELD | CONSTANT_RENAMED | `com.example.order.constants.OrderConstants.EXPORT_SEPARATOR` | THEIRS_CHANGED | `com.example.order.export.ExportLimits.SEP (field initializer) [in ours]` @ `order-service/src/main/java/com/example/order/export/ExportLimits.java:8` | `order-service/src/main/java/com/example/order/constants/OrderConstants.java:11` | `order-service/src/main/java/com/example/order/export/ExportLimits.java:8` | Theirs renamed constant OrderConstants.EXPORT_SEPARATOR -> CSV_SEPARATOR but ours still references it; the referencing declaration was added/changed by ours: the merge will not update it<br>reference: OrderConstants.EXPORT_SEPARATOR<br>base: order-service/src/main/java/com/example/order/constants/OrderConstants.java:13 |
| 3 | HIGH | STILL_USES_REMOVED_FIELD | CONSTANT_RENAMED | `com.example.order.constants.OrderConstants.EXPORT_SEPARATOR` | THEIRS_CHANGED | `com.example.order.export.OrderExportService#exportLine(Long) [in ours]` @ `order-service/src/main/java/com/example/order/export/OrderExportService.java:32` | `order-service/src/main/java/com/example/order/constants/OrderConstants.java:11` | `order-service/src/main/java/com/example/order/export/OrderExportService.java:32` | Theirs renamed constant OrderConstants.EXPORT_SEPARATOR -> CSV_SEPARATOR but ours still references it; the referencing code was ADDED by ours: the merge will not update it<br>reference: OrderConstants.EXPORT_SEPARATOR<br>base: order-service/src/main/java/com/example/order/constants/OrderConstants.java:13 |
| 4 | HIGH | STILL_USES_REMOVED_FIELD | ENUM_CONSTANT_REMOVED | `com.example.order.model.OrderStatus.CLOSED` | THEIRS_CHANGED | `com.example.order.service.impl.CachedOrderService#isClosed(Long) [in ours]` @ `order-service/src/main/java/com/example/order/service/impl/CachedOrderService.java:44` | `order-service/src/main/java/com/example/order/model/OrderStatus.java:3` | `order-service/src/main/java/com/example/order/service/impl/CachedOrderService.java:44` | Theirs removed enum constant OrderStatus.CLOSED but ours still references it; the referencing code was ADDED by ours: the merge will not update it<br>reference: OrderStatus.CLOSED<br>base: order-service/src/main/java/com/example/order/model/OrderStatus.java:7 |
| 5 | HIGH | STILL_USES_OLD_SIGNATURE | SIGNATURE_CHANGED | `com.example.order.service.PricingService#applyDiscount(BigDecimal,int)` | THEIRS_CHANGED | `com.example.order.export.OrderExportService#exportLine(Long) [in ours]` @ `order-service/src/main/java/com/example/order/export/OrderExportService.java:31` | `order-service/src/main/java/com/example/order/service/PricingService.java:23` | `order-service/src/main/java/com/example/order/export/OrderExportService.java:31` | Theirs changed the parameters of applyDiscount(BigDecimal,int) -> applyDiscount(BigDecimal,int,boolean); caller was ADDED by ours: the merge will not update it<br>callSite: pricingService.applyDiscount(order.getAmount(), 10) |
| 6 | HIGH | STILL_USES_REMOVED_FIELD | FIELD_RENAMED | `com.example.order.service.ShippingService.freeThreshold` | THEIRS_CHANGED | `com.example.order.service.ShippingService#isFreeShipping(Order) [in ours]` @ `order-service/src/main/java/com/example/order/service/ShippingService.java:18` | `order-service/src/main/java/com/example/order/service/ShippingService.java:8` | `order-service/src/main/java/com/example/order/service/ShippingService.java:18` | Theirs renamed field ShippingService.freeThreshold -> freeShippingThreshold but ours still references it; the referencing code was ADDED by ours: the merge will not update it<br>reference: freeThreshold<br>base: order-service/src/main/java/com/example/order/service/ShippingService.java:8 |
| 7 | HIGH | STILL_USES_OLD_SIGNATURE | RENAMED | `com.example.order.util.OrderNumberGenerator#generate(Long)` | THEIRS_CHANGED | `com.example.order.export.OrderExportService#exportLine(Long) [in ours]` @ `order-service/src/main/java/com/example/order/export/OrderExportService.java:33` | `order-service/src/main/java/com/example/order/util/OrderNumberGenerator.java:9` | `order-service/src/main/java/com/example/order/export/OrderExportService.java:33` | Theirs renamed generate(Long) -> nextOrderNo(Long); caller was ADDED by ours: the merge will not update it<br>callSite: OrderNumberGenerator.generate(id) |
| 8 | LOW | STILL_USES_REMOVED_FIELD | FIELD_RENAMED | `com.example.order.service.ShippingService.freeThreshold` | THEIRS_CHANGED | `com.example.order.service.ShippingService#fee(Order) [in ours]` @ `order-service/src/main/java/com/example/order/service/ShippingService.java:11` | `order-service/src/main/java/com/example/order/service/ShippingService.java:8` | `order-service/src/main/java/com/example/order/service/ShippingService.java:11` | Theirs renamed field ShippingService.freeThreshold -> freeShippingThreshold but ours still references it; referencing method unchanged in ours; theirs updated it, so a clean merge carries theirs' version<br>reference: freeThreshold<br>base: order-service/src/main/java/com/example/order/service/ShippingService.java:8 |
| 9 | LOW | STILL_USES_OLD_SIGNATURE | RENAMED | `com.example.order.util.OrderNumberGenerator#generate(Long)` | THEIRS_CHANGED | `com.example.order.service.impl.OrderServiceImpl#createOrder(Order) [in ours]` @ `order-service/src/main/java/com/example/order/service/impl/OrderServiceImpl.java:26` | `order-service/src/main/java/com/example/order/util/OrderNumberGenerator.java:9` | `order-service/src/main/java/com/example/order/service/impl/OrderServiceImpl.java:26` | Theirs renamed generate(Long) -> nextOrderNo(Long); caller unchanged in ours; theirs updated it, so a clean merge carries theirs' version<br>callSite: OrderNumberGenerator.generate(order.getId()) |

## 4b. Values changed by theirs (constant / field initializers, MyBatis XML statement bodies)

LOW = only theirs changed it (a whole-branch merge carries it; a backport/cherry-pick must port it) · HIGH = ours and theirs changed it differently.

_none_

## 5. Methods modified on both sides (manual review required)

### `com.example.order.service.PricingService#calculatePrice(Order)` — modified/modified

- ours: MODIFIED, theirs: MODIFIED
- ours: `order-service/src/main/java/com/example/order/service/PricingService.java:12-18` · theirs: `order-service/src/main/java/com/example/order/service/PricingService.java:15-21` · base: `order-service/src/main/java/com/example/order/service/PricingService.java:12`
- git textual conflict: **NO - git merges this file cleanly, so only semantic review can catch contradictions**

<details><summary>diffBaseToOurs</summary>

```diff
  public BigDecimal calculatePrice(Order order) {
-     BigDecimal amount = order.getAmount();
+     BigDecimal amount = order.getAmount() == null ? null : order.getAmount().multiply(new BigDecimal("1.06"));
      if (amount == null) {
  ...
```
</details>

<details><summary>diffBaseToTheirs</summary>

```diff
      }
-     return amount.setScale(2, RoundingMode.HALF_UP);
+     return amount.multiply(new BigDecimal("1.06")).setScale(2, RoundingMode.HALF_UP);
  }
```
</details>

### `com.example.order.service.impl.OrderServiceImpl#cancelOrder(Long)` — modified/modified

- ours: MODIFIED, theirs: MODIFIED
- ours: `order-service/src/main/java/com/example/order/service/impl/OrderServiceImpl.java:38-48` · theirs: `order-service/src/main/java/com/example/order/service/impl/OrderServiceImpl.java:54-64` · base: `order-service/src/main/java/com/example/order/service/impl/OrderServiceImpl.java:35`
- git textual conflict: **YES - 1 conflict hunk(s) in order-service/src/main/java/com/example/order/service/impl/OrderServiceImpl.java**

<details><summary>diffBaseToOurs</summary>

```diff
      }
+     if (order.getStatus() == OrderStatus.PAID) {
+         throw new IllegalStateException("paid order cannot be cancelled: " + id);
+     }
      orderMapper.updateStatus(id, OrderStatus.CANCELLED.name());
  ...
```
</details>

<details><summary>diffBaseToTheirs</summary>

```diff
      }
+     if (order.getStatus() == OrderStatus.PAID) {
+         refundOrder(id, order.getAmount());
+     }
      orderMapper.updateStatus(id, OrderStatus.CANCELLED.name());
  ...
```
</details>

## 6. Uncertain items (name-only matches or unconfirmed origin; verify manually)

_none_

## Appendix: method-level change sets

<details><summary>base → theirs (24 changes)</summary>

| Kind | Method | Location |
|---|---|---|
| ADDED | `com.example.order.mapper.OrderMapper#selectByCustomerId(Long) : List` | `order-service/src/main/java/com/example/order/mapper/OrderMapper.java:18` |
| ADDED | `com.example.order.mapper.OrderMapper#countAll() : int` | `order-service/src/main/java/com/example/order/mapper/OrderMapper.java:20` |
| ADDED | `com.example.order.service.OrderService#refundOrder(Long,BigDecimal) : void` | `order-service/src/main/java/com/example/order/service/OrderService.java:14` |
| ADDED | `com.example.order.service.OrderService#describe(Long) : String` | `order-service/src/main/java/com/example/order/service/OrderService.java:16` |
| MODIFIED | `com.example.order.service.PricingService#calculatePrice(Order) : BigDecimal` | `order-service/src/main/java/com/example/order/service/PricingService.java:15` |
| SIGNATURE_CHANGED | `com.example.order.service.PricingService#applyDiscount(BigDecimal,int)  ->  applyDiscount(BigDecimal,int,boolean)` | `order-service/src/main/java/com/example/order/service/PricingService.java:23` |
| ADDED | `com.example.order.service.PricingService#calculateFinalPrice(Order,String,int) : BigDecimal` | `order-service/src/main/java/com/example/order/service/PricingService.java:30` |
| MODIFIED | `com.example.order.service.ShippingService#fee(Order) : BigDecimal` | `order-service/src/main/java/com/example/order/service/ShippingService.java:10` |
| MODIFIED | `com.example.order.service.impl.OrderServiceImpl#createOrder(Order) : Order` | `order-service/src/main/java/com/example/order/service/impl/OrderServiceImpl.java:38` |
| MODIFIED | `com.example.order.service.impl.OrderServiceImpl#getOrder(Long) : Order` | `order-service/src/main/java/com/example/order/service/impl/OrderServiceImpl.java:46` |
| MODIFIED | `com.example.order.service.impl.OrderServiceImpl#cancelOrder(Long) : void` | `order-service/src/main/java/com/example/order/service/impl/OrderServiceImpl.java:54` |
| ADDED | `com.example.order.service.impl.OrderServiceImpl#refundOrder(Long,BigDecimal) : void` | `order-service/src/main/java/com/example/order/service/impl/OrderServiceImpl.java:66` |
| ADDED | `com.example.order.service.impl.OrderServiceImpl#listCustomerOrders(Long) : List` | `order-service/src/main/java/com/example/order/service/impl/OrderServiceImpl.java:73` |
| ADDED | `com.example.order.util.MoneyUtils#roundHalfUp(BigDecimal) : BigDecimal` | `order-service/src/main/java/com/example/order/util/MoneyUtils.java:14` |
| RENAMED | `com.example.order.util.OrderNumberGenerator#generate(Long)  ->  nextOrderNo(Long)` | `order-service/src/main/java/com/example/order/util/OrderNumberGenerator.java:9` |
| ADDED | `com.example.order.coupon.CouponService#preview(BigDecimal,int) : BigDecimal` | `order-service/src/main/java/com/example/order/coupon/CouponService.java:25` |
| ADDED | `com.example.order.coupon.CouponService#releaseCoupons(Long) : void` | `order-service/src/main/java/com/example/order/coupon/CouponService.java:29` |
| ADDED | `com.example.order.notify.SmsNotificationSender#send(String,String) : void` | `order-service/src/main/java/com/example/order/notify/SmsNotificationSender.java:7` |
| ADDED | `com.example.order.payment.PaymentProperties#getGatewayUrl() : String` | `order-service/src/main/java/com/example/order/payment/PaymentProperties.java:13` |
| ADDED | `com.example.order.payment.PaymentProperties#setGatewayUrl(String) : void` | `order-service/src/main/java/com/example/order/payment/PaymentProperties.java:14` |
| ADDED | `com.example.order.payment.PaymentProperties#getTimeoutMs() : int` | `order-service/src/main/java/com/example/order/payment/PaymentProperties.java:15` |
| ADDED | `com.example.order.payment.PaymentProperties#setTimeoutMs(int) : void` | `order-service/src/main/java/com/example/order/payment/PaymentProperties.java:16` |
| ADDED | `com.example.order.payment.PaymentProperties#getRetryCount() : int` | `order-service/src/main/java/com/example/order/payment/PaymentProperties.java:17` |
| ADDED | `com.example.order.payment.PaymentProperties#setRetryCount(int) : void` | `order-service/src/main/java/com/example/order/payment/PaymentProperties.java:18` |

</details>

<details><summary>base → ours (13 changes)</summary>

| Kind | Method | Location |
|---|---|---|
| MODIFIED | `com.example.order.service.PricingService#calculatePrice(Order) : BigDecimal` | `order-service/src/main/java/com/example/order/service/PricingService.java:12` |
| ADDED | `com.example.order.service.ShippingService#isFreeShipping(Order) : boolean` | `order-service/src/main/java/com/example/order/service/ShippingService.java:17` |
| MODIFIED | `com.example.order.service.impl.OrderServiceImpl#getOrder(Long) : Order` | `order-service/src/main/java/com/example/order/service/impl/OrderServiceImpl.java:30` |
| MODIFIED | `com.example.order.service.impl.OrderServiceImpl#cancelOrder(Long) : void` | `order-service/src/main/java/com/example/order/service/impl/OrderServiceImpl.java:38` |
| ADDED | `com.example.order.export.ExportLimits#<init>()` | `order-service/src/main/java/com/example/order/export/ExportLimits.java:10` |
| ADDED | `com.example.order.export.OrderExportService#<init>(OrderMapper,PricingService)` | `order-service/src/main/java/com/example/order/export/OrderExportService.java:24` |
| ADDED | `com.example.order.export.OrderExportService#exportLine(Long) : String` | `order-service/src/main/java/com/example/order/export/OrderExportService.java:29` |
| ADDED | `com.example.order.export.OrderExportService#maxLines() : int` | `order-service/src/main/java/com/example/order/export/OrderExportService.java:36` |
| ADDED | `com.example.order.service.impl.CachedOrderService#<init>(OrderServiceImpl)` | `order-service/src/main/java/com/example/order/service/impl/CachedOrderService.java:20` |
| ADDED | `com.example.order.service.impl.CachedOrderService#createOrder(Order) : Order` | `order-service/src/main/java/com/example/order/service/impl/CachedOrderService.java:24` |
| ADDED | `com.example.order.service.impl.CachedOrderService#getOrder(Long) : Order` | `order-service/src/main/java/com/example/order/service/impl/CachedOrderService.java:31` |
| ADDED | `com.example.order.service.impl.CachedOrderService#cancelOrder(Long) : void` | `order-service/src/main/java/com/example/order/service/impl/CachedOrderService.java:36` |
| ADDED | `com.example.order.service.impl.CachedOrderService#isClosed(Long) : boolean` | `order-service/src/main/java/com/example/order/service/impl/CachedOrderService.java:42` |

</details>

