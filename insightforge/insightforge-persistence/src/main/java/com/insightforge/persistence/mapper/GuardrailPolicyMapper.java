package com.insightforge.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insightforge.persistence.entity.GuardrailPolicyEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 护栏策略 Mapper（Phase 8）。版本化读取「某活动最新一条策略」。
 */
@Mapper
public interface GuardrailPolicyMapper extends BaseMapper<GuardrailPolicyEntity> {

    @Select("SELECT * FROM guardrail_policy WHERE activity_id = #{aid} ORDER BY id DESC LIMIT 1")
    GuardrailPolicyEntity selectLatestByActivity(@Param("aid") Long aid);
}
