package com.mall.common;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理器：所有异常统一转换为 Result 返回，并输出带请求上下文的诊断日志。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** HTTP 429 Too Many Requests */
    private static final int CODE_TOO_MANY_REQUESTS = 429;

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBizException(BizException e, HttpServletRequest request) {
        log.warn("业务异常: uri={}, method={}, code={}, msg={}",
                request.getRequestURI(), request.getMethod(), e.getCode(), e.getMessage());
        Result<Void> body = Result.fail(e.getCode(), e.getMessage());
        // 限流类业务码映射为 HTTP 429，其余保持 HTTP 200 + Result 约定
        if (e.getCode() == CODE_TOO_MANY_REQUESTS) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(body);
        }
        return ResponseEntity.ok().body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e, HttpServletRequest request) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败: uri={}, method={}, errors={}",
                request.getRequestURI(), request.getMethod(), detail);
        return Result.fail(Result.CODE_BAD_REQUEST, "参数校验失败: " + detail);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParameter(MissingServletRequestParameterException e, HttpServletRequest request) {
        log.warn("缺少请求参数: uri={}, method={}, param={}, type={}",
                request.getRequestURI(), request.getMethod(), e.getParameterName(), e.getParameterType());
        return Result.fail(Result.CODE_BAD_REQUEST, "缺少必需参数: " + e.getParameterName());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleHttpMessageNotReadable(HttpMessageNotReadableException e, HttpServletRequest request) {
        log.warn("请求体解析失败: uri={}, method={}, error={}",
                request.getRequestURI(), request.getMethod(), e.getMessage());
        return Result.fail(Result.CODE_BAD_REQUEST, "请求体格式错误");
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e, HttpServletRequest request) {
        log.error("系统异常: uri={}, method={}", request.getRequestURI(), request.getMethod(), e);
        return Result.fail(Result.CODE_ERROR, "系统异常，请稍后重试");
    }

    private String formatFieldError(FieldError fieldError) {
        return fieldError.getField() + " " + fieldError.getDefaultMessage();
    }
}
