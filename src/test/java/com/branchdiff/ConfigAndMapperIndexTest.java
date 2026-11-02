package com.branchdiff;

import com.branchdiff.index.ConfigIndex;
import com.branchdiff.index.MapperXmlIndex;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigAndMapperIndexTest {
    @Test
    void yamlAndPropertiesAllProfiles() {
        ConfigIndex c = new ConfigIndex();
        c.addFile("src/main/resources/application.yml", """
                order:
                  max-items: 50
                  payment:
                    gateway-url: http://x
                  channels:
                    - sms
                    - mail
                ---
                spring:
                  config:
                    activate:
                      on-profile: dev
                order:
                  retry:
                    max-attempts: 3
                """);
        c.addFile("src/main/resources/application-prod.properties", """
                # comment
                order.notify.channel=email
                order.long.value = a \\
                   b
                order.colon: x
                """);
        assertTrue(c.has("order.max-items"));
        assertTrue(c.has("order.maxItems"), "relaxed binding");
        assertTrue(c.has("order.payment"), "prefix of existing keys");
        assertTrue(c.has("order.channels[0]"));
        assertTrue(c.has("order.channels"));
        assertTrue(c.has("order.retry.max-attempts"));
        assertEquals("dev", c.find("order.retry.max-attempts").get(0).profile());
        assertTrue(c.has("order.notify.channel"));
        assertTrue(c.has("order.long.value"));
        assertTrue(c.has("order.colon"));
        assertFalse(c.has("order.coupon.expire-days"));
        assertFalse(c.has("order.max"), "partial segment must not match");
        assertEquals(2, c.find("order.max-items").get(0).line());
        assertEquals("prod", c.find("order.notify.channel").get(0).profile());
    }

    @Test
    void recognizesConfigFiles() {
        assertTrue(ConfigIndex.isConfigFile("svc/src/main/resources/application.yml"));
        assertTrue(ConfigIndex.isConfigFile("svc/src/main/resources/application-dev.yaml"));
        assertTrue(ConfigIndex.isConfigFile("svc/src/main/resources/bootstrap.properties"));
        assertFalse(ConfigIndex.isConfigFile("svc/src/test/resources/application.yml"));
        assertFalse(ConfigIndex.isConfigFile("svc/src/main/resources/logback.xml"));
    }

    @Test
    void mapperXmlWithDoctypeIsParsedOffline() {
        MapperXmlIndex m = new MapperXmlIndex();
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.x.OrderMapper">
                    <resultMap id="m" type="com.x.Order"/>
                    <select id="selectById" resultMap="m">select 1</select>
                    <update id="updateStatus">update t</update>
                </mapper>
                """;
        assertTrue(MapperXmlIndex.maybeMapperXml("src/main/resources/mapper/OrderMapper.xml", xml));
        m.addFile("src/main/resources/mapper/OrderMapper.xml", xml);
        assertTrue(m.errors().isEmpty(), m.errors().toString());
        assertEquals(2, m.statements("com.x.OrderMapper").size(), "resultMap is not a statement");
        assertEquals(5, m.statements("com.x.OrderMapper").get("selectById").line());
    }
}
