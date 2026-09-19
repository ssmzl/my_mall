package com.mall.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResultTest {

    @Test
    void okShouldCarryDataAndSuccessCode() {
        Result<String> result = Result.ok("hello");
        assertThat(result.getCode()).isEqualTo(Result.CODE_SUCCESS);
        assertThat(result.getMessage()).isNotBlank();
        assertThat(result.getData()).isEqualTo("hello");
    }

    @Test
    void okWithoutDataShouldSucceed() {
        Result<Void> result = Result.ok();
        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).isNull();
    }

    @Test
    void failWithMessageShouldUseBizCode() {
        Result<Void> result = Result.fail("库存不足");
        assertThat(result.getCode()).isEqualTo(Result.CODE_BIZ_ERROR);
        assertThat(result.getMessage()).isEqualTo("库存不足");
        assertThat(result.getData()).isNull();
    }

    @Test
    void failWithCodeAndMessage() {
        Result<Void> result = Result.fail(Result.CODE_UNAUTHORIZED, "未登录");
        assertThat(result.getCode()).isEqualTo(401);
        assertThat(result.getMessage()).isEqualTo("未登录");
    }
}
