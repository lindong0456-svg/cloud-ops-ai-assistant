package com.cloudops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("mock_resource_relation")
public class MockResourceRelation {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String resourceId;
    private String relatedId;
    private String relationType;
    private String relationDesc;

}
