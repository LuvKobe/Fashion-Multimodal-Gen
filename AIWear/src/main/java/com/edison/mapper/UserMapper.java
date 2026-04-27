package com.edison.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edison.entity.User;
import org.apache.ibatis.annotations.Mapper;

// 用户mapper
@Mapper
public interface UserMapper extends BaseMapper<User> {

}
