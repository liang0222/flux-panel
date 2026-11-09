package com.admin.common.dto;

import cloud.tianai.captcha.validator.common.model.dto.ImageCaptchaTrack;
import lombok.Data;


/**
 * 验证码验证请求DTO
 */
@Data
public class CaptchaVerifyDto {

    private String id;

    private ImageCaptchaTrack data;

}
