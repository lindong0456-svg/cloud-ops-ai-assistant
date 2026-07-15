package com.cloudops.security.dto;

import java.util.List;

public record LoginResponse(
        String token,
        String userId,
        String username,
        String tenantId,
        String deptId,
        List<String> roles,
        List<String> permissions
) {}
