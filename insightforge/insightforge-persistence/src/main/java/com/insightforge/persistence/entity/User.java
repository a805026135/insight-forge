package com.insightforge.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("users")
public class User extends BaseEntity {
    private String username;
    private String password;
    private String role;       // "user" or "merchant"
    private String displayName;
}
