package com.aegis.admin.service.tenant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TenantManageService#maskPhone(String)} 单元测试。
 *
 * @author wang.zhen
 */
class TenantManageServiceTest {

    @Test
    void maskPhone_shouldMaskMiddleDigits() {
        assertThat(TenantManageService.maskPhone("13800000000")).isEqualTo("138****0000");
    }

    @Test
    void maskPhone_shortValue_shouldFullyMask() {
        assertThat(TenantManageService.maskPhone("12345")).isEqualTo("*****");
        assertThat(TenantManageService.maskPhone("123456")).isEqualTo("******");
    }

    @Test
    void maskPhone_boundarySevenDigits_shouldKeepHeadAndTail() {
        assertThat(TenantManageService.maskPhone("1234567")).isEqualTo("123****4567");
    }

    @Test
    void maskPhone_blankOrNull_shouldReturnAsIs() {
        assertThat(TenantManageService.maskPhone(null)).isNull();
        assertThat(TenantManageService.maskPhone("")).isEmpty();
        assertThat(TenantManageService.maskPhone("   ")).isEqualTo("   ");
    }
}
