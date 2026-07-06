package com.cloudops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudops.entity.ChatMessageRecord;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 对话记忆 Mapper
 */
public interface ChatMessageMapper extends BaseMapper<ChatMessageRecord> {

    @Select("SELECT * FROM chat_memory WHERE session_id = #{sessionId} ORDER BY seq")
    List<ChatMessageRecord> selectBySessionId(@Param("sessionId") String sessionId);

    @Delete("DELETE FROM chat_memory WHERE session_id = #{sessionId}")
    int deleteBySessionId(@Param("sessionId") String sessionId);
}
