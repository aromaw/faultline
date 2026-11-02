package com.branchdiff;

import com.branchdiff.util.Names;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class NamesTest {
    @Test
    void erasesGenericsAndQualifiers() {
        assertEquals("List", Names.simpleErased("java.util.List<java.lang.String>"));
        assertEquals("Map", Names.simpleErased("Map<String, List<Long>>"));
        assertEquals("String[]", Names.simpleErased("java.lang.String..."));
        assertEquals("Inner", Names.simpleErased("Outer.Inner"));
        assertEquals("int[][]", Names.simpleErased("int[][]"));
    }

    @Test
    void parsesPlaceholders() {
        assertEquals(List.of("a.b:1", "c"), Names.placeholderBodies("${a.b:1}-${c}"));
        assertEquals(List.of("x:${y:z}"), Names.placeholderBodies("${x:${y:z}}"));
        assertEquals(List.of(), Names.placeholderBodies("#{T(java.lang.Math).random()}"));
        assertArrayEquals(new String[]{"a.b", "1"}, Names.splitPlaceholder("a.b:1"));
        assertArrayEquals(new String[]{"a.b", null}, Names.splitPlaceholder("a.b"));
        assertArrayEquals(new String[]{"x", "${y:z}"}, Names.splitPlaceholder("x:${y:z}"));
    }

    @Test
    void springNames() {
        assertEquals("orderService", Names.decapitalize("OrderService"));
        assertEquals("URLHelper", Names.decapitalize("URLHelper"));
        assertEquals("gateway-url", Names.kebab("gatewayUrl"));
        assertEquals(Names.relaxedKey("order.max-items"), Names.relaxedKey("order.maxItems"));
        assertEquals(Names.relaxedKey("order.max_items"), Names.relaxedKey("order.max-items"));
    }
}
