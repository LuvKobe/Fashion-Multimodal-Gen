package com.edison.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

// 用户表的实体类
@Data
@TableName("users")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    // 用户唯一标识
    private String username;

    private String email;

    @TableField("password_hash")
    private String passwordhash;
}
