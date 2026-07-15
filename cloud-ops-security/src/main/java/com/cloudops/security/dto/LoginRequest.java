package com.cloudops.security.dto;

public record LoginRequest(
        String username,
        String password
) {}
