package com.uav.remote.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.NoSuchElementException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> badRequest(IllegalArgumentException ex) { return Map.of("code", 400, "message", ex.getMessage() == null ? "请求参数错误" : ex.getMessage()); }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, Object> notFound(NoSuchElementException ex) { return Map.of("code", 404, "message", ex.getMessage() == null ? "资源不存在" : ex.getMessage()); }
}
