#!/usr/bin/env bash
# Creates a sample Spring Boot repo with a merge-base, an 'ours' branch and a 'theirs' branch
# that plant known semantic gaps (see README "Verification sample").
set -euo pipefail
DEST="${1:-/workspace/branch-diff-sample}"
rm -rf "$DEST"
mkdir -p "$DEST"
cd "$DEST"
git init -q -b main
git config user.name "Sample Bot"
git config user.email "sample@example.com"
export GIT_AUTHOR_DATE="2026-01-01T10:00:00+08:00" GIT_COMMITTER_DATE="2026-01-01T10:00:00+08:00"

S=order-service
J=$S/src/main/java/com/example/order
R=$S/src/main/resources
w() { mkdir -p "$(dirname "$1")"; cat > "$1"; }

############################################################################ BASE
w $S/pom.xml <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.5</version>
    <relativePath/>
  </parent>
  <groupId>com.example</groupId>
  <artifactId>order-service</artifactId>
  <version>1.0.0</version>
  <properties>
    <java.version>17</java.version>
  </properties>
  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.mybatis.spring.boot</groupId>
      <artifactId>mybatis-spring-boot-starter</artifactId>
      <version>3.0.3</version>
    </dependency>
    <dependency>
      <groupId>com.h2database</groupId>
      <artifactId>h2</artifactId>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>${project.groupId}</groupId>
      <artifactId>order-common</artifactId>
      <version>1.0.0</version>
    </dependency>
  </dependencies>
</project>
EOF

w $J/OrderApplication.java <<'EOF'
package com.example.order;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.example.order.mapper")
public class OrderApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }
}
EOF

w $J/model/OrderStatus.java <<'EOF'
package com.example.order.model;

public enum OrderStatus {
    CREATED,
    PAID,
    CANCELLED,
    CLOSED
}
EOF

w $J/model/Order.java <<'EOF'
package com.example.order.model;

import com.example.order.constants.OrderConstants;

import java.math.BigDecimal;

public class Order {
    private Long id;
    private Long customerId;
    private String orderNo;
    private BigDecimal amount;
    private OrderStatus status = OrderStatus.CREATED;
    private String currency = OrderConstants.DEFAULT_CURRENCY;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
}
EOF

w $J/constants/OrderConstants.java <<'EOF'
package com.example.order.constants;

public final class OrderConstants {

    public static final int MAX_ITEMS = 50;

    public static final String ORDER_NO_PREFIX = "ORD";

    public static final String DEFAULT_CURRENCY = "CNY";

    public static final int ORDER_NO_WIDTH = 10;

    public static final String EXPORT_SEPARATOR = ",";

    public static final String LEGACY_CHANNEL = "fax";

    private OrderConstants() {
    }
}
EOF

w $J/util/MoneyUtils.java <<'EOF'
package com.example.order.util;

import java.math.BigDecimal;

public final class MoneyUtils {
    private MoneyUtils() {
    }

    public static boolean isPositive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }
}
EOF

w $J/util/OrderNumberGenerator.java <<'EOF'
package com.example.order.util;

import com.example.order.constants.OrderConstants;

public final class OrderNumberGenerator {
    private OrderNumberGenerator() {
    }

    public static String generate(Long id) {
        String digits = String.format("%0" + OrderConstants.ORDER_NO_WIDTH + "d", id);
        return OrderConstants.ORDER_NO_PREFIX + "-" + digits;
    }
}
EOF

w $J/mapper/OrderMapper.java <<'EOF'
package com.example.order.mapper;

import com.example.order.model.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrderMapper {
    Order selectById(Long id);

    int insert(Order order);

    int updateStatus(@Param("id") Long id, @Param("status") String status);
}
EOF

w $R/mapper/OrderMapper.xml <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.example.order.mapper.OrderMapper">
    <resultMap id="orderMap" type="com.example.order.model.Order">
        <id property="id" column="id"/>
        <result property="customerId" column="customer_id"/>
        <result property="orderNo" column="order_no"/>
        <result property="amount" column="amount"/>
        <result property="status" column="status"/>
        <result property="currency" column="currency"/>
    </resultMap>

    <select id="selectById" resultMap="orderMap">
        select * from orders where id = #{id}
    </select>

    <insert id="insert" useGeneratedKeys="true" keyProperty="id">
        insert into orders (customer_id, order_no, amount, status, currency)
        values (#{customerId}, #{orderNo}, #{amount}, #{status}, #{currency})
    </insert>

    <update id="updateStatus">
        update orders set status = #{status} where id = #{id}
    </update>
</mapper>
EOF

w $J/service/OrderService.java <<'EOF'
package com.example.order.service;

import com.example.order.model.Order;

public interface OrderService {
    Order createOrder(Order order);

    Order getOrder(Long id);

    void cancelOrder(Long id);
}
EOF

w $J/service/PricingService.java <<'EOF'
package com.example.order.service;

import com.example.order.model.Order;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class PricingService {

    public BigDecimal calculatePrice(Order order) {
        BigDecimal amount = order.getAmount();
        if (amount == null) {
            return BigDecimal.ZERO;
        }
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal applyDiscount(BigDecimal amount, int percent) {
        BigDecimal factor = BigDecimal.valueOf(100 - percent).divide(BigDecimal.valueOf(100));
        return amount.multiply(factor).setScale(2, RoundingMode.HALF_UP);
    }
}
EOF

w $J/service/impl/OrderServiceImpl.java <<'EOF'
package com.example.order.service.impl;

import com.example.order.mapper.OrderMapper;
import com.example.order.model.Order;
import com.example.order.model.OrderStatus;
import com.example.order.service.OrderService;
import com.example.order.service.PricingService;
import com.example.order.util.OrderNumberGenerator;
import org.springframework.stereotype.Service;

@Service
public class OrderServiceImpl implements OrderService {

    private final OrderMapper orderMapper;
    private final PricingService pricingService;

    public OrderServiceImpl(OrderMapper orderMapper, PricingService pricingService) {
        this.orderMapper = orderMapper;
        this.pricingService = pricingService;
    }

    @Override
    public Order createOrder(Order order) {
        order.setAmount(pricingService.calculatePrice(order));
        orderMapper.insert(order);
        order.setOrderNo(OrderNumberGenerator.generate(order.getId()));
        return order;
    }

    @Override
    public Order getOrder(Long id) {
        return orderMapper.selectById(id);
    }

    @Override
    public void cancelOrder(Long id) {
        Order order = orderMapper.selectById(id);
        if (order == null) {
            throw new IllegalArgumentException("order not found: " + id);
        }
        orderMapper.updateStatus(id, OrderStatus.CANCELLED.name());
    }
}
EOF

w $J/notify/NotificationSender.java <<'EOF'
package com.example.order.notify;

public interface NotificationSender {
    void send(String to, String message);
}
EOF

w $J/notify/EmailNotificationSender.java <<'EOF'
package com.example.order.notify;

import org.springframework.stereotype.Component;

@Component("emailNotifier")
public class EmailNotificationSender implements NotificationSender {
    @Override
    public void send(String to, String message) {
        System.out.println("mail to " + to + ": " + message);
    }
}
EOF

w $J/controller/OrderController.java <<'EOF'
package com.example.order.controller;

import com.example.order.model.Order;
import com.example.order.service.OrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
public class OrderController {
    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping("/{id}")
    public Order getOrder(@PathVariable Long id) {
        return orderService.getOrder(id);
    }

    @PostMapping
    public Order createOrder(@RequestBody Order order) {
        return orderService.createOrder(order);
    }

    @PostMapping("/{id}/cancel")
    public void cancel(@PathVariable Long id) {
        orderService.cancelOrder(id);
    }
}
EOF

w $R/application.yml <<'EOF'
server:
  port: 8080

spring:
  application:
    name: order-service
  datasource:
    url: jdbc:h2:mem:orders
    username: sa
    password: ""

mybatis:
  mapper-locations: classpath:mapper/*.xml

order:
  max-items: 50
EOF

w $R/application-prod.properties <<'EOF'
# production overrides
order.notify.channel=email
spring.datasource.url=jdbc:mysql://db:3306/orders
EOF

w other-service/src/main/java/com/example/other/OtherThing.java <<'EOF'
package com.example.other;

public class OtherThing {
    public String name() {
        return "other";
    }
}
EOF
# G9c: instance field that theirs renames
w $J/service/ShippingService.java <<'EOF'
package com.example.order.service;

import com.example.order.model.Order;

import java.math.BigDecimal;

public class ShippingService {
    private final BigDecimal freeThreshold = new BigDecimal("99");

    public BigDecimal fee(Order order) {
        if (order.getAmount() != null && order.getAmount().compareTo(freeThreshold) >= 0) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal("8");
    }
}
EOF

# sibling Maven module 'common' (order-service depends on it) + root aggregator pom
w pom.xml <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.example</groupId>
  <artifactId>sample-parent</artifactId>
  <version>1.0.0</version>
  <packaging>pom</packaging>
  <modules>
    <module>common</module>
    <module>order-service</module>
  </modules>
</project>
EOF
w common/pom.xml <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.example</groupId>
  <artifactId>order-common</artifactId>
  <version>1.0.0</version>
</project>
EOF
C=common/src/main/java/com/example/common
w $C/text/TextUtils.java <<'EOF'
package com.example.common.text;

public final class TextUtils {
    private TextUtils() {
    }

    public static String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }
}
EOF
w $C/id/IdUtils.java <<'EOF'
package com.example.common.id;

public final class IdUtils {
    private IdUtils() {
    }

    public static boolean isValid(Long id) {
        return id != null && id > 0;
    }
}
EOF
w $C/CommonConstants.java <<'EOF'
package com.example.common;

public final class CommonConstants {
    public static final String DEFAULT_TENANT = "t0";

    public static final String LEGACY_TENANT = "legacy";

    private CommonConstants() {
    }
}
EOF
w $C/time/DateUtils.java <<'EOF'
package com.example.common.time;

import java.time.LocalDate;

public final class DateUtils {
    private DateUtils() {
    }

    public static String today() {
        return LocalDate.now().toString();
    }
}
EOF

printf '# sample monorepo\n\norder-service: Spring Boot sample used by branch-diff-tool verification.\n' > README.md

git add -A
git commit -q -m "base: order-service"

############################################################################ THEIRS
git checkout -q -b theirs
export GIT_AUTHOR_DATE="2026-01-05T10:00:00+08:00" GIT_COMMITTER_DATE="2026-01-05T10:00:00+08:00"

# G8: theirs adds lombok
python3 - "$S/pom.xml" <<'EOF'
import sys
p=sys.argv[1]; s=open(p).read()
s=s.replace("""      <scope>runtime</scope>
    </dependency>
""","""      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>org.projectlombok</groupId>
      <artifactId>lombok</artifactId>
      <optional>true</optional>
    </dependency>
""")
open(p,'w').write(s)
EOF

w $J/model/OrderStatus.java <<'EOF'
package com.example.order.model;

public enum OrderStatus {
    CREATED,
    PAID,
    CANCELLED,
    REFUNDED
}
EOF

# G1b: new constant at the end (DEFAULT_CURRENCY untouched by theirs)
# G9a: EXPORT_SEPARATOR renamed to CSV_SEPARATOR ; N13: unused LEGACY_CHANNEL removed ;
# N14: MAX_ITEMS removed (ours only uses its own ExportLimits.MAX_ITEMS)
w $J/constants/OrderConstants.java <<'EOF'
package com.example.order.constants;

public final class OrderConstants {

    public static final String ORDER_NO_PREFIX = "ORD";

    public static final String DEFAULT_CURRENCY = "CNY";

    public static final int ORDER_NO_WIDTH = 10;

    public static final String CSV_SEPARATOR = ",";

    public static final int MAX_DISCOUNT_PERCENT = 30;

    private OrderConstants() {
    }
}
EOF

# G1a: new helper
w $J/util/MoneyUtils.java <<'EOF'
package com.example.order.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class MoneyUtils {
    private MoneyUtils() {
    }

    public static boolean isPositive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    public static BigDecimal roundHalfUp(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }
}
EOF

# G6a: rename generate(Long) -> nextOrderNo(Long), same body
w $J/util/OrderNumberGenerator.java <<'EOF'
package com.example.order.util;

import com.example.order.constants.OrderConstants;

public final class OrderNumberGenerator {
    private OrderNumberGenerator() {
    }

    public static String nextOrderNo(Long id) {
        String digits = String.format("%0" + OrderConstants.ORDER_NO_WIDTH + "d", id);
        return OrderConstants.ORDER_NO_PREFIX + "-" + digits;
    }
}
EOF

# G5: mapper method + XML id ; N11: annotated mapper method (no XML needed)
w $J/mapper/OrderMapper.java <<'EOF'
package com.example.order.mapper;

import com.example.order.model.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface OrderMapper {
    Order selectById(Long id);

    int insert(Order order);

    int updateStatus(@Param("id") Long id, @Param("status") String status);

    List<Order> selectByCustomerId(@Param("customerId") Long customerId);

    @Select("select count(*) from orders")
    int countAll();
}
EOF
python3 - "$R/mapper/OrderMapper.xml" <<'EOF'
import sys
p=sys.argv[1]; s=open(p).read()
s=s.replace("""    <update id="updateStatus">""","""    <select id="selectByCustomerId" resultMap="orderMap">
        select * from orders where customer_id = #{customerId} order by id desc
    </select>

    <update id="updateStatus">""")
open(p,'w').write(s)
EOF

# G4: new interface method ; N12: default method
w $J/service/OrderService.java <<'EOF'
package com.example.order.service;

import com.example.order.model.Order;

import java.math.BigDecimal;

public interface OrderService {
    Order createOrder(Order order);

    Order getOrder(Long id);

    void cancelOrder(Long id);

    void refundOrder(Long id, BigDecimal amount);

    default String describe(Long id) {
        return "order#" + id;
    }
}
EOF

# G1: calculateFinalPrice (A) -> MoneyUtils.roundHalfUp (B, theirs only), MAX_DISCOUNT_PERCENT (theirs only),
#     DEFAULT_CURRENCY (ours removes it), calculatePrice (exists everywhere, N2)
# G6b: applyDiscount(BigDecimal,int) -> applyDiscount(BigDecimal,int,boolean)
# G7b: calculatePrice last line changed (tax added at the end)
w $J/service/PricingService.java <<'EOF'
package com.example.order.service;

import com.example.order.constants.OrderConstants;
import com.example.order.model.Order;
import com.example.order.util.MoneyUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class PricingService {

    public BigDecimal calculatePrice(Order order) {
        BigDecimal amount = order.getAmount();
        if (amount == null) {
            return BigDecimal.ZERO;
        }
        return amount.multiply(new BigDecimal("1.06")).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal applyDiscount(BigDecimal amount, int percent, boolean capAtMax) {
        int effective = capAtMax ? Math.min(percent, OrderConstants.MAX_DISCOUNT_PERCENT) : percent;
        BigDecimal factor = BigDecimal.valueOf(100 - effective).divide(BigDecimal.valueOf(100));
        return amount.multiply(factor).setScale(2, RoundingMode.HALF_UP);
    }

    /** Final price after coupon; currency is always the default one for now. */
    public BigDecimal calculateFinalPrice(Order order, String couponCode, int couponPercent) {
        BigDecimal price = calculatePrice(order);
        if (StringUtils.hasText(couponCode)) {
            price = applyDiscount(price, couponPercent, true);
        }
        if (!OrderConstants.DEFAULT_CURRENCY.equals(order.getCurrency())) {
            throw new IllegalArgumentException("unsupported currency " + order.getCurrency());
        }
        return MoneyUtils.roundHalfUp(price);
    }
}
EOF

# G2: new bean (lombok constructor injection of an EXISTING bean = N5), @Value keys
w $J/coupon/CouponService.java <<'EOF'
package com.example.order.coupon;

import com.example.order.service.PricingService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class CouponService {

    private final PricingService pricingService;

    @Value("${order.coupon.expire-days}")
    private int expireDays;

    @Value("${order.audit.enabled:false}")
    private boolean auditEnabled;

    @Value("${order.notify.channel}")
    private String notifyChannel;

    public BigDecimal preview(BigDecimal amount, int percent) {
        return pricingService.applyDiscount(amount, percent, true);
    }

    public void releaseCoupons(Long orderId) {
        if (auditEnabled) {
            System.out.println("release coupons of " + orderId + " (expire " + expireDays + "d, via " + notifyChannel + ")");
        }
    }
}
EOF

w $J/notify/SmsNotificationSender.java <<'EOF'
package com.example.order.notify;

import org.springframework.stereotype.Component;

@Component("smsNotifier")
public class SmsNotificationSender implements NotificationSender {
    @Override
    public void send(String to, String message) {
        System.out.println("sms to " + to + ": " + message);
    }
}
EOF

# G3b: @ConfigurationProperties (retryCount has a Java default and no key -> must not be flagged)
w $J/payment/PaymentProperties.java <<'EOF'
package com.example.order.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "order.payment")
public class PaymentProperties {
    private String gatewayUrl;
    private int timeoutMs;
    private int retryCount = 3;

    public String getGatewayUrl() { return gatewayUrl; }
    public void setGatewayUrl(String gatewayUrl) { this.gatewayUrl = gatewayUrl; }
    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
}
EOF

# OrderServiceImpl: @Autowired CouponService (G2a), @Qualifier("smsNotifier") (G2c), refundOrder (G4 impl),
# listCustomerOrders -> selectByCustomerId (G5), createOrder uses renamed generator (theirs updated caller),
# cancelOrder modified (G7a), getOrder identical edit with ours (N9)
w $J/service/impl/OrderServiceImpl.java <<'EOF'
package com.example.order.service.impl;

import com.example.common.id.IdUtils;
import com.example.common.text.TextUtils;
import com.example.order.coupon.CouponService;
import com.example.order.mapper.OrderMapper;
import com.example.order.model.Order;
import com.example.order.model.OrderStatus;
import com.example.order.notify.NotificationSender;
import com.example.order.service.OrderService;
import com.example.order.service.PricingService;
import com.example.order.util.OrderNumberGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
public class OrderServiceImpl implements OrderService {

    private final OrderMapper orderMapper;
    private final PricingService pricingService;

    @Autowired
    private CouponService couponService;

    @Autowired
    @Qualifier("smsNotifier")
    private NotificationSender smsSender;

    public OrderServiceImpl(OrderMapper orderMapper, PricingService pricingService) {
        this.orderMapper = orderMapper;
        this.pricingService = pricingService;
    }

    @Override
    public Order createOrder(Order order) {
        order.setAmount(pricingService.calculatePrice(order));
        orderMapper.insert(order);
        order.setOrderNo(OrderNumberGenerator.nextOrderNo(order.getId()));
        return order;
    }

    @Override
    public Order getOrder(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
        return orderMapper.selectById(id);
    }

    @Override
    public void cancelOrder(Long id) {
        Order order = orderMapper.selectById(id);
        if (order == null) {
            throw new IllegalArgumentException("order not found: " + id);
        }
        if (order.getStatus() == OrderStatus.PAID) {
            refundOrder(id, order.getAmount());
        }
        orderMapper.updateStatus(id, OrderStatus.CANCELLED.name());
    }

    @Override
    public void refundOrder(Long id, BigDecimal amount) {
        orderMapper.updateStatus(id, OrderStatus.REFUNDED.name());
        couponService.releaseCoupons(id);
        smsSender.send(TextUtils.maskPhone(String.valueOf(id)), "refunded " + amount);
    }

    public List<Order> listCustomerOrders(Long customerId) {
        if (!IdUtils.isValid(customerId)) {
            throw new IllegalArgumentException("bad customer id " + TextUtils.trimToEmpty(String.valueOf(customerId)));
        }
        return orderMapper.selectByCustomerId(customerId);
    }
}
EOF

# N1: comment / formatting-only change in controller
w $J/controller/OrderController.java <<'EOF'
package com.example.order.controller;

import com.example.order.model.Order;
import com.example.order.service.OrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
public class OrderController {
    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping("/{id}")
    public Order getOrder(@PathVariable Long id) {
        return orderService.getOrder(id);
    }

    /**
     * Creates an order. (theirs: documentation only)
     */
    @PostMapping
    public Order createOrder(@RequestBody Order order) {
        // delegate to the service layer
        return orderService
                .createOrder(order);
    }

    @PostMapping("/{id}/cancel")
    public void cancel(@PathVariable Long id) {
        orderService.cancelOrder(id);
    }
}
EOF

w $R/application.yml <<'EOF'
server:
  port: 8080

spring:
  application:
    name: order-service
  datasource:
    url: jdbc:h2:mem:orders
    username: sa
    password: ""

mybatis:
  mapper-locations: classpath:mapper/*.xml

order:
  max-items: 50
  coupon:
    expire-days: 30
  payment:
    gateway-url: https://pay.example.com/api
    timeout-ms: 3000
EOF

# N10: gap outside the analysed service dir must not be reported
w other-service/src/main/java/com/example/other/OtherThing.java <<'EOF'
package com.example.other;

public class OtherThing {
    public String name() {
        return OtherHelper.prefix() + "other";
    }
}
EOF
w other-service/src/main/java/com/example/other/OtherHelper.java <<'EOF'
package com.example.other;

public class OtherHelper {
    public static String prefix() {
        return "x-";
    }
}
EOF

# G9c: theirs renames the instance field and updates fee() (ours adds a new method still using the old name)
w $J/service/ShippingService.java <<'EOF'
package com.example.order.service;

import com.example.order.model.Order;

import java.math.BigDecimal;

public class ShippingService {
    private final BigDecimal freeShippingThreshold = new BigDecimal("99");

    public BigDecimal fee(Order order) {
        if (order.getAmount() != null && order.getAmount().compareTo(freeShippingThreshold) >= 0) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal("8");
    }
}
EOF

# sibling module changes in theirs:
# G10a: new TextUtils.maskPhone used by theirs' service code (ours' common lacks it)
# N16: maskEmail + MaskingSupport only used inside common; DateUtils.today changed on both sides (outside scope)
w $C/text/TextUtils.java <<'EOF'
package com.example.common.text;

public final class TextUtils {
    private TextUtils() {
    }

    public static String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    public static String maskEmail(String email) {
        int at = email == null ? -1 : email.indexOf('@');
        return at <= 1 ? email : email.charAt(0) + "***" + email.substring(at);
    }
}
EOF
w $C/text/MaskingSupport.java <<'EOF'
package com.example.common.text;

public final class MaskingSupport {
    private MaskingSupport() {
    }

    public static String maskContact(String contact) {
        return contact != null && contact.contains("@") ? TextUtils.maskEmail(contact) : TextUtils.maskPhone(contact);
    }
}
EOF
# G10b: theirs removes CommonConstants.LEGACY_TENANT (ours' new export code still uses it)
w $C/CommonConstants.java <<'EOF'
package com.example.common;

public final class CommonConstants {
    public static final String DEFAULT_TENANT = "t0";

    private CommonConstants() {
    }
}
EOF
w $C/time/DateUtils.java <<'EOF'
package com.example.common.time;

import java.time.LocalDate;
import java.time.ZoneId;

public final class DateUtils {
    private DateUtils() {
    }

    public static String today() {
        return LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
    }
}
EOF

git add -A
git commit -q -m "theirs: coupons, refunds, payment config, customer order query"

############################################################################ OURS
git checkout -q main
git checkout -q -b ours
export GIT_AUTHOR_DATE="2026-01-06T10:00:00+08:00" GIT_COMMITTER_DATE="2026-01-06T10:00:00+08:00"

# G1c: ours removes DEFAULT_CURRENCY (inlined into Order)
w $J/constants/OrderConstants.java <<'EOF'
package com.example.order.constants;

public final class OrderConstants {

    public static final int MAX_ITEMS = 50;

    public static final String ORDER_NO_PREFIX = "ORD";

    public static final int ORDER_NO_WIDTH = 10;

    public static final String EXPORT_SEPARATOR = ",";

    public static final String LEGACY_CHANNEL = "fax";

    private OrderConstants() {
    }
}
EOF
sed -i 's/private String currency = OrderConstants.DEFAULT_CURRENCY;/private String currency = "CNY";/; /import com.example.order.constants.OrderConstants;/d' $J/model/Order.java

# G7b: calculatePrice also adds tax, but at the beginning (clean textual merge, double tax semantically)
python3 - "$J/service/PricingService.java" <<'EOF'
import sys
p=sys.argv[1]; s=open(p).read()
s=s.replace("""        BigDecimal amount = order.getAmount();
""","""        BigDecimal amount = order.getAmount() == null ? null : order.getAmount().multiply(new BigDecimal("1.06"));
""")
open(p,'w').write(s)
EOF

# G7a: cancelOrder modified by ours (paid orders cannot be cancelled) ; N9: same getOrder edit as theirs
python3 - "$J/service/impl/OrderServiceImpl.java" <<'EOF'
import sys
p=sys.argv[1]; s=open(p).read()
s=s.replace("""            throw new IllegalArgumentException("order not found: " + id);
        }
        orderMapper.updateStatus""","""            throw new IllegalArgumentException("order not found: " + id);
        }
        if (order.getStatus() == OrderStatus.PAID) {
            throw new IllegalStateException("paid order cannot be cancelled: " + id);
        }
        orderMapper.updateStatus""")
s=s.replace("""    public Order getOrder(Long id) {
        return orderMapper.selectById(id);""","""    public Order getOrder(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
        return orderMapper.selectById(id);""")
open(p,'w').write(s)
EOF

# G4 target: ours-only implementation of OrderService
w $J/service/impl/CachedOrderService.java <<'EOF'
package com.example.order.service.impl;

import com.example.order.model.Order;
import com.example.order.model.OrderStatus;
import com.example.order.service.OrderService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Primary
@Service("cachedOrderService")
@ConditionalOnProperty(name = "order.cache.enabled", havingValue = "true")
public class CachedOrderService implements OrderService {
    private final OrderServiceImpl delegate;
    private final Map<Long, Order> cache = new ConcurrentHashMap<>();

    public CachedOrderService(OrderServiceImpl delegate) {
        this.delegate = delegate;
    }

    @Override
    public Order createOrder(Order order) {
        Order created = delegate.createOrder(order);
        cache.put(created.getId(), created);
        return created;
    }

    @Override
    public Order getOrder(Long id) {
        return cache.computeIfAbsent(id, delegate::getOrder);
    }

    @Override
    public void cancelOrder(Long id) {
        cache.remove(id);
        delegate.cancelOrder(id);
    }

    public boolean isClosed(Long id) {
        Order order = getOrder(id);
        return order != null && order.getStatus() == OrderStatus.CLOSED;
    }
}
EOF

# G6: ours-only new code still using generate(Long) and applyDiscount(BigDecimal,int); N6: ours-only @Value with key in ours yml
w $J/export/OrderExportService.java <<'EOF'
package com.example.order.export;

import com.example.common.CommonConstants;
import com.example.order.constants.OrderConstants;
import com.example.order.mapper.OrderMapper;
import com.example.order.model.Order;
import com.example.order.service.PricingService;
import com.example.order.util.OrderNumberGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

import static com.example.order.export.ExportLimits.MAX_ITEMS;

@Service
public class OrderExportService {
    private final OrderMapper orderMapper;
    private final PricingService pricingService;

    @Value("${order.export.batch-size}")
    private int batchSize;

    public OrderExportService(OrderMapper orderMapper, PricingService pricingService) {
        this.orderMapper = orderMapper;
        this.pricingService = pricingService;
    }

    public String exportLine(Long id) {
        Order order = orderMapper.selectById(id);
        BigDecimal memberPrice = pricingService.applyDiscount(order.getAmount(), 10);
        String sep = OrderConstants.EXPORT_SEPARATOR;
        return OrderNumberGenerator.generate(id) + sep + memberPrice + sep + batchSize + sep + CommonConstants.LEGACY_TENANT;
    }

    public int maxLines() {
        return Math.min(batchSize, MAX_ITEMS);
    }
}
EOF

# ours-only limits class: own MAX_ITEMS (N14) and a field initializer using the constant theirs renames (G9a)
w $J/export/ExportLimits.java <<'EOF'
package com.example.order.export;

import com.example.order.constants.OrderConstants;

public final class ExportLimits {
    public static final int MAX_ITEMS = 1000;

    public static final String SEP = OrderConstants.EXPORT_SEPARATOR;

    private ExportLimits() {
    }
}
EOF

# G9c: ours adds a method that still uses the field theirs renamed (git merges this file cleanly)
python3 - "$J/service/ShippingService.java" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
s=s.rstrip()[:-1].rstrip() + '''

    public boolean isFreeShipping(Order order) {
        return order.getAmount() != null && order.getAmount().compareTo(freeThreshold) >= 0;
    }
}
'''
open(p,'w').write(s)
PY

# N16: ours changes DateUtils.today differently (sibling module: never reported as both-modified)
w common/src/main/java/com/example/common/time/DateUtils.java <<'EOF'
package com.example.common.time;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

public final class DateUtils {
    private DateUtils() {
    }

    public static String today() {
        return LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
    }
}
EOF

cat >> $R/application.yml <<'EOF'
  cache:
    enabled: false
  export:
    batch-size: 500
EOF

git add -A
git commit -q -m "ours: order cache, export, currency inlined, stricter cancel"
git checkout -q main
echo "sample repo created at $DEST"
git log --oneline --all --graph
