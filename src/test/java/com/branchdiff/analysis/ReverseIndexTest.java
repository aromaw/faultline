package com.branchdiff.analysis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The name prefilter of the on-demand reverse edges. */
class ReverseIndexTest {
    @Test
    void simpleNameOfEveryKeyKindAMemberCanDependOn() {
        assertEquals("edit", ContextBuilder.simpleNameOfKey("M:com.x.RoleController#edit(SysRole)"));
        assertEquals("refresh", ContextBuilder.simpleNameOfKey("M:com.x.TokenService#refresh(/2)"));
        assertEquals("TokenService", ContextBuilder.simpleNameOfKey("M:com.x.TokenService#<init>(RedisCache)"));
        assertEquals("Inner", ContextBuilder.simpleNameOfKey("M:com.x.Outer.Inner#<init>()"));
        assertEquals("noticeReadService", ContextBuilder.simpleNameOfKey("F:com.x.SysNoticeController.noticeReadService"));
        assertEquals("SysNotice", ContextBuilder.simpleNameOfKey("T:com.x.domain.SysNotice"));
        assertEquals("Inner", ContextBuilder.simpleNameOfKey("T:com.x.Outer$Inner"));
        assertNull(ContextBuilder.simpleNameOfKey("CFG:token.secret"));
        assertNull(ContextBuilder.simpleNameOfKey("BEAN:com.x.Service"));
        assertNull(ContextBuilder.simpleNameOfKey("nokind"));
    }
}
