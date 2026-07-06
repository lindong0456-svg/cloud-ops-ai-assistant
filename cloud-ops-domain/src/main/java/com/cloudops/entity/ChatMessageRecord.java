package com.cloudops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 对话记忆持久化实体
 * session_id + seq 联合定位一条消息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("chat_memory")
public class ChatMessageRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionId;
    private Integer seq;
    private String role;
    private String content;
    private LocalDateTime createdAt;
}
